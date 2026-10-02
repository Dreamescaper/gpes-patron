package gpes.core.geo

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * A north-up map view for drawing: the point ([centerLat], [centerLon]) is at the middle of the screen and one
 * screen pixel is [metersPerPx] metres. `x` grows to the east and `y` to the south (screen coordinates), relative to
 * the middle. A local tangent plane is accurate to a metre within the few kilometres a phone map shows.
 */
class MapView(val centerLat: Double, val centerLon: Double, val metersPerPx: Double) {
    private val frame = LocalFrame(centerLat, centerLon)

    fun x(lat: Double, lon: Double): Double = frame.toEnu(lat, lon).e / metersPerPx
    fun y(lat: Double, lon: Double): Double = -frame.toEnu(lat, lon).n / metersPerPx

    /** The map point under a pixel offset from the middle of the screen. */
    fun toLatLon(dxPx: Double, dyPx: Double): LatLon = frame.toLatLon(dxPx * metersPerPx, -dyPx * metersPerPx)

    companion object {
        /** The longest round scale-bar length (1, 2 or 5 × 10ⁿ metres) that fits in [maxPx] pixels. */
        fun niceScaleM(metersPerPx: Double, maxPx: Double): Double {
            val limit = metersPerPx * maxPx
            val pow10 = 10.0.pow(floor(log10(limit)))
            return listOf(5.0, 2.0, 1.0).map { it * pow10 }.first { it <= limit }
        }
    }
}
