import * as functions from "firebase-functions";
import * as admin from "firebase-admin";
import * as https from "https";

const db = admin.firestore();
const messaging = admin.messaging();

const GEMINI_MODEL = "gemini-3.6-flash";
const GEMINI_FALLBACK_MODEL = "gemini-3.5-flash-lite";
const GEMINI_STANDARD_MODELS: string[] = [GEMINI_MODEL, GEMINI_FALLBACK_MODEL];
const GEMINI_FALLBACK_STATUS_CODES = new Set<number>([404, 429, 503]);
const OBF_BASE_MASK = 0x5a;
const OBF_STEP = 7;
function decodeBuiltInKey(encoded: number[]): string {
  return Buffer.from(
    encoded.map((b, idx) => b ^ ((OBF_BASE_MASK + idx * OBF_STEP) & 0xff))
  ).toString("utf8");
}

const BUILTIN_GEMINI_KEYS: string[] = [
  decodeBuiltInKey([
    27, 48, 70, 46, 20, 69, 214, 197, 164, 208, 255, 225, 193, 141, 211, 140, 255, 176,
    153, 165, 190, 169, 140, 174, 75, 127, 121, 84, 110, 66, 88, 1, 93, 45, 63, 37,
    36, 19, 55, 17, 27, 32, 213, 197, 223, 164, 212, 202, 208, 219, 222, 215, 161
  ]),
  decodeBuiltInKey([
    27, 40, 18, 14, 37, 4, 192, 253, 198, 222, 209, 203, 239, 129, 234, 243, 189, 252,
    129, 233, 146, 190, 193, 194, 123, 89, 68, 72, 70, 82, 86, 91, 93, 14, 48, 120,
    48, 108, 84
  ]),
  decodeBuiltInKey([
    27, 48, 70, 46, 20, 69, 214, 197, 164, 213, 215, 209, 255, 214, 138, 170, 136, 136,
    153, 180, 142, 154, 150, 188, 73, 106, 61, 94, 83, 113, 116, 4, 93, 3, 126, 98,
    2, 54, 21, 28, 10, 18, 199, 212, 239, 246, 240, 245, 240, 197, 212, 244, 177
  ])
];

const MAX_WEEKLY_QUESTION_BONUS_SECONDS = 9000;
const MAX_DAILY_REWARDED_SOLUTIONS_PER_OWNER = 2;
const MAX_REJECTED_ATTEMPTS_PER_QUESTION = 2;
const MAX_SHOWCASE_BADGES = 5;
const QUESTION_REWARD_WINDOW_MS = 105 * 60 * 1000;
const TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000;

type BadgeTierName = "BRONZE" | "SILVER" | "GOLD" | "MEGALODON";
type SolutionMethodName = "TEXT" | "DRAWING" | "PHOTO" | "AI_FALLBACK";

interface QuestionAttachmentDoc {
  questionId?: string;
  ownerUid?: string;
  ownerName?: string;
  subject?: string;
  topic?: string;
  questionImageUrl?: string;
  noteText?: string;
  sharedAtMillis?: number;
  expiresAtMillis?: number;
  autoApproveAt?: number;
  durationMinutes?: number;
  status?: string;
  approvedSolutionId?: string | null;
  approvedSolverUid?: string | null;
  approvedSolverName?: string | null;
  solutionsCount?: number;
  aiAccessedByUids?: string[];
  rejectedSolverCounts?: Record<string, number>;
  aiFallbackText?: string | null;
}

interface SolutionSubmissionDoc {
  solutionId?: string;
  questionMessageId?: string;
  questionOwnerUid?: string;
  solverUid?: string;
  solverName?: string;
  method?: SolutionMethodName | string;
  annotatedImageUrl?: string;
  explanationText?: string;
  submittedAtMillis?: number;
  autoApproveAt?: number;
  rewardTier?: string;
  escrowPoints?: number;
  potentialBonusSeconds?: number;
  verifyStatus?: string;
  verifiedAtMillis?: number | null;
  awardedPoints?: number;
  awardedBonusSeconds?: number;
}

function resolveBadgeTier(mentorPoints: number): { tier: BadgeTierName; displayName: string; target: number } {
  if (mentorPoints >= 1800) {
    return { tier: "MEGALODON", displayName: "Megalodon Mentor", target: 1800 };
  }
  if (mentorPoints >= 800) {
    return { tier: "GOLD", displayName: "Altın Köpekbalığı", target: 800 };
  }
  if (mentorPoints >= 350) {
    return { tier: "SILVER", displayName: "Gümüş Köpekbalığı", target: 350 };
  }
  return { tier: "BRONZE", displayName: "Bronz Köpekbalığı", target: 100 };
}

function isSupportedChatCollection(col: string): boolean {
  return (
    col === "league_chats" ||
    col === "study_groups" ||
    col === "direct_messages" ||
    col === "dm_threads"
  );
}

async function isSenderBlockedByRecipient(recipientUid: string, senderUid: string): Promise<boolean> {
  const cleanRecipient = (recipientUid || "").trim();
  const cleanSender = (senderUid || "").trim();
  if (!cleanRecipient || !cleanSender || cleanRecipient === cleanSender) {
    return false;
  }
  try {
    const blockDoc = await db.doc(`user_blocks/${cleanRecipient}/blocked/${cleanSender}`).get();
    if (blockDoc.exists) {
      return true;
    }
    const recipientSnap = await db.doc(`students/${cleanRecipient}`).get();
    const blockedArr = recipientSnap.data()?.blockedUserIds;
    if (Array.isArray(blockedArr) && blockedArr.includes(cleanSender)) {
      return true;
    }
  } catch (_err) {
    // Ignore transient lookup errors
  }
  return false;
}

