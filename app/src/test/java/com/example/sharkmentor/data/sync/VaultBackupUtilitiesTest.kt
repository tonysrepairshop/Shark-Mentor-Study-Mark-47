package com.example.sharkmentor.data.sync

import com.example.sharkmentor.service.sync.SyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VaultBackupUtilitiesTest {

    @Test
    fun sha256HexIsDeterministicAndMatchesStandardDigest() {
        val helloHash = VaultBackupManager.sha256Hex("sharkmentor-vault-v2".toByteArray(Charsets.UTF_8))
        assertEquals(64, helloHash.length)
        assertEquals(
            helloHash,
            VaultBackupManager.sha256Hex("sharkmentor-vault-v2".toByteArray(Charsets.UTF_8))
        )
        assertNotEquals(
            helloHash,
            VaultBackupManager.sha256Hex("sharkmentor-vault-v1".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun sanitizeTurkishTextRepairsMojibakeCleanly() {
        val corrupted = "Matematik Ã‡Ã¶zÃ¼mÃ¼ ve Ä°statistik Ã–zeti"
        assertEquals(
            "Matematik Çözümü ve İstatistik Özeti",
            SyncWorker.sanitizeTurkishText(corrupted)
        )
    }
}
