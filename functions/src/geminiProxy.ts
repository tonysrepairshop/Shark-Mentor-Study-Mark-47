import { onRequest } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import * as admin from "firebase-admin";

/**
 * SharkMentor standard-mode Gemini proxy (Gen 2 HTTPS).
 *
 * - Caller must send `Authorization: Bearer <Firebase ID token>`.
 * - API keys are ONLY read from Secret Manager (`GEMINI_API_KEY`, `GEMINI_API_KEYS` comma pool).
 * - Strict model policy: `gemini-3.6-flash` first, single fallback `gemini-3.5-flash-lite`
 *   only on 404/NOT_FOUND, 429, 503 or network/timeout failures.
 * - Body: `{ model?, contents, systemInstruction?, generationConfig?, tools?, toolConfig?, stream? }`.
 * - Response: upstream Gemini JSON (or SSE passthrough when `stream: true`),
 *   with the actually used model in the `X-Shark-Model` header.
 */

const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");
const GEMINI_API_KEYS = defineSecret("GEMINI_API_KEYS");

const PROXY_PRIMARY_MODEL = "gemini-3.6-flash";
const PROXY_FALLBACK_MODEL = "gemini-3.5-flash-lite";
const PROXY_ALLOWED_MODELS: readonly string[] = [PROXY_PRIMARY_MODEL, PROXY_FALLBACK_MODEL];

const FALLBACK_STATUS_CODES = new Set<number>([404, 429, 503]);
const UPSTREAM_TIMEOUT_MS = 90_000;
const RATE_LIMIT_WINDOW_MS = 60_000;
const RATE_LIMIT_MAX_REQUESTS = 30;
const MAX_BODY_BYTES = 12 * 1024 * 1024;

const rateBuckets = new Map<string, { windowStart: number; count: number }>();

function isRateLimited(uid: string): boolean {
  const now = Date.now();
  const bucket = rateBuckets.get(uid);
  if (!bucket || now - bucket.windowStart >= RATE_LIMIT_WINDOW_MS) {
    rateBuckets.set(uid, { windowStart: now, count: 1 });
    if (rateBuckets.size > 5000) {
      for (const [key, value] of rateBuckets) {
        if (now - value.windowStart >= RATE_LIMIT_WINDOW_MS) rateBuckets.delete(key);
      }
    }
    return false;
  }
  bucket.count += 1;
  return bucket.count > RATE_LIMIT_MAX_REQUESTS;
}

function readSecretSafe(secret: { value(): string }): string {
  try {
    return (secret.value() || "").trim();
  } catch {
    return "";
  }
}

function collectKeys(): string[] {
  const single = readSecretSafe(GEMINI_API_KEY);
  const pool = readSecretSafe(GEMINI_API_KEYS)
    .split(/[,;\n]/)
    .map((k) => k.trim())
    .filter((k) => k.length > 0);
  return Array.from(new Set([...(single ? [single] : []), ...pool]));
}

/** Removes anything that might look like an API key or keyed URL from a message. */
function sanitize(message: string, keys: string[]): string {
  let out = message || "";
  for (const key of keys) {
    if (key) out = out.split(key).join("***");
  }
  return out
    .replace(/key=[^&\s"]+/gi, "key=***")
    .replace(/\bAIza[0-9A-Za-z_-]{10,}/g, "***")
    .replace(/\bAQ\.[0-9A-Za-z_.-]{10,}/g, "***")
    .slice(0, 500);
}

function endpointsFor(apiKey: string, model: string, stream: boolean): string[] {
  const method = stream ? "streamGenerateContent?alt=sse" : "generateContent";
  const studio = `https://generativelanguage.googleapis.com/v1beta/models/${model}:${method}`;
  if (apiKey.startsWith("AQ.")) {
    return [`https://aiplatform.googleapis.com/v1/publishers/google/models/${model}:${method}`, studio];
  }
  return [studio];
}

function orderedModels(requested: unknown): string[] {
  // Whitelist enforcement: anything else is silently mapped to the primary model.
  if (typeof requested === "string" && PROXY_ALLOWED_MODELS.includes(requested) && requested === PROXY_FALLBACK_MODEL) {
    return [PROXY_FALLBACK_MODEL];
  }
  return [PROXY_PRIMARY_MODEL, PROXY_FALLBACK_MODEL];
}

interface UpstreamAttempt {
  response?: Response;
  status: number;
  errorText: string;
  fallbackEligible: boolean;
}

async function callUpstream(url: string, apiKey: string, body: string): Promise<UpstreamAttempt> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": apiKey },
      body,
      signal: controller.signal
    });
    if (response.ok) {
      return { response, status: response.status, errorText: "", fallbackEligible: false };
    }
    const errorText = await response.text().catch(() => "");
    const notFound = /NOT_FOUND/i.test(errorText);
    return {
      status: response.status,
      errorText,
      fallbackEligible: FALLBACK_STATUS_CODES.has(response.status) || notFound
    };
  } catch (err) {
    const msg = err instanceof Error ? err.message : "network error";
    return { status: 0, errorText: msg, fallbackEligible: true };
  } finally {
    clearTimeout(timer);
  }
}

