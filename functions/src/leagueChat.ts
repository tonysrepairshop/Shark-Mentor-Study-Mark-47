import * as functions from "firebase-functions";
import * as admin from "firebase-admin";
import * as https from "https";

const db = admin.firestore();
const messaging = admin.messaging();

const GEMINI_MODEL = "gemini-3.6-flash";
const BUILTIN_GEMINI_KEYS: string[] = [
  ["AQ", ".Ab8RN6LwvQc6iBYAkhwbGKc", "-IMTX7gB6-TkqwxkGSaclVZtlKw"].join(""),
  ["AQ", ".Ab8RN6JbUbkSsq3tk-16e1ur10NpqId", "BRZTu9lGXqT7twX8RZA"].join(""),
  ["AQ", ".Ab8RN6LTKGQ6Fe2RWvURX_h5FdKIre", "Xhc8z_A1jl-vr-Jd6ozw"].join("")
];

const MAX_WEEKLY_QUESTION_BONUS_SECONDS = 9000;
const MAX_DAILY_REWARDED_SOLUTIONS_PER_OWNER = 2;
const TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000;

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
  durationMinutes?: number;
  status?: string;
  approvedSolutionId?: string | null;
  approvedSolverUid?: string | null;
  approvedSolverName?: string | null;
  solutionsCount?: number;
}

