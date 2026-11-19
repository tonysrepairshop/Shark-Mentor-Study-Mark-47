import * as functions from "firebase-functions";
import * as admin from "firebase-admin";

const db = admin.firestore();

const SHARK_TIERS = [
  "CAMGOZ",
  "MAKO",
  "CEKICBAS",
  "KAPLAN",
  "BUYUK_BEYAZ",
  "MEGALODON"
] as const;

type SharkTierName = typeof SHARK_TIERS[number];

const MAKO_THRESHOLD = 28 * 3600;
const CEKICBAS_THRESHOLD = 49 * 3600;
const KAPLAN_THRESHOLD = 70 * 3600;
const BUYUK_BEYAZ_THRESHOLD = 84 * 3600;
const MEGALODON_THRESHOLD = 105 * 3600;
const MAX_WEEKLY_BONUS_SECONDS = 9000;

const TOP_HIGHLIGHT_COUNT = 3;
const RELEGATION_BOTTOM_COUNT = 5;

function toTierFromSeconds(seconds: number): SharkTierName {
  if (seconds >= MEGALODON_THRESHOLD) return "MEGALODON";
  if (seconds >= BUYUK_BEYAZ_THRESHOLD) return "BUYUK_BEYAZ";
  if (seconds >= KAPLAN_THRESHOLD) return "KAPLAN";
  if (seconds >= CEKICBAS_THRESHOLD) return "CEKICBAS";
  if (seconds >= MAKO_THRESHOLD) return "MAKO";
  return "CAMGOZ";
}

function parseTier(raw: unknown, fallbackSeconds: number): SharkTierName {
  if (typeof raw === "string") {
    const clean = raw.trim().toUpperCase();
    if ((SHARK_TIERS as readonly string[]).includes(clean)) {
      return clean as SharkTierName;
    }
  }
  return toTierFromSeconds(fallbackSeconds);
}

function lowerTierOf(tier: SharkTierName): SharkTierName | null {
  const idx = SHARK_TIERS.indexOf(tier);
  return idx > 0 ? SHARK_TIERS[idx - 1] : null;
}

function isoWeekKey(date: Date): string {
  const d = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()));
  const dayNum = d.getUTCDay() || 7;
  d.setUTCDate(d.getUTCDate() + 4 - dayNum);
  const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
  const weekNo = Math.ceil((((d.getTime() - yearStart.getTime()) / 86400000) + 1) / 7);
  return `${d.getUTCFullYear()}-W${String(weekNo).padStart(2, "0")}`;
}

interface LeagueRow {
  studentId: string;
  tier: SharkTierName;
  weeklyFocusSeconds: number;
  effectiveLeagueSeconds: number;
  mentorPoints: number;
}

/**
 * Weekly Shark League settlement (runs every Monday 00:05 Europe/Istanbul):
 * - Evaluates every league tier across `GLOBAL_INDIVIDUAL` and institution buckets.
 * - Top 3 in each tier earn `TOP_3` outcome and keep their earned tier.
 * - Bottom 5 in each tier (excluding Top 3 when tier size <= 8) are relegated 1 tier lower
 *   (`RELEGATED`), or repeat `CAMGOZ` (`REPEATED_CAMGOZ`) when already in the lowest tier.
 * - Idempotent per bucket via `institutions/{bucket}/weeklyLeagueHistory/{closedWeekKey}`.
 */
