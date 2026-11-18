import * as functions from "firebase-functions";
import * as admin from "firebase-admin";

const db = admin.firestore();
const messaging = admin.messaging();

interface NotificationState {
  lastNotificationTimestamp: admin.firestore.Timestamp;
  lastTriggerType: '48H_INACTIVITY' | 'STREAK_3DAY' | 'EXAM_UPLOADED' | 'NUDGE' | 'MANUAL';
  streakDaysCount: number;
}

const NUDGE_PAIR_COOLDOWN_MINUTES = 15;

// ─── ANTI-SPAM & QUIET HOURS ─────────────────────────────────────────────

/**
 * Checks if the current time in UTC+3 is within quiet hours (23:00 - 08:30).
 * If so, returns true. Otherwise false.
 */
function isQuietHours(): boolean {
  const now = new Date();
  // Convert current time to UTC+3
  const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
  const utcPlus3 = new Date(utc + (3600000 * 3));
  
  const hour = utcPlus3.getHours();
  const minute = utcPlus3.getMinutes();
  
  // 23:00 to 23:59 or 00:00 to 08:30
  if (hour >= 23 || hour < 8) return true;
  if (hour === 8 && minute < 30) return true;
  
  return false;
}

/**
 * Checks if a notification can be sent based on rate limit (default 24-hour, or custom cooldownHours).
 */
async function canSendNotification(studentId: string, cooldownHours: number = 24): Promise<boolean> {
  const metaRef = db.doc(`students/${studentId}/meta/notificationState`);
  const metaSnap = await metaRef.get();
  
  if (!metaSnap.exists) return true;
  
  const state = metaSnap.data() as NotificationState;
  if (!state.lastNotificationTimestamp) return true;
  
  const lastTime = state.lastNotificationTimestamp.toDate().getTime();
  const now = Date.now();
  const hoursSinceLast = (now - lastTime) / (1000 * 60 * 60);
  
  return hoursSinceLast >= cooldownHours;
}

/**
 * Resolves all unique FCM tokens for a student (supports both `fcmTokens` array and single `fcmToken`).
 */
async function resolveStudentFcmTokens(studentId: string): Promise<string[]> {
  const studentSnap = await db.doc(`students/${studentId}`).get();
  const data = studentSnap.data();
  const tokenSet = new Set<string>();

  if (data) {
    const arr = data.fcmTokens as string[] | undefined;
    if (Array.isArray(arr)) {
      arr.filter((t) => typeof t === "string" && t.trim().length > 0).forEach((t) => tokenSet.add(t.trim()));
    }
    const single = data.fcmToken as string | undefined;
    if (typeof single === "string" && single.trim().length > 0) {
      tokenSet.add(single.trim());
    }
  }
  return Array.from(tokenSet);
}

/**
 * Centralized dispatcher that enforces anti-spam and quiet hours.
 * Note: If quiet hours are active, this implementation currently DROPS the 
 * notification to enforce the strict requirement. A robust system could push 
 * to a queue collection to be processed at 09:00.
 */
async function dispatchPushNotification(
  studentId: string, 
  title: string, 
  body: string,
  triggerType: NotificationState['lastTriggerType'],
  cooldownHours: number = 24,
  extraData: Record<string, string> = {}
): Promise<boolean> {
  if (isQuietHours()) {
    console.log(`[Quiet Hours] Suppressed notification for ${studentId}: ${title}`);
    return false;
  }
  
  if (!(await canSendNotification(studentId, cooldownHours))) {
    console.log(`[Rate Limit] Suppressed notification for ${studentId}: ${title}`);
    return false;
  }
  
  const fcmTokens = await resolveStudentFcmTokens(studentId);
  
  if (fcmTokens && fcmTokens.length > 0) {
    try {
      await messaging.sendEachForMulticast({
        tokens: fcmTokens,
        notification: { title, body },
        data: { triggerType, ...extraData }
      });
      
      // Update state
      await db.doc(`students/${studentId}/meta/notificationState`).set({
        lastNotificationTimestamp: admin.firestore.FieldValue.serverTimestamp(),
        lastTriggerType: triggerType
      }, { merge: true });
      
      console.log(`Successfully dispatched ${triggerType} to ${studentId}`);
      return true;
    } catch (err) {
      console.error(`Failed to send FCM to ${studentId}`, err);
      return false;
    }
  } else {
    console.log(`No FCM tokens found for ${studentId}`);
    return false;
  }
}