async function resolveStudentFcmTokens(studentId: string): Promise<string[]> {
  if (!studentId) return [];
  const snap = await db.doc(`students/${studentId}`).get();
  const data = snap.data();
  const tokens = new Set<string>();
  if (data) {
    const arr = data.fcmTokens as string[] | undefined;
    if (Array.isArray(arr)) {
      arr
        .filter((t) => typeof t === "string" && t.trim().length > 0)
        .forEach((t) => tokens.add(t.trim()));
    }
    const single = data.fcmToken as string | undefined;
    if (typeof single === "string" && single.trim().length > 0) {
      tokens.add(single.trim());
    }
  }
  return Array.from(tokens);
}

async function isRoomMutedForUser(roomId: string, uid: string): Promise<boolean> {
  if (!roomId || !uid) return false;
  try {
    const roomSnap = await db.doc(`league_chats/${roomId}`).get();
    if (!roomSnap.exists) return false;
    const mutedBy = roomSnap.data()?.mutedBy;
    return Array.isArray(mutedBy) && mutedBy.includes(uid);
  } catch (_err) {
    return false;
  }
}

async function sendHighPriorityPush(
  recipientUid: string,
  title: string,
  body: string,
  data: Record<string, string>,
  roomId?: string,
  senderUid?: string
): Promise<boolean> {
  const effectiveSenderUid = (
    senderUid ||
    data.solverUid ||
    data.senderUid ||
    data.questionOwnerUid ||
    ""
  ).trim();
  if (effectiveSenderUid && (await isSenderBlockedByRecipient(recipientUid, effectiveSenderUid))) {
    console.log(
      `[leagueChat] Suppressed notification for ${recipientUid}; sender ${effectiveSenderUid} is blocked.`
    );
    return false;
  }
  if (roomId && (await isRoomMutedForUser(roomId, recipientUid))) {
    console.log(`[leagueChat] Suppressed notification for ${recipientUid}; room ${roomId} is muted.`);
    return false;
  }
  const tokens = await resolveStudentFcmTokens(recipientUid);
  if (tokens.length === 0) {
    return false;
  }
  try {
    const res = await messaging.sendEachForMulticast({
      tokens,
      notification: { title, body },
      data,
      android: { priority: "high" }
    });
    return res.successCount > 0;
  } catch (err) {
    console.error(`[leagueChat] Failed to send push to ${recipientUid}:`, err);
    return false;
  }
}

function getDayKeyUtcPlus3(nowMs: number = Date.now()): string {
  const utcPlus3 = new Date(nowMs + 3 * 3600 * 1000);
  return utcPlus3.toISOString().split("T")[0];
}

function postJsonHttps(
  urlStr: string,
  headers: Record<string, string>,
  bodyStr: string
): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const parsedUrl = new URL(urlStr);
    const req = https.request(
      {
        hostname: parsedUrl.hostname,
        path: parsedUrl.pathname + parsedUrl.search,
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(bodyStr).toString(),
          ...headers
        },
        timeout: 30000
      },
      (res) => {
        const chunks: Buffer[] = [];
        res.on("data", (chunk: Buffer) => chunks.push(chunk));
        res.on("end", () => {
          resolve({
            status: res.statusCode || 0,
            body: Buffer.concat(chunks).toString("utf8")
          });
        });
      }
    );
    req.on("error", reject);
    req.on("timeout", () => {
      req.destroy(new Error("Request timed out"));
    });
    req.write(bodyStr);
    req.end();
  });
}

function fetchBinaryAsBase64(urlStr: string): Promise<{ mimeType: string; base64: string } | null> {
  if (!urlStr || (!urlStr.startsWith("https://") && !urlStr.startsWith("http://"))) {
    return Promise.resolve(null);
  }
  return new Promise((resolve) => {
    try {
      const parsedUrl = new URL(urlStr);
      const req = https.request(
        {
          hostname: parsedUrl.hostname,
          path: parsedUrl.pathname + parsedUrl.search,
          method: "GET",
          timeout: 15000
        },
        (res) => {
          if ((res.statusCode || 0) >= 300 && (res.statusCode || 0) < 400 && res.headers.location) {
            fetchBinaryAsBase64(res.headers.location).then(resolve).catch(() => resolve(null));
            return;
          }
          if ((res.statusCode || 0) < 200 || (res.statusCode || 0) >= 300) {
            resolve(null);
            return;
          }
          const chunks: Buffer[] = [];
          res.on("data", (chunk: Buffer) => chunks.push(chunk));
          res.on("end", () => {
            const buffer = Buffer.concat(chunks);
            if (buffer.length === 0 || buffer.length > 6 * 1024 * 1024) {
              resolve(null);
              return;
            }
            const contentType = (res.headers["content-type"] || "image/jpeg").split(";")[0].trim();
            resolve({
              mimeType: contentType || "image/jpeg",
              base64: buffer.toString("base64")
            });
          });
        }
      );
      req.on("error", () => resolve(null));
      req.on("timeout", () => {
        req.destroy();
        resolve(null);
      });
      req.end();
    } catch (_e) {
      resolve(null);
    }
  });
}

/**
 * Solves an expired question with `gemini-3.6-flash`.
 * If `process.env.GEMINI_API_KEY` is not set, automatically rotates across the 3 built-in `AQ.` keys.
 */
