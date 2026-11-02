package com.measurecam

import com.measurecam.util.CaptureOrientation
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureOrientationTest {
    @Test fun allFourPhysicalOrientationsMapToSurfaceRotations() {
        assertEquals(0, CaptureOrientation.rotationFor(0, 0))
        assertEquals(3, CaptureOrientation.rotationFor(90, 0))
        assertEquals(2, CaptureOrientation.rotationFor(180, 0))
        assertEquals(1, CaptureOrientation.rotationFor(270, 0))
    }
    @Test fun unknownSensorReadingKeepsLastDirection() {
        assertEquals(3, CaptureOrientation.rotationFor(-1, 3))
        assertEquals(1, CaptureOrientation.rotationFor(360, 1))
    }
    @Test fun diagonalMovementDoesNotOscillate() {
        var rotation = 0
        for (degrees in listOf(42, 48, 44, 52, 46)) rotation = CaptureOrientation.rotationFor(degrees, rotation)
        assertEquals(0, rotation)
        assertEquals(3, CaptureOrientation.rotationFor(55, rotation))
        assertEquals(3, CaptureOrientation.rotationFor(40, 3))
        assertEquals(0, CaptureOrientation.rotationFor(35, 3))
    }
    @Test fun zeroDegreeWrapKeepsPortrait() {
        assertEquals(0, CaptureOrientation.rotationFor(359, 0))
        assertEquals(0, CaptureOrientation.rotationFor(306, 0))
        assertEquals(1, CaptureOrientation.rotationFor(305, 0))
    }
    @Test fun landscapeCanSwitchDirectlyToOppositeSide() {
        assertEquals(1, CaptureOrientation.rotationFor(270, 3))
        assertEquals(3, CaptureOrientation.rotationFor(90, 1))
    }
}
