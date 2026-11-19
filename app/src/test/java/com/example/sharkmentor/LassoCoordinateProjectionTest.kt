package com.example.sharkmentor

import com.example.sharkmentor.ui.screens.notes.drawing.DrawingScreenshotHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LassoCoordinateProjectionTest {

    @Test
    fun projectLocalBoundsToWindow_handlesScrolledPastTopWithNegativeCanvasOrigin() {
        // Canvas local origin (0,0) scrolled 500px above top of screen; visible viewport starts below 160px toolbar.
        // Local lasso box [100..500, 700..950] maps to window [100..500, 200..450] via localToWindow().
        val canvasOriginY = -500f
        val localTop = 700f
        val localBottom = 950f

        val rect = DrawingScreenshotHelper.projectLocalBoundsToWindow(
            projectedLeft = 100f,
            projectedTop = canvasOriginY + localTop,
            projectedRight = 500f,
            projectedBottom = canvasOriginY + localBottom,
            visibleClipLeft = 0f,
            visibleClipTop = 160f,
            visibleClipRight = 1080f,
            visibleClipBottom = 2200f,
            windowWidth = 1080,
            windowHeight = 2400
        )

        assertNotNull(rect)
        assertEquals(100, rect!!.left)
        assertEquals(200, rect.top)
        assertEquals(500, rect.right)
        assertEquals(450, rect.bottom)
        assertEquals(400, rect.width)
        assertEquals(250, rect.height)
    }

    @Test
    fun projectLocalBoundsToWindow_clipsSelectionExtendingPastTopToolbarAndBottomViewport() {
        val rect = DrawingScreenshotHelper.projectLocalBoundsToWindow(
            projectedLeft = -40f,
            projectedTop = 100f,
            projectedRight = 1120f,
            projectedBottom = 2300f,
            visibleClipLeft = 24f,
            visibleClipTop = 180f,
            visibleClipRight = 1056f,
            visibleClipBottom = 2100f,
            windowWidth = 1080,
            windowHeight = 2400
        )

        assertNotNull(rect)
        assertEquals(24, rect!!.left)
        assertEquals(180, rect.top)
        assertEquals(1056, rect.right)
        assertEquals(2100, rect.bottom)
    }

    @Test
    fun projectLocalBoundsToWindow_handles2xZoomAndPanTransform() {
        // 2x zoom with pan (-300, -150): local [200..450, 300..500] -> window [100..600, 450..850]
        val zoom = 2f
        val panX = -300f
        val panY = -150f

        val rect = DrawingScreenshotHelper.projectLocalBoundsToWindow(
            projectedLeft = 200f * zoom + panX,
            projectedTop = 300f * zoom + panY,
            projectedRight = 450f * zoom + panX,
            projectedBottom = 500f * zoom + panY,
            visibleClipLeft = 0f,
            visibleClipTop = 120f,
            visibleClipRight = 1080f,
            visibleClipBottom = 2200f,
            windowWidth = 1080,
            windowHeight = 2400
        )

        assertNotNull(rect)
        assertEquals(100, rect!!.left)
        assertEquals(450, rect.top)
        assertEquals(600, rect.right)
        assertEquals(850, rect.bottom)
    }

    @Test
    fun projectLocalBoundsToWindow_returnsNullWhenOffScreenOrTooSmall() {
        val offScreen = DrawingScreenshotHelper.projectLocalBoundsToWindow(
            projectedLeft = 50f,
            projectedTop = -300f,
            projectedRight = 400f,
            projectedBottom = 50f,
            visibleClipLeft = 0f,
            visibleClipTop = 160f,
            visibleClipRight = 1080f,
            visibleClipBottom = 2200f,
            windowWidth = 1080,
            windowHeight = 2400
        )
        assertNull(offScreen)

        val tiny = DrawingScreenshotHelper.projectLocalBoundsToWindow(
            projectedLeft = 200f,
            projectedTop = 300f,
            projectedRight = 202f,
            projectedBottom = 302f,
            visibleClipLeft = 0f,
            visibleClipTop = 0f,
            visibleClipRight = 1080f,
            visibleClipBottom = 2400f,
            windowWidth = 1080,
            windowHeight = 2400
        )
        assertNull(tiny)
    }

    @Test
    fun mapLocalBoundsToBitmapRect_mapsHighResPdfPageAccurately() {
        // On-screen PDF card is 1000x1400 px, backing rendered bitmap is 2000x2800 px (2x scale)
        val rect = DrawingScreenshotHelper.mapLocalBoundsToBitmapRect(
            localLeft = 150f,
            localTop = 350f,
            localRight = 650f,
            localBottom = 900f,
            canvasWidth = 1000f,
            canvasHeight = 1400f,
            bitmapWidth = 2000,
            bitmapHeight = 2800
        )

        assertNotNull(rect)
        assertEquals(300, rect!!.left)
        assertEquals(700, rect.top)
        assertEquals(1300, rect.right)
        assertEquals(1800, rect.bottom)
    }

    @Test
    fun mapLocalBoundsToBitmapRect_clampsOverflowAndRejectsInvalidDimensions() {
        val clamped = DrawingScreenshotHelper.mapLocalBoundsToBitmapRect(
            localLeft = -50f,
            localTop = -10f,
            localRight = 1200f,
            localBottom = 1600f,
            canvasWidth = 1000f,
            canvasHeight = 1500f,
            bitmapWidth = 1500,
            bitmapHeight = 2250
        )

        assertNotNull(clamped)
        assertEquals(0, clamped!!.left)
        assertEquals(0, clamped.top)
        assertEquals(1500, clamped.right)
        assertEquals(2250, clamped.bottom)

        assertNull(
            DrawingScreenshotHelper.mapLocalBoundsToBitmapRect(
                localLeft = 10f,
                localTop = 10f,
                localRight = 200f,
                localBottom = 200f,
                canvasWidth = 0f,
                canvasHeight = 1000f,
                bitmapWidth = 1000,
                bitmapHeight = 1000
            )
        )
    }
}