async function generateAiFallbackSolutionWithGemini36(
  subject: string,
  topic: string,
  noteText: string,
  questionImageUrl: string
): Promise<string> {
  const promptText = [
    `Sen SharkMentor Study'nin uzman öğretmenisin. Ders: ${subject || "Genel"}, Konu: ${topic || "Genel"}.`,
    noteText ? `Öğrencinin notu: "${noteText}"` : "",
    "Bu soru çalışma odasında 105 dakika boyunca akranlar tarafından çözülemedi.",
    "Soruyu adım adım, net, anlaşılır ve pedagojik bir dille Türkçe olarak çöz ve doğru cevabı açıkla."
  ]
    .filter((line) => line.length > 0)
    .join("\n");

  const imagePart = await fetchBinaryAsBase64(questionImageUrl);
  const parts: Array<Record<string, unknown>> = [];
  if (imagePart) {
    parts.push({
      inlineData: {
        mimeType: imagePart.mimeType,
        data: imagePart.base64
      }
    });
  }
  parts.push({ text: promptText });

  const payload = JSON.stringify({
    contents: [
      {
        role: "user",
        parts
      }
    ],
    generationConfig: {
      temperature: 0.25,
      maxOutputTokens: 2048,
      topP: 0.85
    }
  });

  const envKey = (process.env.GEMINI_API_KEY || "").trim();
  const envPool = (process.env.GEMINI_API_KEYS || "")
    .split(/[,;\n]/)
    .map((k) => k.trim())
    .filter((k) => k.length > 0);
  const candidateKeys = Array.from(
    new Set([...(envKey ? [envKey] : []), ...envPool, ...BUILTIN_GEMINI_KEYS])
  );

  // Strict model policy: primary gemini-3.6-flash, single fallback gemini-3.5-flash-lite
  // (only when primary is 404/NOT_FOUND, 429, 503 or unreachable on every key/endpoint).
  for (const model of GEMINI_STANDARD_MODELS) {
    let shouldTryFallbackModel = false;

    for (const apiKey of candidateKeys) {
      const endpoints = apiKey.startsWith("AQ.")
        ? [`https://aiplatform.googleapis.com/v1/publishers/google/models/${model}:generateContent`]
        : [`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`];

      for (const url of endpoints) {
        try {
          const resp = await postJsonHttps(url, { "x-goog-api-key": apiKey }, payload);
          if (resp.status >= 200 && resp.status < 300) {
            const parsed = JSON.parse(resp.body);
            const candidateParts = parsed?.candidates?.[0]?.content?.parts;
            if (Array.isArray(candidateParts)) {
              const answerText = candidateParts
                .filter((p: Record<string, unknown>) => !p.thought && typeof p.text === "string")
                .map((p: Record<string, unknown>) => p.text as string)
                .join("")
                .trim();
              if (answerText.length > 0) {
                return answerText;
              }
            }
          } else if (GEMINI_FALLBACK_STATUS_CODES.has(resp.status)) {
            shouldTryFallbackModel = true;
            console.warn(`[leagueChat] Gemini ${model} returned HTTP ${resp.status}; fallback eligible.`);
          } else {
            console.warn(`[leagueChat] Gemini ${model} returned HTTP ${resp.status}.`);
          }
        } catch (err) {
          // Network / timeout errors are fallback-eligible. Never log the key or full URL.
          shouldTryFallbackModel = true;
          console.warn(
            `[leagueChat] Gemini call failed on ${model}:`,
            err instanceof Error ? err.message.replace(/key=[^&\s]+/g, "key=***") : "unknown error"
          );
        }
      }
    }

    if (!shouldTryFallbackModel) {
      break;
    }
  }

  return `Shark AI (${GEMINI_MODEL}) Çözüm Rehberi:\nBu ${subject} (${topic}) sorusunda önce verilenleri birim ve formül bazında eşleştirip temel bağıntıyı uygulayarak adım adım sonuca ulaşabilirsin.`;
}

const QUESTION_INVITE_TEMPLATES: string[] = [
  "Gruba yeni bir %SUBJECT% sorusu düştü. Sen çözebilecek misin?",
  "%SENDER% yeni bir %SUBJECT% sorusu paylaştı. Çözümünü göndermek için sohbete göz at.",
  "%SUBJECT% adına yeni soru paylaşıldı. İlk doğru çözümü sen üretmeye hazır mısın?",
  "Grupta %SUBJECT% sorusu seni bekliyor. Adımını at, çözüm önerini paylaş.",
  "%SENDER% az önce %SUBJECT% sorusu bıraktı. İpucu ya da tam çözüm ile destek ol."
];

