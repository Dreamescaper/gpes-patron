package gpes.core

import gpes.core.road.RoadTileFiles
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** When a cached road tile is downloaded again (D-065). Times are arbitrary; nothing here comes from a recording. */
class RoadTileFreshnessTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun `a tile is fresh for 90 days`() {
        assertFalse(RoadTileFiles.isStale(now - 89 * day, now))
        assertFalse(RoadTileFiles.isStale(now - 90 * day, now))
    }

    @Test
    fun `an older tile is due for a refresh`() {
        assertTrue(RoadTileFiles.isStale(now - 91 * day, now))
        assertTrue(RoadTileFiles.isStale(now - 400 * day, now))
    }

    @Test
    fun `an unknown time is due, a time in the future is not`() {
        assertTrue(RoadTileFiles.isStale(0, now))
        assertFalse(RoadTileFiles.isStale(now + 30 * day, now)) // the clock was wrong when it was written
    }
}
