import re
with open(r'app\src\main\java\com\example\sharkmentor\service\sync\SyncWorker.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# The file now has two copies of pullRemoteResources and pullRemoteTopics because the replace tool inserted them inside the previous block!
# Let's fix this manually by reading the previously known good version from task-1156 log? No, I can just use regex.
# Actually, the file was corrupted. I will find the first occurrence of "// ¦¦ 4. Inbound Downstream Pull: Coach-Assigned Goals (targets/goals) ¦¦"
# and the LAST occurrence of "// ¦¦ 5. Inbound Downstream Pull: Legacy Plans / Targets"
# and replace everything in between with the correct pullRemoteGoals.

start_marker = r'// ¦¦ 4. Inbound Downstream Pull: Coach-Assigned Goals \(targets/goals\)'
end_marker = r'// ¦¦ 5. Inbound Downstream Pull: Legacy Plans / Targets'

# Find the start index
start_idx = re.search(start_marker, content).start()

# Find the last occurrence of end_marker
matches = list(re.finditer(end_marker, content))
end_idx = matches[-1].start()

new_pull_goals = '''// ¦¦ 4. Inbound Downstream Pull: Coach-Assigned Goals (targets/goals) ¦¦
    private suspend fun pullRemoteGoals(
        db: com.example.sharkmentor.data.local.SharkMentorDatabase,
        firestore: FirebaseFirestore,
        syncTag: String,
        uid: String
    ) = withContext(Dispatchers.IO) {
        try {
            // Web writes to students/{uid}/goals
            val goalsSnapshot = firestore.collection("students").document(uid)
                .collection("goals")
                .get(Source.SERVER).await()

            val targetDao = db.targetDao()
            val concreteTargetDao = db.concreteTargetDao()

            var latestMacroGoal: com.google.firebase.firestore.DocumentSnapshot? = null

            for (doc in goalsSnapshot.documents) {
                val isDeleted = doc.getSafeBoolean("isDeleted") ?: false
                val remoteId = doc.getSafeLong("id")
                    ?: doc.id.toLongOrNull()
                    ?: abs(doc.id.hashCode().toLong())

                if (isDeleted) {
                    targetDao.purgeTarget(remoteId)
                    continue
                }

                // Check if it's the latest macro goal
                val hasTargetUni = !doc.getString("targetUniversity").isNullOrBlank()
                val hasTargetDep = !doc.getString("targetDepartment").isNullOrBlank()
                val hasTargetRank = (doc.getLong("targetRanking") ?: 0L) > 0L
                if (hasTargetUni || hasTargetDep || hasTargetRank) {
                    val currentCreatedAt = doc.getTimestamp("createdAt")?.toDate()?.time ?: doc.id.toLongOrNull() ?: 0L
                    val latestCreatedAt = latestMacroGoal?.getTimestamp("createdAt")?.toDate()?.time ?: latestMacroGoal?.id?.toLongOrNull() ?: -1L
                    if (currentCreatedAt > latestCreatedAt) {
                        latestMacroGoal = doc
                    }
                }

                val title = doc.getSafeString("title") ?: "Hedef"
                val category = doc.getSafeString("category") ?: "CUSTOM"
                val isCompleted = doc.getSafeBoolean("isCompleted") ?: false

                val targetEntity = TargetEntity(
                    id = remoteId,
                    title = title,
                    description = doc.getSafeString("description") ?: "",
                    deadlineMillis = System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000),
                    type = TargetType.SHORT_TERM,
                    period = TargetPeriod.WEEKLY,
                    priority = "NORMAL",
                    category = category,
                    targetValue = null,
                    isCompleted = isCompleted,
                    isAssignedByCoach = true,
                    coachNote = doc.getSafeString("coachNote"),
                    date = "",
                    isSynced = true,
                    isDeleted = false,
                    lastModifiedMillis = System.currentTimeMillis()
                )

                targetDao.upsertTarget(targetEntity)
                Log.d(syncTag, "Successfully saved remote goal to Room: id=\, title=\")
            }

            // Bridge to ConcreteTargetDao (Macro Target)
            latestMacroGoal?.let { doc ->
                val title = doc.getSafeString("title") ?: "Hedef"
                val targetRanking = doc.getSafeLong("targetRanking")?.toInt()
                val targetScoreOrRank = if (targetRanking != null && targetRanking > 0) "\ Sýralama" else ""
                val concreteTarget = com.example.sharkmentor.data.local.entity.ConcreteTargetEntity(
                    id = 1,
                    examType = "YKS",
                    targetTitle = title,
                    targetScoreOrRank = targetScoreOrRank,
                    aiAnalysisSummary = doc.getSafeString("coachNote") ?: "",
                    targetRanking = targetRanking,
                    targetUniversity = doc.getSafeString("targetUniversity"),
                    targetDepartment = doc.getSafeString("targetDepartment"),
                    updatedAtMillis = System.currentTimeMillis()
                )
                concreteTargetDao.saveTarget(concreteTarget)
                Log.d(syncTag, "Successfully bridged latest macro goal into ConcreteTargetDao")
            }
        } catch (e: Exception) {
            Log.e(syncTag, "CRITICAL FAILURE in pullRemoteGoals: \", e)
        }
    }

    '''

new_content = content[:start_idx] + new_pull_goals + content[end_idx:]

# Also fix the schedule bug in pullRemoteSchedule
new_content = new_content.replace(
    'val blocksList = dayMap["blocks"] as? List<*> ?: emptyList<Any>()',
    'val blocksList = (dayMap["classes"] as? List<*>) ?: (dayMap["blocks"] as? List<*>) ?: emptyList<Any>()'
)

new_content = new_content.replace(
    'teacherName = "Koç Programý",',
    'teacherName = blockMap["teacher"] as? String ?: "Koç Programý",'
)

with open(r'app\src\main\java\com\example\sharkmentor\service\sync\SyncWorker.kt', 'w', encoding='utf-8') as f:
    f.write(new_content)