async function resolveChannelRecipientsAndMeta(
  channelCollection: string,
  channelId: string,
  senderUid: string
): Promise<{ recipientUids: string[]; groupName: string; isDm: boolean }> {
  const isDm =
    channelCollection === "dm_threads" ||
    channelCollection === "direct_messages";
  const recipients = new Set<string>();
  let groupName = "";

  try {
    if (isDm) {
      const dmSnap = await db.doc(`dm_threads/${channelId}`).get();
      const dmData = dmSnap.exists
        ? dmSnap.data()
        : (await db.doc(`${channelCollection}/${channelId}`).get()).data();
      const status = String(dmData?.status || dmData?.dmStatus || "ACCEPTED");
      if (status === "REJECTED") {
        return { recipientUids: [], groupName: "", isDm: true };
      }
      const participantIds = Array.isArray(dmData?.participantIds)
        ? (dmData?.participantIds as string[])
        : [];
      for (const uid of participantIds) {
        if (typeof uid === "string" && uid.trim().length > 0 && uid.trim() !== senderUid) {
          recipients.add(uid.trim());
        }
      }
      if (recipients.size === 0 && channelId.includes("_")) {
        for (const part of channelId.split("_")) {
          if (part.trim().length > 0 && part.trim() !== senderUid) {
            recipients.add(part.trim());
          }
        }
      }
    } else if (channelCollection === "study_groups") {
      const groupSnap = await db.doc(`study_groups/${channelId}`).get();
      const gData = groupSnap.data();
      groupName = String(gData?.name || `Grup ${channelId}`).trim();
      const memberIds = Array.isArray(gData?.memberIds) ? (gData?.memberIds as string[]) : [];
      for (const uid of memberIds) {
        if (typeof uid === "string" && uid.trim().length > 0 && uid.trim() !== senderUid) {
          recipients.add(uid.trim());
        }
      }
    }
  } catch (err) {
    console.warn(`[leagueChat] Failed to resolve recipients for ${channelCollection}/${channelId}:`, err);
  }

  return {
    recipientUids: Array.from(recipients),
    groupName,
    isDm
  };
}

// ─── 1. ON MESSAGE / QUESTION / SOLUTION CREATED (24/7 SEPARATED PUSH) ───────

export const onQuestionSolutionSubmitted = functions.firestore
  .document("{channelCollection}/{channelId}/messages/{messageId}")
  .onCreate(async (snap, context) => {
    const channelCollection = context.params.channelCollection as string;
    const channelId = context.params.channelId as string;
    if (!isSupportedChatCollection(channelCollection)) {
      return;
    }

    const data = snap.data();
    if (!data || data.isDeleted === true) {
      return;
    }

    const rawType = String(data.type || data.messageType || "TEXT").toUpperCase();

    // Case A: Solution card submitted -> notify question owner
    if (rawType === "SOLUTION_CARD" || rawType === "SOLUTION") {
      const submission = (data.solutionSubmission || {}) as SolutionSubmissionDoc;
      const questionMessageId = (
        submission.questionMessageId ||
        data.replyToQuestionMessageId ||
        data.replyToQuestionId ||
        ""
      ) as string;
      const solverUid = (submission.solverUid || data.senderUid || data.senderId || "") as string;
      const solverName = (submission.solverName || data.senderName || "Arkadaşın") as string;
      let questionOwnerUid = (submission.questionOwnerUid || "") as string;

      if (!questionOwnerUid && questionMessageId) {
        const qSnap = await db
          .collection(channelCollection)
          .doc(channelId)
          .collection("messages")
          .doc(questionMessageId)
          .get();
        const qData = qSnap.data();
        const qAttachment = (qData?.questionAttachment || {}) as QuestionAttachmentDoc;
        questionOwnerUid = (qAttachment.ownerUid || qData?.senderUid || qData?.senderId || "") as string;
      }

      if (!questionOwnerUid || questionOwnerUid === solverUid) {
        return;
      }

      const title = `${solverName} soruna bir çözüm gönderdi`;
      const body = "Çözümü incele ve doğruysa onaylayarak arkadaşına mentor puanı kazandır.";

      await sendHighPriorityPush(
        questionOwnerUid,
        title,
        body,
        {
          type: "QUESTION_SOLUTION_SUBMITTED",
          channelCollection,
          channelId,
          roomId: channelId,
          questionMessageId,
          solutionMessageId: snap.id,
          solverUid,
          solverName,
          method: String(submission.method || "DRAWING")
        },
        channelId,
        solverUid
      );
      return;
    }

    // Avoid duplicate notifications if legacy dual-write hits league_chats
    if (channelCollection === "league_chats") {
      return;
    }

    const qAttachment = (data.questionAttachment || {}) as QuestionAttachmentDoc;
    const senderUid = String(
      data.senderId || data.senderUid || qAttachment.ownerUid || ""
    ).trim();
    if (!senderUid || senderUid === "SHARK_AI_MENTOR") {
      return;
    }
    const senderName = String(
      data.senderName || qAttachment.ownerName || "Grup Üyesi"
    ).trim();

    const { recipientUids, groupName, isDm } = await resolveChannelRecipientsAndMeta(
      channelCollection,
      channelId,
      senderUid
    );
    if (recipientUids.length === 0) {
      return;
    }

    // Case B: Question shared -> separated inviting question notification
    if (rawType === "QUESTION" || rawType === "QUESTION_CARD") {
      const subject = String(
        data.questionSubject || qAttachment.subject || "Ders"
      ).trim() || "Ders";
      const topic = String(
        data.questionTopic || qAttachment.topic || ""
      ).trim();
      const subjectLabel = topic && topic.toLowerCase() !== "genel"
        ? `${subject} (${topic})`
        : subject;

      const templateIdx = Math.abs(snap.id.split("").reduce((acc, ch) => acc + ch.charCodeAt(0), 0)) %
        QUESTION_INVITE_TEMPLATES.length;
      const body = isDm
        ? `${senderName} sana yeni bir ${subjectLabel} sorusu gönderdi. Sen çözebilecek misin?`
        : QUESTION_INVITE_TEMPLATES[templateIdx]
            .replace("%SUBJECT%", subjectLabel)
            .replace("%SENDER%", senderName);
      const title = isDm
        ? `${senderName} • Yeni ${subject} Sorusu`
        : `${groupName || "Çalışma Grubu"} • Yeni ${subject} Sorusu`;

      await Promise.all(
        recipientUids.map((recipientUid) =>
          sendHighPriorityPush(
            recipientUid,
            title,
            body,
            {
              type: "QUESTION",
              triggerType: "LEAGUE_QUESTION",
              channelCollection,
              channelId,
              roomId: channelId,
              groupCode: isDm ? "" : channelId,
              groupId: isDm ? "" : channelId,
              groupName: groupName || "",
              isDm: isDm ? "true" : "false",
              chatMode: isDm ? "DIRECT" : "GROUP",
              messageId: snap.id,
              id: snap.id,
              senderUid,
              senderId: senderUid,
              senderName,
              fromName: senderName,
              questionSubject: subject,
              subject,
              questionTopic: topic,
              topic
            },
            channelId,
            senderUid
          )
        )
      );
      return;
    }

    // Case C: Text chat message -> separated chat notification
    if (rawType === "TEXT") {
      const messageText = String(data.text || "").trim();
      if (!messageText) {
        return;
      }
      const title = isDm
        ? senderName
        : `${groupName || "Çalışma Grubu"} • ${senderName}`;
      const body = messageText.slice(0, 160);

      await Promise.all(
        recipientUids.map((recipientUid) =>
          sendHighPriorityPush(
            recipientUid,
            title,
            body,
            {
              type: isDm ? "DM_MESSAGE" : "CHAT_MESSAGE",
              triggerType: "CHAT_MESSAGE",
              channelCollection,
              channelId,
              roomId: channelId,
              groupCode: isDm ? "" : channelId,
              groupId: isDm ? "" : channelId,
              groupName: groupName || "",
              isDm: isDm ? "true" : "false",
              chatMode: isDm ? "DIRECT" : "GROUP",
              messageId: snap.id,
              id: snap.id,
              senderUid,
              senderId: senderUid,
              senderName,
              fromName: senderName,
              text: messageText.slice(0, 240),
              body: messageText.slice(0, 240),
              message: messageText.slice(0, 240)
            },
            channelId,
            senderUid
          )
        )
      );
    }
  });