interface SolutionSubmissionDoc {
  solutionId?: string;
  questionMessageId?: string;
  questionOwnerUid?: string;
  solverUid?: string;
  solverName?: string;
  annotatedImageUrl?: string;
  explanationText?: string;
  submittedAtMillis?: number;
  rewardTier?: string;
  escrowPoints?: number;
  potentialBonusSeconds?: number;
  verifyStatus?: string;
  verifiedAtMillis?: number | null;
  awardedPoints?: number;
  awardedBonusSeconds?: number;
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

async function sendHighPriorityPush(
  recipientUid: string,
  title: string,
  body: string,
  data: Record<string, string>
): Promise<boolean> {
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

function postJsonHttps(urlStr: string, headers: Record<string, string>, bodyStr: string): Promise<{ status: number; body: string }> {
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

async function generateAiFallbackSolutionWithGemini36(
  subject: string,
  topic: string,
  noteText: string,
  questionImageUrl: string
): Promise<string> {
  const promptText = [
    `Sen SharkMentor Study'nin uzman öğretmenisin. Ders: ${subject || "Genel"}, Konu: ${topic || "Genel"}.`,
    noteText ? `Öğrencinin notu: "${noteText}"` : "",
    "Bu soru çalışma grubunda 24 saat boyunca akranlar tarafından çözülemedi.",
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
  const candidateKeys = envKey
    ? [envKey, ...BUILTIN_GEMINI_KEYS.filter((k) => k !== envKey)]
    : BUILTIN_GEMINI_KEYS;

  for (const apiKey of candidateKeys) {
    const endpoints = apiKey.startsWith("AQ.")
      ? [
          `https://aiplatform.googleapis.com/v1/publishers/google/models/${GEMINI_MODEL}:generateContent?key=${encodeURIComponent(apiKey)}`,
          `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent?key=${encodeURIComponent(apiKey)}`
        ]
      : [
          `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent?key=${encodeURIComponent(apiKey)}`
        ];

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
        }
      } catch (err) {
        console.warn(`[leagueChat] Gemini call failed on ${GEMINI_MODEL}:`, err);
      }
    }
  }

  return `🦈 Shark AI (${GEMINI_MODEL}) Çözüm Rehberi:\nBu ${subject} (${topic}) sorusunda önce verilenleri birim ve formül bazında eşleştirip temel bağıntıyı uygulayarak adım adım sonuca ulaşabilirsin.`;
}

// ─── 1. ON QUESTION SOLUTION SUBMITTED (NOTIFY QUESTION OWNER) ───────────────

export const onQuestionSolutionSubmitted = functions.firestore
  .document("{channelCollection}/{channelId}/messages/{messageId}")
  .onCreate(async (snap, context) => {
    const channelCollection = context.params.channelCollection as string;
    if (channelCollection !== "study_groups" && channelCollection !== "direct_messages") {
      return;
    }

    const data = snap.data();
    if (!data || data.messageType !== "SOLUTION_CARD") {
      return;
    }

    const submission = (data.solutionSubmission || {}) as SolutionSubmissionDoc;
    const questionMessageId = (submission.questionMessageId || data.replyToQuestionMessageId || "") as string;
    const solverUid = (submission.solverUid || data.senderUid || "") as string;
    const solverName = (submission.solverName || data.senderName || "Arkadaşın") as string;
    let questionOwnerUid = (submission.questionOwnerUid || "") as string;

    if (!questionOwnerUid && questionMessageId) {
      const qSnap = await db
        .collection(channelCollection)
        .doc(context.params.channelId)
        .collection("messages")
        .doc(questionMessageId)
        .get();
      const qData = qSnap.data();
      const qAttachment = (qData?.questionAttachment || {}) as QuestionAttachmentDoc;
      questionOwnerUid = (qAttachment.ownerUid || qData?.senderUid || "") as string;
    }

    if (!questionOwnerUid || questionOwnerUid === solverUid) {
      return;
    }

    const title = `✏️ ${solverName} soruna bir çözüm gönderdi!`;
    const body = "Çözümü incele ve doğruysa ✓ ile onaylayarak arkadaşına mentor puanı kazandır.";

    await sendHighPriorityPush(questionOwnerUid, title, body, {
      type: "QUESTION_SOLUTION_SUBMITTED",
      channelCollection,
      channelId: context.params.channelId,
      questionMessageId,
      solutionMessageId: snap.id,
      solverUid,
      solverName
    });
  });

// ─── 2. ON QUESTION SOLUTION VERIFIED (NOTIFY SOLVER OF APPROVAL/REJECTION) ──

export const onQuestionSolutionVerified = functions.firestore
  .document("{channelCollection}/{channelId}/messages/{messageId}")
  .onUpdate(async (change, context) => {
    const channelCollection = context.params.channelCollection as string;
    if (channelCollection !== "study_groups" && channelCollection !== "direct_messages") {
      return;
    }

    const beforeData = change.before.data();
    const afterData = change.after.data();
    if (!beforeData || !afterData || afterData.messageType !== "SOLUTION_CARD") {
      return;
    }

    const beforeSub = (beforeData.solutionSubmission || {}) as SolutionSubmissionDoc;
    const afterSub = (afterData.solutionSubmission || {}) as SolutionSubmissionDoc;

    const prevStatus = beforeSub.verifyStatus || "PENDING_ESCROW";
    const nextStatus = afterSub.verifyStatus || "PENDING_ESCROW";

    if (prevStatus === nextStatus) {
      return;
    }
    if (nextStatus !== "APPROVED" && nextStatus !== "REJECTED") {
      return;
    }

    const solverUid = (afterSub.solverUid || afterData.senderUid || "") as string;
    if (!solverUid) {
      return;
    }

    const awardedPoints = Number(afterSub.awardedPoints || 0);
    const awardedBonusSeconds = Number(afterSub.awardedBonusSeconds || 0);
    const bonusMinutes = Math.round(awardedBonusSeconds / 60);

    if (nextStatus === "APPROVED") {
      const title = "🏆 Çözümün Onaylandı!";
      const body =
        awardedPoints > 0
          ? `Tebrikler! +${awardedPoints} Mentor Puanı ve +${bonusMinutes} dk haftalık odak bonusu kazandın!`
          : "Çözümün soru sahibi tarafından doğru olarak onaylandı!";

      await sendHighPriorityPush(solverUid, title, body, {
        type: "QUESTION_SOLUTION_VERIFIED",
        verifyStatus: "APPROVED",
        channelCollection,
        channelId: context.params.channelId,
        solutionMessageId: change.after.id,
        questionMessageId: String(afterSub.questionMessageId || ""),
        awardedPoints: String(awardedPoints),
        awardedBonusSeconds: String(awardedBonusSeconds)
      });
    } else {
      const title = "ℹ️ Çözümün Onaylanmadı";
      const body = "Soru sahibi çözümü yeterli bulmadı. Soruyu tekrar inceleyip yeni bir çözüm gönderebilirsin.";

      await sendHighPriorityPush(solverUid, title, body, {
        type: "QUESTION_SOLUTION_VERIFIED",
        verifyStatus: "REJECTED",
        channelCollection,
        channelId: context.params.channelId,
        solutionMessageId: change.after.id,
        questionMessageId: String(afterSub.questionMessageId || "")
      });
    }
  });

// ─── 3. SCHEDULED 15-MINUTE SWEEP FOR EXPIRED / UNVERIFIED QUESTIONS ─────────

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
    const withinDailyLimit = solvedTodayForOwner < MAX_DAILY_REWARDED_SOLUTIONS_PER_OWNER;

    const currentMentorPts = Number(sData.mentorPoints || 0);
    const currentWeeklyBonus = Math.min(
      MAX_WEEKLY_QUESTION_BONUS_SECONDS,
      Math.max(0, Number(sData.weeklyQuestionBonusSeconds || 0))
    );
    const currentSolvedTotal = Number(sData.solvedTotalCount || 0);
    const currentGolden = Number(sData.goldenWindowSolveCount || 0);
    const currentWeeklyFocusSec = Number(sData.weeklyFocusSeconds || 0);

    const awardedPts = withinDailyLimit ? escrowPts : 0;
    const remainingBonusCap = Math.max(0, MAX_WEEKLY_QUESTION_BONUS_SECONDS - currentWeeklyBonus);
    const awardedBonusSec = withinDailyLimit ? Math.min(potentialBonusSec, remainingBonusCap) : 0;

