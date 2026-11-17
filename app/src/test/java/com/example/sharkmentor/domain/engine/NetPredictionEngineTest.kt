package com.example.sharkmentor.domain.engine

import com.example.sharkmentor.domain.model.prediction.ExamCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetPredictionEngineTest {

    @Test
    fun testBasicAverage_calculatesCorrectAverage() {
        val nets = listOf(70.0f, 75.0f, 80.0f)
        val avg = NetPredictionEngine.calculateBasicAverage(nets)
        assertEquals(75.0f, avg, 0.1f)
    }

    @Test
    fun testNetGap_calculatesCorrectGap() {
        val gap = NetPredictionEngine.calculateNetGap(100.0f, 85.0f)
        assertEquals(15.0f, gap, 0.1f)
    }

    @Test
    fun testFallbackEmptyResult_returnsValidDefaults() {
        val result = NetPredictionEngine.fallbackEmptyResult(ExamCategory.TYT, 80.0f)
        assertNotNull(result)
        assertEquals(ExamCategory.TYT, result.category)
        assertEquals(0f, result.currentAverageNet, 0.01f)
        assertEquals(80.0f, result.targetNet, 0.01f)
        assertEquals(80.0f, result.netGap, 0.01f)
        assertTrue(result.diagnosticSummary.contains("henüz girilmedi"))
    }
}