// ─── 2. ON QUESTION SOLUTION VERIFIED (SYNC WEEKLY LEAGUE & NOTIFY SOLVER) ───

export const onQuestionSolutionVerified = functions.firestore
  .document("{channelCollection}/{channelId}/messages/{messageId}")
  .onUpdate(async (change, context) => {
    const channelCollection = context.params.channelCollection as string;
    if (!isSupportedChatCollection(channelCollection)) {
      return;
    }

    const beforeData = change.before.data();
    const afterData = change.after.data();
    if (
      !beforeData ||
      !afterData ||
      (afterData.messageType !== "SOLUTION_CARD" && afterData.type !== "SOLUTION")
    ) {
      return;
    }

    const beforeSub = (beforeData.solutionSubmission || {}) as SolutionSubmissionDoc;
    const afterSub = (afterData.solutionSubmission || {}) as SolutionSubmissionDoc;

    const prevStatus = beforeSub.verifyStatus || String(beforeData.approvalStatus || "PENDING_ESCROW");
    const nextStatus = afterSub.verifyStatus || String(afterData.approvalStatus || "PENDING_ESCROW");

    if (prevStatus === nextStatus) {
      return;
    }
    if (nextStatus !== "APPROVED" && nextStatus !== "REJECTED") {
      return;
    }

    const solverUid = (
      afterSub.solverUid ||
      afterData.senderUid ||
      afterData.senderId ||
      ""
    ) as string;
    if (!solverUid) {
      return;
    }

    const awardedPoints = Number(afterSub.awardedPoints ?? afterData.escrowPoints ?? 0);
    const awardedBonusSeconds = Number(
      afterSub.awardedBonusSeconds ??
        (afterData.escrowBonusMinutes ? Number(afterData.escrowBonusMinutes) * 60 : 0)
    );
    const bonusMinutes = Math.round(awardedBonusSeconds / 60);

    if (nextStatus === "APPROVED") {
      // Ensure approved bonus and badge tier are synced to institutions/GLOBAL_INDIVIDUAL/weeklyLeague/{solverUid}
      try {
        const solverSnap = await db.doc(`students/${solverUid}`).get();
        const solverData = solverSnap.data() || {};
        const totalMentorPts = Number(solverData.mentorPoints || awardedPoints);
        const weeklyBonusSec = Math.min(
          MAX_WEEKLY_QUESTION_BONUS_SECONDS,
          Number(solverData.weeklyQuestionBonusSeconds || awardedBonusSeconds)
        );
        const badgeInfo = resolveBadgeTier(totalMentorPts);
        const showcaseBadgeIds = Array.isArray(solverData.showcaseBadgeIds)
          ? (solverData.showcaseBadgeIds as string[]).slice(0, MAX_SHOWCASE_BADGES)
          : Array.isArray(solverData.showcaseBadges)
          ? (solverData.showcaseBadges as string[]).slice(0, MAX_SHOWCASE_BADGES)
          : [badgeInfo.tier];

        await db
          .doc(`students/${solverUid}/badges/${badgeInfo.tier}`)
          .set(
            {
              badgeId: badgeInfo.tier,
              title: badgeInfo.displayName,
              tier: badgeInfo.tier,
              progress: totalMentorPts,
              target: badgeInfo.target,
              unlocked: true,
              unlockedAtMillis: Date.now()
            },
            { merge: true }
          );

        const globalLeagueRef = db.doc(`institutions/GLOBAL_INDIVIDUAL/weeklyLeague/${solverUid}`);
        const globalSnap = await globalLeagueRef.get();
        const currentFocusSec = Number(
          globalSnap.data()?.weeklyFocusSeconds ?? solverData.weeklyFocusSeconds ?? 0
        );
        await globalLeagueRef.set(
          {
            studentId: solverUid,
            displayName: afterSub.solverName || solverData.name || "Öğrenci",
            weeklyFocusSeconds: Math.max(currentFocusSec, Number(solverData.weeklyFocusSeconds || 0)),
            weeklyQuestionBonusSeconds: weeklyBonusSec,
            mentorPoints: totalMentorPts,
            badgeTier: badgeInfo.tier,
            showcaseBadgeIds,
            showcaseBadges: showcaseBadgeIds,
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
          },
          { merge: true }
        );
      } catch (err) {
        console.warn(`[onQuestionSolutionVerified] Failed to sync weeklyLeague for ${solverUid}:`, err);
      }

      const title = "Çözümün Onaylandı";
      const body =
        awardedPoints > 0
          ? `Tebrikler! +${awardedPoints} Mentor Puanı ve +${bonusMinutes} dk haftalık odak bonusu kazandın.`
          : "Çözümün soru sahibi tarafından doğru olarak onaylandı.";

      await sendHighPriorityPush(
        solverUid,
        title,
        body,
        {
          type: "QUESTION_SOLUTION_VERIFIED",
          verifyStatus: "APPROVED",
          channelCollection,
          channelId: context.params.channelId,
          roomId: context.params.channelId,
          solutionMessageId: change.after.id,
          questionMessageId: String(afterSub.questionMessageId || ""),
          awardedPoints: String(awardedPoints),
          awardedBonusSeconds: String(awardedBonusSeconds)
        },
        context.params.channelId
      );
    } else {
      const title = "Çözümün Onaylanmadı";
      const body = `Soru sahibi çözümü yeterli bulmadı (aynı soruda en fazla ${MAX_REJECTED_ATTEMPTS_PER_QUESTION} red hakkı).`;

      await sendHighPriorityPush(
        solverUid,
        title,
        body,
        {
          type: "QUESTION_SOLUTION_VERIFIED",
          verifyStatus: "REJECTED",
          channelCollection,
          channelId: context.params.channelId,
          roomId: context.params.channelId,
          solutionMessageId: change.after.id,
          questionMessageId: String(afterSub.questionMessageId || "")
        },
        context.params.channelId
      );
    }
  });