// ─── 48-HOUR INACTIVITY RULE (CRON) ──────────────────────────────────────────

/**
 * Scheduled job running every 2 hours.
 * Query students with active timers where lastSessionAt < now - 48 hours.
 */
export const check48HourInactivity = functions.pubsub
  .schedule('every 2 hours')
  .onRun(async (context) => {
    // 48 hours ago
    const thresholdDate = new Date(Date.now() - (48 * 60 * 60 * 1000));
    
    // We assume students have a lastSessionAt field to optimize query.
    const inactiveStudents = await db.collection('students')
      .where('lastSessionAt', '<', thresholdDate)
      .get();
      
    if (inactiveStudents.empty) return;
    
    const batchPromises = inactiveStudents.docs.map(doc => 
      dispatchPushNotification(
        doc.id,
        "🦈 Masan Seni Bekliyor",
        "Hedefinden uzaklaşma. Masaya geç, kronometreni başlat ve ritmini koru.",
        "48H_INACTIVITY"
      )
    );
    
    await Promise.all(batchPromises);
  });

// ─── 3-DAY STREAK CELEBRATION RULE ─────────────────────────────────────────

/**
 * Triggered on studySessions collection creation.
 * Checks if the student logged >= 4 hours/day for 3 consecutive days.
 * 
 * Assumes document path: students/{studentId}/focus_sessions/{sessionId}
 */
export const onStudySessionCreated = functions.firestore
  .document('students/{studentId}/focus_sessions/{sessionId}')
  .onCreate(async (snap, context) => {
    const studentId = context.params.studentId;
    
    // Quick check to see if this session brings the daily total over 4 hours (14400 seconds)
    // To properly check 3 consecutive days, we would query the last 3 days of sessions.
    // For performance, we check the streakDaysCount in meta state, and update it.
    
    // Simplified logic: We fetch all sessions for the last 3 days and calculate daily totals.
    const now = new Date();
    const threeDaysAgo = new Date(now.getTime() - (3 * 24 * 60 * 60 * 1000));
    
    const recentSessionsSnap = await db.collection(`students/${studentId}/focus_sessions`)
      .where('createdAt', '>=', admin.firestore.Timestamp.fromDate(threeDaysAgo))
      .get();
      
    const dailyTotals: Record<string, number> = {};
    recentSessionsSnap.forEach(doc => {
      const data = doc.data();
      if (!data.createdAt) return;
      const dateStr = data.createdAt.toDate().toISOString().split('T')[0];
      dailyTotals[dateStr] = (dailyTotals[dateStr] || 0) + (data.durationSeconds || 0);
    });
    
    // Count how many days in the last 3 days have >= 4 hours (14400 seconds)
    let consecutiveDays = 0;
    for (let i = 0; i < 3; i++) {
      const d = new Date(now.getTime() - (i * 24 * 60 * 60 * 1000));
      const dateStr = d.toISOString().split('T')[0];
      if (dailyTotals[dateStr] >= 14400) {
        consecutiveDays++;
      }
    }
    
    if (consecutiveDays >= 3) {
      await dispatchPushNotification(
        studentId,
        "🔥 Odak Canavarı Devrede!",
        "3 gün üst üste 4 saatin üzerinde odaklandın. Ritmi yakaladın, aynen devam et.",
        "STREAK_3DAY"
      );
    }
  });

// ─── EXAM FEEDBACK EVENT RULE ──────────────────────────────────────────────

/**
 * Triggered on exams/{examId} write operation.
 * Assumes path: students/{studentId}/exams/{examId}
 */
export const onExamUpdated = functions.firestore
  .document('students/{studentId}/exams/{examId}')
  .onWrite(async (change, context) => {
    const studentId = context.params.studentId;
    
    // If it's a delete, do nothing
    if (!change.after.exists) return;
    
    // Only notify if coach feedback/note was updated or the exam is freshly uploaded
    const beforeData = change.before.data();
    const afterData = change.after.data();
    
    // Simple rule: if coach notes changed, or it's a new exam
    const isNew = !change.before.exists;
    const coachNotesChanged = beforeData?.coachNotes !== afterData?.coachNotes;
    
    if (isNew || coachNotesChanged) {
      await dispatchPushNotification(
        studentId,
        "📊 Yeni Deneme Sonucu",
        "Koçun son TYT/AYT denemeni sisteme işledi. Analizleri incele.",
        "EXAM_UPLOADED"
      );
    }
  });

