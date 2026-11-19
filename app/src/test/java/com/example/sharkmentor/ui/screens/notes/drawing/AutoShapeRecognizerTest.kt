package com.example.sharkmentor.ui.screens.notes.drawing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class AutoShapeRecognizerTest {

    @Test
    fun recognize_straightensSlightlyCrookedHorizontalLine() {
        val pts = (0..24).map { i ->
            val t = i / 24f
            val wobble = sin(t * PI * 3.0).toFloat() * 4.5f
            DrawingPoint(x = 50f + t * 240f, y = 120f + t * 8f + wobble)
        }
        val recognized = AutoShapeRecognizer.recognize(pts)
        assertNotNull(recognized)
        assertEquals(ShapeKind.LINE, recognized!!.shapeKind)
        assertEquals(2, recognized.points.size)
        // Snapped near-horizontal (slope < 6.5 deg -> exact horizontal)
        assertEquals(recognized.points[0].y, recognized.points[1].y, 0.01f)
    }

    @Test
    fun recognize_detectsRoughTriangleWithThreeVertices() {
        val a = DrawingPoint(200f, 60f)
        val b = DrawingPoint(340f, 280f)
        val c = DrawingPoint(60f, 276f)
        val pts = buildList {
            addAll(sampleNoisyEdge(a, b, 16, 4.0f))
            addAll(sampleNoisyEdge(b, c, 16, 4.0f))
            addAll(sampleNoisyEdge(c, a, 16, 4.0f))
        }

        val recognized = AutoShapeRecognizer.recognize(pts)
        assertNotNull(recognized)
        assertEquals(ShapeKind.TRIANGLE, recognized!!.shapeKind)
        assertEquals(3, recognized.points.size)
    }

    @Test
    fun recognize_detectsRoughRectangle() {
        val tl = DrawingPoint(60f, 80f)
        val tr = DrawingPoint(320f, 84f)
        val br = DrawingPoint(316f, 230f)
        val bl = DrawingPoint(64f, 226f)
        val pts = buildList {
            addAll(sampleNoisyEdge(tl, tr, 14, 3.5f))
            addAll(sampleNoisyEdge(tr, br, 12, 3.5f))
            addAll(sampleNoisyEdge(br, bl, 14, 3.5f))
            addAll(sampleNoisyEdge(bl, tl, 12, 3.5f))
        }

        val recognized = AutoShapeRecognizer.recognize(pts)
        assertNotNull(recognized)
        assertEquals(ShapeKind.RECTANGLE, recognized!!.shapeKind)
        assertEquals(2, recognized.points.size)
    }

    @Test
    fun recognize_detectsRoughCircleAndSnapsAspectRatio() {
        val cx = 200f
        val cy = 200f
        val pts = (0..42).map { i ->
            val theta = (2.0 * PI * i) / 42.0
            val r = 90f + sin(theta * 3.0).toFloat() * 5.0f
            DrawingPoint(
                x = cx + (r * 1.04f * cos(theta)).toFloat(),
                y = cy + (r * sin(theta)).toFloat()
            )
        }

        val recognized = AutoShapeRecognizer.recognize(pts)
        assertNotNull(recognized)
        assertEquals(ShapeKind.ELLIPSE, recognized!!.shapeKind)
        val w = abs(recognized.points[1].x - recognized.points[0].x)
        val h = abs(recognized.points[1].y - recognized.points[0].y)
        assertTrue("Expected snapped circle w=$w h=$h", abs(w - h) < 0.5f)
    }

    @Test
    fun recognize_ignoresSmallHandwritingAndOpenCurves() {
        // Small letter "o" (~22px wide) must NOT snap
        val tinyO = (0..18).map { i ->
            val theta = (2.0 * PI * i) / 18.0
            DrawingPoint(50f + (11f * cos(theta)).toFloat(), 50f + (11f * sin(theta)).toFloat())
        }
        assertNull(AutoShapeRecognizer.recognize(tinyO))

        // Open letter "S" wave must NOT snap
        val letterS = (0..30).map { i ->
            val t = i / 30f
            DrawingPoint(
                x = 120f + sin(t * PI * 2.0).toFloat() * 45f,
                y = 60f + t * 140f
            )
        }
        assertNull(AutoShapeRecognizer.recognize(letterS))
    }

    private fun sampleNoisyEdge(
        start: DrawingPoint,
        end: DrawingPoint,
        steps: Int,
        noiseAmp: Float
    ): List<DrawingPoint> {
        return (0 until steps).map { i ->
            val t = i.toFloat() / steps.toFloat()
            val wobble = sin(t * PI * 2.0).toFloat() * noiseAmp
            DrawingPoint(
                x = start.x + (end.x - start.x) * t + wobble,
                y = start.y + (end.y - start.y) * t - wobble * 0.5f
            )
        }
    }
}