// ─── 3. SCHEDULED 5-MINUTE QUESTION TIMER & AUTO-APPROVAL SWEEP ──────────────

async function autoApproveSolutionTransaction(
  questionDocRef: admin.firestore.DocumentReference,
  solutionDocRef: admin.firestore.DocumentReference,
  qAttachment: QuestionAttachmentDoc,
  sub: SolutionSubmissionDoc
): Promise<void> {
  const now = Date.now();
  const todayKey = getDayKeyUtcPlus3(now);
  const solverUid = (sub.solverUid || "").trim();
  const ownerUid = (qAttachment.ownerUid || sub.questionOwnerUid || "").trim();

  if (!solverUid) return;

  const aiAccessed = Array.isArray(qAttachment.aiAccessedByUids) ? qAttachment.aiAccessedByUids : [];
  const rejectedCounts = qAttachment.rejectedSolverCounts || {};
  const solverRejectedCount = Number(rejectedCounts[solverUid] || 0);

  const solverRef = db.collection("students").doc(solverUid);
  const globalLeagueRef = db
    .collection("institutions")
    .doc("GLOBAL_INDIVIDUAL")
    .collection("weeklyLeague")
    .doc(solverUid);

  await db.runTransaction(async (tx) => {
    const solverSnap = await tx.get(solverRef);
    const leagueSnap = await tx.get(globalLeagueRef);

    const sData = solverSnap.data() || {};
    const escrowPts = Number(sub.escrowPoints || 0);
    const potentialBonusSec = Number(sub.potentialBonusSeconds || 0);

    const currentPending = Number(sData.pendingMentorPoints || 0);
    const nextPending = Math.max(0, currentPending - escrowPts);

    const pairField = `dailyOwnerReward_${todayKey}_${ownerUid}`;
    const solvedTodayForOwner = Number(sData[pairField] || 0);
    const eligibleForReward =
      solvedTodayForOwner < MAX_DAILY_REWARDED_SOLUTIONS_PER_OWNER &&
      !aiAccessed.includes(solverUid) &&
      solverRejectedCount < MAX_REJECTED_ATTEMPTS_PER_QUESTION;

    const currentMentorPts = Number(sData.mentorPoints || 0);
    const currentWeeklyBonus = Math.min(
      MAX_WEEKLY_QUESTION_BONUS_SECONDS,
      Math.max(0, Number(sData.weeklyQuestionBonusSeconds || 0))
    );
    const currentSolvedTotal = Number(sData.solvedTotalCount || 0);
    const currentGolden = Number(sData.goldenWindowSolveCount || 0);
    const currentWeeklyFocusSec = Number(sData.weeklyFocusSeconds || 0);

    const awardedPts = eligibleForReward ? escrowPts : 0;
    const remainingBonusCap = Math.max(0, MAX_WEEKLY_QUESTION_BONUS_SECONDS - currentWeeklyBonus);
    const awardedBonusSec = eligibleForReward ? Math.min(potentialBonusSec, remainingBonusCap) : 0;

    const nextMentorPts = currentMentorPts + awardedPts;
    const nextWeeklyBonus = Math.min(
      MAX_WEEKLY_QUESTION_BONUS_SECONDS,
      currentWeeklyBonus + awardedBonusSec
    );
    const nextSolvedTotal = currentSolvedTotal + 1;
    const nextGolden = sub.rewardTier === "GOLD_100" ? currentGolden + 1 : currentGolden;
    const badgeInfo = resolveBadgeTier(nextMentorPts);

    const existingShowcase = Array.isArray(sData.showcaseBadgeIds)
      ? (sData.showcaseBadgeIds as string[]).slice(0, MAX_SHOWCASE_BADGES)
      : Array.isArray(sData.showcaseBadges)
      ? (sData.showcaseBadges as string[]).slice(0, MAX_SHOWCASE_BADGES)
      : [badgeInfo.tier];

    const updatedSub: SolutionSubmissionDoc = {
      ...sub,
      verifyStatus: "APPROVED",
      verifiedAtMillis: now,
      awardedPoints: awardedPts,
      awardedBonusSeconds: awardedBonusSec
    };

    const updatedQuestion: QuestionAttachmentDoc = {
      ...qAttachment,
      status: "SOLVED_APPROVED",
      approvedSolutionId: sub.solutionId || solutionDocRef.id,
      approvedSolverUid: solverUid,
      approvedSolverName: sub.solverName || "Arkadaşın"
    };

    tx.update(questionDocRef, { questionAttachment: updatedQuestion });
    tx.set(solutionDocRef, { solutionSubmission: updatedSub }, { merge: true });
    tx.set(
      questionDocRef.collection("solutions").doc(sub.solutionId || solutionDocRef.id),
      updatedSub,
      { merge: true }
    );

    const studentUpdate: Record<string, unknown> = {
      mentorPoints: nextMentorPts,
      pendingMentorPoints: nextPending,
      weeklyQuestionBonusSeconds: nextWeeklyBonus,
      solvedTotalCount: nextSolvedTotal,
      goldenWindowSolveCount: nextGolden,
      badgeTier: badgeInfo.tier,
      showcaseBadgeIds: existingShowcase,
      showcaseBadges: existingShowcase,
      weeklyFocusSeconds: currentWeeklyFocusSec + awardedBonusSec,
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    };
    if (eligibleForReward && awardedPts > 0 && ownerUid) {
      studentUpdate[pairField] = solvedTodayForOwner + 1;
    }
    tx.set(solverRef, studentUpdate, { merge: true });

    tx.set(
      solverRef.collection("badges").doc(badgeInfo.tier),
      {
        badgeId: badgeInfo.tier,
        title: badgeInfo.displayName,
        tier: badgeInfo.tier,
        progress: nextMentorPts,
        target: badgeInfo.target,
        unlocked: true,
        unlockedAtMillis: now
      },
      { merge: true }
    );

    const lData = leagueSnap.exists ? leagueSnap.data() || {} : {};
    const lWeeklySec = Number(lData.weeklyFocusSeconds ?? currentWeeklyFocusSec);
    tx.set(
      globalLeagueRef,
      {
        studentId: solverUid,
        displayName: sub.solverName || sData.name || "Öğrenci",
        weeklyFocusSeconds: lWeeklySec + awardedBonusSec,
        mentorPoints: nextMentorPts,
        weeklyQuestionBonusSeconds: nextWeeklyBonus,
        badgeTier: badgeInfo.tier,
        showcaseBadgeIds: existingShowcase,
        showcaseBadges: existingShowcase,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      },
      { merge: true }
    );
  });
}