// ─── SHARK LEAGUE NUDGE ("DÜRTME" / ÇALIŞMAYA DAVET ET) PUSH NOTIFICATION RULE ─

/**
 * Triggered when a student nudges a friend or study group member via `nudges/{nudgeId}`.
 * Dispatches IMMEDIATELY as a high-priority FCM notification (bypassing global rate limit and quiet hours)
 * with only a 15-minute per-pair (`fromUid -> toUid`) anti-spam cooldown.
 */
export const onNudgeCreated = functions.firestore
  .document('nudges/{nudgeId}')
  .onCreate(async (snap, context) => {
    const data = snap.data();
    if (!data) return;

    const fromUid = ((data.fromUid || data.senderId) as string | undefined)?.trim() || "";
    const toUid = ((data.toUid || data.receiverId) as string | undefined)?.trim() || "";
    const fromName = ((data.fromName || data.senderName) as string | undefined)?.trim() || "Arkadaşın";
    const groupId = (data.groupId as string | undefined)?.trim() || "GLOBAL_INDIVIDUAL";

    if (!fromUid || !toUid || fromUid === toUid) {
      console.log(`[onNudgeCreated] Invalid fromUid/toUid on nudge ${context.params.nudgeId}`);
      await snap.ref.set({
        delivered: false,
        processedAt: admin.firestore.FieldValue.serverTimestamp(),
        status: "INVALID_PARTICIPANTS"
      }, { merge: true });
      return;
    }

    // Check 15-minute per-pair (fromUid -> toUid) anti-spam rate limit
    const pairMetaRef = db.doc(`students/${toUid}/meta/nudge_${fromUid}`);
    const pairMetaSnap = await pairMetaRef.get();
    if (pairMetaSnap.exists) {
      const lastNudgeTs = pairMetaSnap.data()?.lastNudgeTimestamp as admin.firestore.Timestamp | undefined;
      if (lastNudgeTs) {
        const minutesElapsed = (Date.now() - lastNudgeTs.toDate().getTime()) / (1000 * 60);
        if (minutesElapsed < NUDGE_PAIR_COOLDOWN_MINUTES) {
          console.log(
            `[Rate Limit] Suppressed duplicate nudge from ${fromUid} to ${toUid} (${minutesElapsed.toFixed(1)}m < ${NUDGE_PAIR_COOLDOWN_MINUTES}m)`
          );
          await snap.ref.set({
            delivered: false,
            processedAt: admin.firestore.FieldValue.serverTimestamp(),
            status: "PAIR_COOLDOWN"
          }, { merge: true });
          return;
        }
      }
    }

    const title = `🦈 ${fromName} seni çalışmaya çağırıyor!`;
    const body = "Masaya geç ve odak seansını başlat! Arkadaşın çalışma grubunda seni bekliyor.";

    const fcmTokens = await resolveStudentFcmTokens(toUid);
    let delivered = false;

    if (fcmTokens.length > 0) {
      try {
        const multicastResp = await messaging.sendEachForMulticast({
          tokens: fcmTokens,
          notification: { title, body },
          data: {
            type: "STUDY_INVITE_NUDGE",
            triggerType: "NUDGE",
            fromUid,
            fromName,
            senderId: fromUid,
            senderName: fromName,
            toUid,
            groupId,
            nudgeId: context.params.nudgeId
          },
          android: {
            priority: "high"
          }
        });
        delivered = multicastResp.successCount > 0;
        console.log(
          `[onNudgeCreated] Dispatched STUDY_INVITE_NUDGE from ${fromUid} to ${toUid} (success=${multicastResp.successCount}/${fcmTokens.length})`
        );
      } catch (err) {
        console.error(`[onNudgeCreated] Failed to send FCM nudge from ${fromUid} to ${toUid}`, err);
        delivered = false;
      }
    } else {
      console.log(`[onNudgeCreated] No FCM tokens found for recipient ${toUid}`);
    }

    if (delivered) {
      await pairMetaRef.set({
        fromUid,
        fromName,
        senderId: fromUid,
        senderName: fromName,
        lastNudgeTimestamp: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });
    }

    await snap.ref.set({
      delivered,
      processedAt: admin.firestore.FieldValue.serverTimestamp(),
      deliveredAt: delivered ? admin.firestore.FieldValue.serverTimestamp() : null,
      status: delivered ? "DELIVERED" : "UNDELIVERED"
    }, { merge: true });
  });


