package dev.xykell.client.runtime.world

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkerMathTest {

    private val eps = 1e-9

    @Test
    fun distanceIs345() {
        assertEquals(5.0, MarkerMath.distance(0.0, 0.0, 0.0, 3.0, 4.0, 0.0), eps)
    }

    @Test
    fun distanceCountsVerticalSeparation() {
        assertEquals(10.0, MarkerMath.distance(0.0, 0.0, 0.0, 0.0, 10.0, 0.0), eps)
    }

    @Test
    fun distanceToSelfIsZero() {
        assertEquals(0.0, MarkerMath.distance(1.0, 2.0, 3.0, 1.0, 2.0, 3.0), eps)
    }

    @Test
    fun bearingNorthIsZero() {
        assertEquals(0.0, MarkerMath.bearing(0.0, 0.0, 0.0, -10.0), eps)
    }

    @Test
    fun bearingEastIsNinety() {
        assertEquals(90.0, MarkerMath.bearing(0.0, 0.0, 10.0, 0.0), eps)
    }

    @Test
    fun bearingSouthIsOneEighty() {
        assertEquals(180.0, MarkerMath.bearing(0.0, 0.0, 0.0, 10.0), eps)
    }

    @Test
    fun bearingWestIsTwoSeventy() {
        assertEquals(270.0, MarkerMath.bearing(0.0, 0.0, -10.0, 0.0), eps)
    }

    @Test
    fun bearingNormalisesNegativeTo315() {
        assertEquals(315.0, MarkerMath.bearing(0.0, 0.0, -1.0, -1.0), eps)
    }

    @Test
    fun cardinalMapsTheEightSectors() {
        assertEquals("N", MarkerMath.cardinal(0.0))
        assertEquals("NE", MarkerMath.cardinal(45.0))
        assertEquals("E", MarkerMath.cardinal(90.0))
        assertEquals("SE", MarkerMath.cardinal(135.0))
        assertEquals("S", MarkerMath.cardinal(180.0))
        assertEquals("SW", MarkerMath.cardinal(225.0))
        assertEquals("W", MarkerMath.cardinal(270.0))
        assertEquals("NW", MarkerMath.cardinal(315.0))
    }

    @Test
    fun cardinalWrapsOutOfRangeBearings() {
        assertEquals("N", MarkerMath.cardinal(360.0))
        assertEquals("W", MarkerMath.cardinal(-90.0))
    }

    @Test
    fun describeFormatsMetresPlusDirection() {
        assertEquals("123m NE", MarkerMath.describe(123.4, 45.0))
        assertEquals("0m N", MarkerMath.describe(0.0, 0.0))
    }
}