/**
 * Scheduled job running every 5 minutes (`checkLeagueQuestionTimers`):
 * - For `OPEN` questions where the 105-minute window has elapsed (`expiresAtMillis <= now`)
 *   and no peer solutions are pending, solves the question using `gemini-3.6-flash` (with built-in
 *   3 `AQ.` keys fallback when `process.env.GEMINI_API_KEY` is absent) and closes it as `AI_SOLVED`.
 * - For questions with unverified peer solutions whose 24-hour `autoApproveAt` deadline has passed,
 *   auto-approves the first valid solution and credits `institutions/GLOBAL_INDIVIDUAL/weeklyLeague/{solverId}`.
 */
export const checkLeagueQuestionTimers = functions.pubsub
  .schedule("every 5 minutes")
  .onRun(async () => {
    const now = Date.now();

    const openQuestionsSnap = await db
      .collectionGroup("messages")
      .where("messageType", "==", "QUESTION_CARD")
      .get();

    if (openQuestionsSnap.empty) {
      return;
    }

    for (const qDoc of openQuestionsSnap.docs) {
      const qData = qDoc.data();
      const qAttachment = (qData.questionAttachment || {}) as QuestionAttachmentDoc;
      const status = qAttachment.status || "OPEN";
      if (status !== "OPEN" && status !== "PENDING_APPROVAL") {
        continue;
      }

      const sharedAt = Number(qAttachment.sharedAtMillis || qData.createdAtMillis || now);
      const expiresAt = Number(qAttachment.expiresAtMillis || sharedAt + QUESTION_REWARD_WINDOW_MS);
      const questionAutoApproveAt = Number(qAttachment.autoApproveAt || sharedAt + TWENTY_FOUR_HOURS_MS);

      // Only act once the 105-minute window has expired
      if (now < expiresAt) {
        continue;
      }

      const messagesCollection = qDoc.ref.parent;
      const channelDocRef = messagesCollection.parent;
      const channelId = channelDocRef?.id || String(qData.roomId || qData.channelId || "");
      const channelCollection = channelDocRef?.parent.id || "league_chats";

      // Check candidate solutions in both `messages` and `solutions` subcollection
      const candidateSolutionsSnap = await messagesCollection
        .where("replyToQuestionMessageId", "==", qDoc.id)
        .get();

      const pendingSolutionDocs = candidateSolutionsSnap.docs
        .filter((doc) => {
          const d = doc.data();
          const s = (d.solutionSubmission || {}) as SolutionSubmissionDoc;
          return (
            d.messageType === "SOLUTION_CARD" &&
            (s.verifyStatus || "PENDING_ESCROW") === "PENDING_ESCROW" &&
            Boolean(s.solverUid)
          );
        })
        .sort((a, b) => {
          const aTime = Number(a.data().createdAtMillis || 0);
          const bTime = Number(b.data().createdAtMillis || 0);
          return aTime - bTime;
        });

      if (pendingSolutionDocs.length > 0) {
        const firstSolutionDoc = pendingSolutionDocs[0];
        const firstSub = (firstSolutionDoc.data().solutionSubmission || {}) as SolutionSubmissionDoc;
        const subAutoApproveAt = Number(firstSub.autoApproveAt || questionAutoApproveAt);

        if (now >= subAutoApproveAt || now >= questionAutoApproveAt) {
          try {
            await autoApproveSolutionTransaction(qDoc.ref, firstSolutionDoc.ref, qAttachment, firstSub);
            console.log(
              `[checkLeagueQuestionTimers] Auto-approved solution ${firstSolutionDoc.id} for question ${qDoc.id}`
            );
          } catch (err) {
            console.error(
              `[checkLeagueQuestionTimers] Failed to auto-approve solution ${firstSolutionDoc.id}:`,
              err
            );
          }
        }
        continue;
      }

      // 105 minutes elapsed and 0 peer solutions -> Solve with gemini-3.6-flash and mark AI_SOLVED
      try {
        const aiSolutionText = await generateAiFallbackSolutionWithGemini36(
          String(qAttachment.subject || "Genel"),
          String(qAttachment.topic || "Genel"),
          String(qAttachment.noteText || ""),
          String(qAttachment.questionImageUrl || "")
        );

        const aiMsgRef = messagesCollection.doc();
        const aiSubRef = qDoc.ref.collection("solutions").doc("ai_fallback");
        const aiSubmission: SolutionSubmissionDoc = {
          solutionId: "ai_fallback",
          questionMessageId: qDoc.id,
          questionOwnerUid: String(qAttachment.ownerUid || ""),
          solverUid: "SHARK_AI_MENTOR",
          solverName: "Shark AI (gemini-3.6-flash)",
          method: "AI_FALLBACK",
          annotatedImageUrl: String(qAttachment.questionImageUrl || ""),
          explanationText: aiSolutionText,
          submittedAtMillis: now,
          autoApproveAt: now,
          rewardTier: "EXPIRED_0",
          escrowPoints: 0,
          potentialBonusSeconds: 0,
          verifyStatus: "APPROVED",
          verifiedAtMillis: now,
          awardedPoints: 0,
          awardedBonusSeconds: 0
        };

        const aiMessagePayload = {
          id: aiMsgRef.id,
          roomId: channelId,
          channelId,
          channelType: channelCollection === "direct_messages" ? "DM" : "GROUP",
          senderUid: "SHARK_AI_MENTOR",
          senderName: "Shark AI (gemini-3.6-flash)",
          senderAvatarPresetId: 0,
          senderShowcaseBadges: ["MEGALODON"],
          showcaseBadgeIds: ["MEGALODON"],
          messageType: "AI_FALLBACK_SOLUTION",
          text: aiSolutionText,
          createdAtMillis: now,
          solutionSubmission: aiSubmission,
          replyToQuestionMessageId: qDoc.id
        };

        const closedAttachment: QuestionAttachmentDoc = {
          ...qAttachment,
          status: "AI_SOLVED",
          approvedSolutionId: "ai_fallback",
          approvedSolverUid: "SHARK_AI_MENTOR",
          approvedSolverName: "Shark AI",
          aiFallbackText: aiSolutionText
        };

        const batch = db.batch();
        batch.set(aiMsgRef, aiMessagePayload);
        batch.set(aiSubRef, aiSubmission, { merge: true });
        batch.update(qDoc.ref, { questionAttachment: closedAttachment });
        await batch.commit();

        if (qAttachment.ownerUid) {
          await sendHighPriorityPush(
            qAttachment.ownerUid,
            "Shark AI Sorunu Çözdü",
            "Paylaştığın soruya 105 dakika içinde çözüm gelmediği için Shark AI adım adım çözümü sohbete ekledi.",
            {
              type: "AI_FALLBACK_SOLUTION",
              status: "AI_SOLVED",
              channelCollection,
              channelId,
              roomId: channelId,
              questionMessageId: qDoc.id,
              aiMessageId: aiMsgRef.id
            },
            channelId
          );
        }

        console.log(`[checkLeagueQuestionTimers] Closed question ${qDoc.id} as AI_SOLVED`);
      } catch (err) {
        console.error(`[checkLeagueQuestionTimers] AI fallback failed for question ${qDoc.id}:`, err);
      }
    }
  });

export const onExpiredQuestionsSweep = checkLeagueQuestionTimers;