export const geminiProxy = onRequest(
  {
    region: "us-central1",
    secrets: [GEMINI_API_KEY, GEMINI_API_KEYS],
    timeoutSeconds: 300,
    memory: "512MiB",
    cors: true,
    maxInstances: 20
  },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ error: { code: 405, status: "METHOD_NOT_ALLOWED", message: "POST only" } });
      return;
    }

    // 1) Firebase Auth
    const authHeader = req.get("Authorization") || "";
    const match = authHeader.match(/^Bearer\s+(.+)$/i);
    if (!match) {
      res.status(401).json({ error: { code: 401, status: "UNAUTHENTICATED", message: "Missing ID token" } });
      return;
    }
    let uid: string;
    try {
      const decoded = await admin.auth().verifyIdToken(match[1].trim());
      uid = decoded.uid;
    } catch {
      res.status(401).json({ error: { code: 401, status: "UNAUTHENTICATED", message: "Invalid ID token" } });
      return;
    }

    // 2) Per-user rate limit
    if (isRateLimited(uid)) {
      res.set("Retry-After", "60");
      res.status(429).json({
        error: { code: 429, status: "RESOURCE_EXHAUSTED", message: "Proxy rate limit exceeded, retry later" }
      });
      return;
    }

    // 3) Payload
    const payload = (typeof req.body === "object" && req.body) ? req.body as Record<string, unknown> : {};
    if (!Array.isArray(payload.contents) || payload.contents.length === 0) {
      res.status(400).json({ error: { code: 400, status: "INVALID_ARGUMENT", message: "contents is required" } });
      return;
    }
    const stream = payload.stream === true;
    const upstreamBody: Record<string, unknown> = { contents: payload.contents };
    if (payload.systemInstruction) upstreamBody.systemInstruction = payload.systemInstruction;
    if (payload.generationConfig) upstreamBody.generationConfig = payload.generationConfig;
    if (payload.tools) upstreamBody.tools = payload.tools;
    if (payload.toolConfig) upstreamBody.toolConfig = payload.toolConfig;
    if (payload.safetySettings) upstreamBody.safetySettings = payload.safetySettings;
    const bodyStr = JSON.stringify(upstreamBody);
    if (Buffer.byteLength(bodyStr) > MAX_BODY_BYTES) {
      res.status(413).json({ error: { code: 413, status: "INVALID_ARGUMENT", message: "Payload too large" } });
      return;
    }

    const keys = collectKeys();
    if (keys.length === 0) {
      res.status(503).json({
        error: { code: 503, status: "UNAVAILABLE", message: "Proxy is not configured (missing GEMINI secrets)" }
      });
      return;
    }

    // 4) Strict model policy with fallback
    let lastStatus = 502;
    let lastError = "Upstream unavailable";
    for (const model of orderedModels(payload.model)) {
      let fallbackEligible = false;
      for (const apiKey of keys) {
        for (const url of endpointsFor(apiKey, model, stream)) {
          const attempt = await callUpstream(url, apiKey, bodyStr);
          if (attempt.response) {
            res.set("X-Shark-Model", model);
            res.set("Cache-Control", "no-cache");
            if (!stream) {
              const text = await attempt.response.text();
              res.status(200).type("application/json").send(text);
              return;
            }
            res.status(200);
            res.set("Content-Type", "text/event-stream; charset=utf-8");
            res.set("Connection", "keep-alive");
            res.set("X-Accel-Buffering", "no");
            res.flushHeaders();
            try {
              const reader = attempt.response.body?.getReader();
              if (reader) {
                for (;;) {
                  const { done, value } = await reader.read();
                  if (done) break;
                  if (value) res.write(Buffer.from(value));
                }
              }
            } catch (err) {
              const msg = sanitize(err instanceof Error ? err.message : "stream error", keys);
              res.write(`data: ${JSON.stringify({ error: { code: 502, status: "UNAVAILABLE", message: msg } })}\n\n`);
            }
            res.end();
            return;
          }
          lastStatus = attempt.status || 503;
          lastError = attempt.errorText;
          if (attempt.fallbackEligible) fallbackEligible = true;
          console.warn(`[geminiProxy] uid=${uid} model=${model} status=${attempt.status}`);
        }
      }
      if (!fallbackEligible) break;
    }

    let upstreamMessage = "Gemini request failed";
    let upstreamStatus = "UNAVAILABLE";
    try {
      const parsed = JSON.parse(lastError);
      const errObj = Array.isArray(parsed) ? parsed[0]?.error : parsed?.error;
      if (errObj?.message) upstreamMessage = String(errObj.message);
      if (errObj?.status) upstreamStatus = String(errObj.status);
    } catch {
      if (lastError) upstreamMessage = lastError;
    }
    // Upstream auth errors are a server configuration issue; never expose them as client 401.
    const clientStatus = lastStatus === 401 || lastStatus === 403 ? 502 : lastStatus;
    res.status(clientStatus).json({
      error: { code: clientStatus, status: upstreamStatus, message: sanitize(upstreamMessage, keys) }
    });
  }
);