export const settleWeeklySharkLeagues = functions.pubsub
  .schedule("every monday 00:05")
  .timeZone("Europe/Istanbul")
  .onRun(async () => {
    const now = new Date();
    const newWeekKey = isoWeekKey(now);
    const closedDate = new Date(now.getTime() - 24 * 60 * 60 * 1000);
    const closedWeekKey = isoWeekKey(closedDate);

    const bucketIds = new Set<string>(["GLOBAL_INDIVIDUAL"]);
    try {
      const instSnap = await db.collection("institutions").limit(200).get();
      instSnap.docs.forEach((d) => bucketIds.add(d.id));
    } catch (err) {
      console.warn("[settleWeeklySharkLeagues] Failed to list institutions:", err);
    }

    for (const bucketId of bucketIds) {
      const historyRef = db
        .collection("institutions")
        .doc(bucketId)
        .collection("weeklyLeagueHistory")
        .doc(closedWeekKey);

      const alreadySettled = await historyRef.get();
      if (alreadySettled.exists) {
        continue;
      }

      const leagueSnap = await db
        .collection("institutions")
        .doc(bucketId)
        .collection("weeklyLeague")
        .get();

      if (leagueSnap.empty) {
        continue;
      }

      const byTier = new Map<SharkTierName, LeagueRow[]>();
      SHARK_TIERS.forEach((t) => byTier.set(t, []));

      for (const doc of leagueSnap.docs) {
        const data = doc.data() || {};
        const focusSecs = Math.max(0, Number(data.weeklyFocusSeconds || 0));
        const bonusSecs = Math.min(Math.max(0, Number(data.weeklyBonusSeconds || 0)), MAX_WEEKLY_BONUS_SECONDS);
        const effSecs = Math.max(focusSecs + bonusSecs, Number(data.effectiveLeagueSeconds || 0));
        const tier = parseTier(data.tier, effSecs);
        const mentorPoints = Math.max(0, Number(data.mentorPoints || 0));
        byTier.get(tier)!.push({
          studentId: doc.id,
          tier,
          weeklyFocusSeconds: focusSecs,
          effectiveLeagueSeconds: effSecs,
          mentorPoints
        });
      }

      const tierSummary: Record<string, { total: number; relegated: number; top3Ids: string[] }> = {};
      const updates: Array<{
        studentId: string;
        payload: Record<string, unknown>;
      }> = [];

      for (const tier of SHARK_TIERS) {
        const rows = byTier.get(tier)!;
        rows.sort((a, b) =>
          (b.effectiveLeagueSeconds - a.effectiveLeagueSeconds) ||
          (b.weeklyFocusSeconds - a.weeklyFocusSeconds) ||
          (b.mentorPoints - a.mentorPoints) ||
          a.studentId.localeCompare(b.studentId)
        );

        const total = rows.length;
        const relegationStartRank = total > TOP_HIGHLIGHT_COUNT
          ? Math.max(TOP_HIGHLIGHT_COUNT + 1, total - RELEGATION_BOTTOM_COUNT + 1)
          : null;
        const lower = lowerTierOf(tier);
        let relegatedCount = 0;

        rows.forEach((row, index) => {
          const rank = index + 1;
          const isRelegation = relegationStartRank !== null && rank >= relegationStartRank;
          let outcome: "TOP_3" | "SAFE" | "RELEGATED" | "REPEATED_CAMGOZ";
          let nextTier: SharkTierName;
          let relegatedFrom: SharkTierName | null = null;

          if (rank <= TOP_HIGHLIGHT_COUNT) {
            outcome = "TOP_3";
            nextTier = tier;
          } else if (isRelegation) {
            relegatedCount += 1;
            if (lower !== null) {
              outcome = "RELEGATED";
              nextTier = lower;
              relegatedFrom = tier;
            } else {
              outcome = "REPEATED_CAMGOZ";
              nextTier = "CAMGOZ";
              relegatedFrom = null;
            }
          } else {
            outcome = "SAFE";
            nextTier = tier;
          }

          updates.push({
            studentId: row.studentId,
            payload: {
              assignedLeagueTier: nextTier,
              tier: nextTier,
              relegationWeekKey: newWeekKey,
              relegatedFromTier: relegatedFrom,
              lastWeekOutcome: outcome,
              lastWeekPlacement: rank,
              lastWeekTotalInTier: total,
              weeklyFocusSeconds: 0,
              weeklyBonusSeconds: 0,
              effectiveLeagueSeconds: 0,
              settledAt: admin.firestore.FieldValue.serverTimestamp()
            }
          });
        });

        tierSummary[tier] = {
          total,
          relegated: relegatedCount,
          top3Ids: rows.slice(0, TOP_HIGHLIGHT_COUNT).map((r) => r.studentId)
        };
      }

      // Commit updates in safe Firestore batch chunks (200 students => 400 writes max per batch)
      const CHUNK_SIZE = 200;
      for (let i = 0; i < updates.length; i += CHUNK_SIZE) {
        const chunk = updates.slice(i, i + CHUNK_SIZE);
        const batch = db.batch();
        for (const item of chunk) {
          const leagueDocRef = db
            .collection("institutions")
            .doc(bucketId)
            .collection("weeklyLeague")
            .doc(item.studentId);
          batch.set(leagueDocRef, item.payload, { merge: true });

          if (bucketId === "GLOBAL_INDIVIDUAL") {
            const studentRef = db.collection("students").doc(item.studentId);
            batch.set(
              studentRef,
              {
                assignedLeagueTier: item.payload.assignedLeagueTier,
                relegationWeekKey: item.payload.relegationWeekKey,
                relegatedFromTier: item.payload.relegatedFromTier,
                lastWeekOutcome: item.payload.lastWeekOutcome,
                lastWeekPlacement: item.payload.lastWeekPlacement,
                lastWeekTotalInTier: item.payload.lastWeekTotalInTier
              },
              { merge: true }
            );
          }
        }
        await batch.commit();
      }

      await historyRef.set({
        closedWeekKey,
        newWeekKey,
        bucketId,
        relegationLimit: RELEGATION_BOTTOM_COUNT,
        topHighlightCount: TOP_HIGHLIGHT_COUNT,
        tierSummary,
        settledAt: admin.firestore.FieldValue.serverTimestamp()
      });

      console.log(`[settleWeeklySharkLeagues] Settled bucket=${bucketId} week=${closedWeekKey} -> ${newWeekKey} (${updates.length} entries)`);
    }

    return null;
  });
