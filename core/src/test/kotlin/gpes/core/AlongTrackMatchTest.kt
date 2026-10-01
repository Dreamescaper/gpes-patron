package gpes.core

import gpes.core.road.AlongTrackMatch
import gpes.core.road.AlongTrackMatch.Sample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.exp

class AlongTrackMatchTest {

    /** Road bearing along the road: north until 200 m, a 90° right turn over 15 m, then east. */
    private fun roadBearing(pos: Double) = when {
        pos < 200 -> 0.0
        pos < 215 -> (pos - 200) / 15 * PI / 2
        else -> PI / 2
    }

    /** Profiles sampled every 2 m back from now; the matched point is at [matched], the car really at [truePos]. */
    private fun profiles(matched: Double, truePos: Double, gyroExtra: (Double) -> Double = { 0.0 }, offset: Double = 0.3): Pair<List<Sample>, List<Sample>> {
        val road = (0..150).map { val b = it * 2.0; Sample(b, roadBearing(matched - b)) }
        val gyro = (0..150).map { val b = it * 2.0; Sample(b, roadBearing(truePos - b) + offset + gyroExtra(truePos - b)) }
        return gyro to road
    }

    @Test
    fun `a turn taken 20 m earlier than the matched point says we are 20 m further`() {
        val (g, r) = profiles(matched = 260.0, truePos = 280.0)
        val res = AlongTrackMatch.match(g, r)
        assertNotNull(res)
        assertEquals(20.0, res!!.shiftM, 2.1)
    }

    @Test
    fun `and 15 m behind gives a negative shift`() {
        val (g, r) = profiles(matched = 290.0, truePos = 275.0)
        assertEquals(-15.0, AlongTrackMatch.match(g, r)!!.shiftM, 2.1)
    }

    @Test
    fun `a gentle 15 degree bend still gives the shift, with a wider sigma`() {
        val bend = { pos: Double -> when { pos < 200 -> 0.0; pos < 240 -> (pos - 200) / 40 * Math.toRadians(15.0); else -> Math.toRadians(15.0) } }
        val road = (0..150).map { val b = it * 2.0; Sample(b, bend(260.0 - b)) }
        val gyro = (0..150).map { val b = it * 2.0; Sample(b, bend(280.0 - b) + 0.2) }
        val res = AlongTrackMatch.match(gyro, road, AlongTrackMatch.Config(minTurnDeg = 12.0))
        assertNotNull(res)
        assertEquals(20.0, res!!.shiftM, 4.1)
        val sharp = AlongTrackMatch.match(profiles(260.0, 280.0).first, profiles(260.0, 280.0).second)!!
        assert(res.sigmaM >= sharp.sigmaM) { "gentle ${res.sigmaM} vs sharp ${sharp.sigmaM}" }
    }

    @Test
    fun `a straight road gives no along-track information`() {
        val road = (0..150).map { Sample(it * 2.0, 0.0) }
        val gyro = (0..150).map { Sample(it * 2.0, 0.1) }
        assertNull(AlongTrackMatch.match(gyro, road))
    }

    @Test
    fun `a lane change on a straight road never shifts`() {
        // Out-and-back 6° excursion over ~40 m (a lane change), road straight.
        val lane = { p: Double -> Math.toRadians(6.0) * exp(-((p - 150) / 12).let { it * it }) }
        val road = (0..150).map { Sample(it * 2.0, 0.0) }
        val gyro = (0..150).map { val b = it * 2.0; Sample(b, lane(300 - b)) }
        assertNull(AlongTrackMatch.match(gyro, road))
    }

    @Test
    fun `a gyro path that does not turn where the road turns is rejected`() {
        val (_, r) = profiles(matched = 260.0, truePos = 260.0)
        val gyro = (0..150).map { Sample(it * 2.0, 0.0) }
        assertNull(AlongTrackMatch.match(gyro, r))
    }
}
