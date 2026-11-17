import * as functions from "firebase-functions";
import * as admin from "firebase-admin";

const db = admin.firestore();
const messaging = admin.messaging();

interface NotificationState {
  lastNotificationTimestamp: admin.firestore.Timestamp;
  lastTriggerType: '48H_INACTIVITY' | 'STREAK_3DAY' | 'EXAM_UPLOADED' | 'MANUAL';
  streakDaysCount: number;
}

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
 * Checks if a notification can be sent based on 24-hour rate limit.
 */
async function canSendNotification(studentId: string): Promise<boolean> {
  const metaRef = db.doc(`students/${studentId}/meta/notificationState`);
  const metaSnap = await metaRef.get();
  
  if (!metaSnap.exists) return true;
  
  const state = metaSnap.data() as NotificationState;
  if (!state.lastNotificationTimestamp) return true;
  
  const lastTime = state.lastNotificationTimestamp.toDate().getTime();
  const now = Date.now();
  const hoursSinceLast = (now - lastTime) / (1000 * 60 * 60);
  
  return hoursSinceLast >= 24;
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
  triggerType: NotificationState['lastTriggerType']
): Promise<void> {
  if (isQuietHours()) {
    console.log(`[Quiet Hours] Suppressed notification for ${studentId}: ${title}`);
    return;
  }
  
  if (!(await canSendNotification(studentId))) {
    console.log(`[Rate Limit] Suppressed notification for ${studentId}: ${title}`);
    return;
  }
  
  // Fetch FCM tokens (Assuming array of strings at students/{studentId}.fcmTokens)
  const studentSnap = await db.doc(`students/${studentId}`).get();
  const fcmTokens = studentSnap.data()?.fcmTokens as string[] | undefined;
  
  if (fcmTokens && fcmTokens.length > 0) {
    try {
      await messaging.sendEachForMulticast({
        tokens: fcmTokens,
        notification: { title, body },
        data: { triggerType }
      });
      
      // Update state
      await db.doc(`students/${studentId}/meta/notificationState`).set({
        lastNotificationTimestamp: admin.firestore.FieldValue.serverTimestamp(),
        lastTriggerType: triggerType
      }, { merge: true });
      
      console.log(`Successfully dispatched ${triggerType} to ${studentId}`);
    } catch (err) {
      console.error(`Failed to send FCM to ${studentId}`, err);
    }
  } else {
    console.log(`No FCM tokens found for ${studentId}`);
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

// ─── SHARK LEAGUE NUDGE ("DÜRTME") PUSH NOTIFICATION RULE ───────────────────

/**
 * Triggered when a student nudges a friend or study group member via `nudges/{nudgeId}`.
 * Enforces:
 * - Quiet hours (23:00 - 08:30 UTC+3)
 * - 12-hour per-pair anti-spam rate limit (`senderId -> receiverId`)
 * - Receiver global rate limit (12h window for nudges)
 */
export const onNudgeCreated = functions.firestore
  .document('nudges/{nudgeId}')
  .onCreate(async (snap, context) => {
    const data = snap.data();
    if (!data) return;

    const senderId = (data.senderId as string | undefined)?.trim() || "";
    const receiverId = (data.receiverId as string | undefined)?.trim() || "";
    const senderName = (data.senderName as string | undefined)?.trim() || "Arkadaşın";
    const groupId = (data.groupId as string | undefined)?.trim() || "GLOBAL_INDIVIDUAL";

    if (!senderId || !receiverId || senderId === receiverId) {
      console.log(`[onNudgeCreated] Invalid sender/receiver on nudge ${context.params.nudgeId}`);
      return;
    }

    if (isQuietHours()) {
      console.log(`[Quiet Hours] Suppressed nudge from ${senderId} to ${receiverId}`);
      return;
    }

    // Check 12-hour per-pair (sender -> receiver) rate limit
    const pairMetaRef = db.doc(`students/${receiverId}/meta/nudge_${senderId}`);
    const pairMetaSnap = await pairMetaRef.get();
    if (pairMetaSnap.exists) {
      const lastNudgeTs = pairMetaSnap.data()?.lastNudgeTimestamp as admin.firestore.Timestamp | undefined;
      if (lastNudgeTs) {
        const hoursElapsed = (Date.now() - lastNudgeTs.toDate().getTime()) / (1000 * 60 * 60);
        if (hoursElapsed < NUDGE_COOLDOWN_HOURS) {
          console.log(`[Rate Limit] Suppressed duplicate nudge from ${senderId} to ${receiverId} (${hoursElapsed.toFixed(1)}h < 12h)`);
          return;
        }
      }
    }

    const title = `🦈 ${senderName} Seni Masaya Çağırıyor!`;
    const body = (data.message as string | undefined)?.trim() ||
      `${senderName} seni dürttü! Kronometreni başlat ve odak yarışında geri kalma.`;

    const sent = await dispatchPushNotification(
      receiverId,
      title,
      body,
      "NUDGE",
      NUDGE_COOLDOWN_HOURS,
      {
        senderId,
        senderName,
        groupId,
        nudgeId: context.params.nudgeId
      }
    );

    if (sent) {
      await pairMetaRef.set({
        senderId,
        senderName,
        lastNudgeTimestamp: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      await snap.ref.set({
        deliveredAt: admin.firestore.FieldValue.serverTimestamp(),
        status: "DELIVERED"
      }, { merge: true });
    }
  });