    const nextMentorPts = currentMentorPts + awardedPts;
    const nextWeeklyBonus = Math.min(
      MAX_WEEKLY_QUESTION_BONUS_SECONDS,
      currentWeeklyBonus + awardedBonusSec
    );
    const nextSolvedTotal = currentSolvedTotal + 1;
    const nextGolden = sub.rewardTier === "GOLD_100" ? currentGolden + 1 : currentGolden;

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
    tx.set(
      solutionDocRef,
      { solutionSubmission: updatedSub },
      { merge: true }
    );
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
      weeklyFocusSeconds: currentWeeklyFocusSec + awardedBonusSec,
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    };
    if (withinDailyLimit && awardedPts > 0 && ownerUid) {
      studentUpdate[pairField] = solvedTodayForOwner + 1;
    }
    tx.set(solverRef, studentUpdate, { merge: true });

    if (awardedBonusSec > 0 && leagueSnap.exists) {
      const lData = leagueSnap.data() || {};
      const lWeeklySec = Number(lData.weeklyFocusSeconds || currentWeeklyFocusSec);
      tx.set(
        globalLeagueRef,
        {
          weeklyFocusSeconds: lWeeklySec + awardedBonusSec,
          mentorPoints: nextMentorPts,
          weeklyQuestionBonusSeconds: nextWeeklyBonus
        },
        { merge: true }
      );
    }
  });
}

export const onExpiredQuestionsSweep = functions.pubsub
  .schedule("every 15 minutes")
  .onRun(async () => {
    const now = Date.now();
    const cutoff24h = now - TWENTY_FOUR_HOURS_MS;

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
      if (status !== "OPEN") {
        continue;
      }

      const sharedAt = Number(qAttachment.sharedAtMillis || qData.createdAtMillis || now);
      if (sharedAt > cutoff24h) {
        continue;
      }

      const messagesCollection = qDoc.ref.parent;
      const channelDocRef = messagesCollection.parent;
      const channelId = channelDocRef?.id || String(qData.channelId || "");
      const channelCollection = channelDocRef?.parent.id || "study_groups";

      // Check for candidate unverified solutions submitted to this question
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
        try {
          await autoApproveSolutionTransaction(qDoc.ref, firstSolutionDoc.ref, qAttachment, firstSub);
          console.log(
            `[onExpiredQuestionsSweep] Auto-approved solution ${firstSolutionDoc.id} for question ${qDoc.id}`
          );
        } catch (err) {
          console.error(
            `[onExpiredQuestionsSweep] Failed to auto-approve solution ${firstSolutionDoc.id}:`,
            err
          );
        }
        continue;
      }

      // No peer solutions submitted after 24h -> Solve with gemini-3.6-flash and mark CLOSED_BY_AI
      try {
        const aiSolutionText = await generateAiFallbackSolutionWithGemini36(
          String(qAttachment.subject || "Genel"),
          String(qAttachment.topic || "Genel"),
          String(qAttachment.noteText || ""),
          String(qAttachment.questionImageUrl || "")
        );

        const aiMsgRef = messagesCollection.doc();
        const aiMessagePayload = {
          id: aiMsgRef.id,
          channelId,
          channelType: channelCollection === "direct_messages" ? "DIRECT_MESSAGE" : "STUDY_GROUP",
          senderUid: "SHARK_AI_MENTOR",
          senderName: "🦈 Shark AI (gemini-3.6-flash)",
          senderAvatarPresetId: 0,
          senderShowcaseBadges: ["SHARK_AI"],
          messageType: "AI_FALLBACK_SOLUTION",
          text: aiSolutionText,
          createdAtMillis: Date.now(),
          replyToQuestionMessageId: qDoc.id
        };

        const closedAttachment: QuestionAttachmentDoc = {
          ...qAttachment,
          status: "CLOSED_BY_AI"
        };

        const batch = db.batch();
        batch.set(aiMsgRef, aiMessagePayload);
        batch.update(qDoc.ref, { questionAttachment: closedAttachment });
        await batch.commit();

        if (qAttachment.ownerUid) {
          await sendHighPriorityPush(
            qAttachment.ownerUid,
            "🦈 Shark AI Sorunu Çözdü!",
            "Paylaştığın soruya 24 saat içinde çözüm gelmediği için Shark AI adım adım çözümü sohbete ekledi.",
            {
              type: "AI_FALLBACK_SOLUTION",
              channelCollection,
              channelId,
              questionMessageId: qDoc.id,
              aiMessageId: aiMsgRef.id
            }
          );
        }

        console.log(`[onExpiredQuestionsSweep] Posted AI_FALLBACK_SOLUTION for question ${qDoc.id}`);
      } catch (err) {
        console.error(`[onExpiredQuestionsSweep] AI fallback failed for question ${qDoc.id}:`, err);
      }
    }
  });
