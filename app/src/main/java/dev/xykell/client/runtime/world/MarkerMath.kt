package dev.xykell.client.runtime.world

import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure marker geometry: distance and bearing from a player position to a
 * marker. No Android imports — host-testable.
 *
 * Coordinates are raw world coordinates as observed (ObservedState.motion).
 * Observation carries no dimension, so a reading across dimensions is a raw
 * coordinate delta and callers must label it as such rather than pretend the
 * scaled/other-world distance is known.
 */
object MarkerMath {

    /** Straight-line distance in metres between two world positions. */
    fun distance(ax: Double, ay: Double, az: Double,
                 bx: Double, by: Double, bz: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Horizontal bearing in degrees from A to B. 0 = north (-Z), 90 = east
     * (+X), normalised to [0, 360). Vertical component is ignored.
     */
    fun bearing(ax: Double, az: Double, bx: Double, bz: Double): Double {
        val dx = bx - ax
        val dz = bz - az // Minecraft north is -Z
        val deg = Math.toDegrees(atan2(dx, -dz))
        return if (deg < 0) deg + 360.0 else deg
    }

    /** 8-point compass label; sector centred every 45 degrees. */
    fun cardinal(deg: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val norm = ((deg % 360.0) + 360.0) % 360.0
        val idx = (norm / 45.0).roundToInt() % 8
        return names[idx]
    }

    /** Compact readout, e.g. "123m NE". */
    fun describe(distanceMetres: Double, bearingDegrees: Double): String =
        "%.0fm %s".format(distanceMetres, cardinal(bearingDegrees))
}
