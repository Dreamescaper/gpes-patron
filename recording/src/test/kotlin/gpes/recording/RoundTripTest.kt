package gpes.recording

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import gpes.core.model.AgcInfo
import gpes.core.model.Annotation
import gpes.core.model.Cov2
import gpes.core.model.DriveRecord
import gpes.core.model.EstimatorMode
import gpes.core.model.GnssClockInfo
import gpes.core.model.GnssMeasurementBatch
import gpes.core.model.GnssRawMeas
import gpes.core.model.Hypothesis
import gpes.core.model.LocSource
import gpes.core.model.Measurement
import gpes.core.model.PositionEstimate
import gpes.core.model.ProviderEvent
import gpes.core.model.RecordOrder
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustReason
import gpes.core.model.TrustState
import gpes.core.sim.DriveSimulator
import gpes.core.sim.SimConfig
import gpes.recording.db.DriveDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.StringWriter
import java.nio.file.Files

class RoundTripTest {
    private fun newDb(): DriveDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DriveDatabase.Schema.create(driver)
        return DriveDatabase(driver)
    }

    @Test
    fun `measurements and outputs survive a sqlite round trip`() {
        val db = newDb()
        val w = DriveWriter(db)
        val sim = DriveSimulator.generate(SimConfig(networkPeriodS = 20.0)).records.take(20_000)
        val extra = listOf<DriveRecord>(
            GnssMeasurementBatch(
                sim[100].tNs, GnssClockInfo(123, 18, 1.0, -99, 0.5, 0.1, 2.0, 0.3, 1),
                listOf(GnssRawMeas(5, 1, 0.0, 16431, 1_000_000, 10, 35.5, -500.1, 0.1, 0, 0.0, 0.0, 1.57542e9, 0, null, 2.5, 32.0, "C")),
                listOf(AgcInfo(1, 1.57542e9, 3.2)),
            ),
            ProviderEvent(sim[200].tNs, "gps", ProviderEvent.Kind.OVERRIDDEN),
            Annotation(sim[300].tNs, "tunnel", "entering, with comma"),
            TrustAssessment(sim[400].tNs, LocSource.GNSS, "gps", TrustState.REJECTED, 0.05, setOf(TrustReason.IMPOSSIBLE_VELOCITY, TrustReason.INNOVATION_GATE), 99.0, 1000.0),
            PositionEstimate(sim[500].tNs, "baseline", 50.0, 30.0, Cov2(4.0, 0.5, 9.0), 1.0, 0.1, 10.0, 0.5, EstimatorMode.DEAD_RECKONING, 0.7,
                listOf(Hypothesis(1.0, 50.0, 30.0, Cov2(4.0, 0.5, 9.0)))),
        )
        (sim + extra).forEach(w::write)
        w.flush()

        val r = DriveReader(db)
        assertEquals(DRIVE_SCHEMA_VERSION, r.schemaVersion())
        assertEquals(sim.first(), r.session())
        val expected = (sim + extra).filterIsInstance<Measurement>().sortedWith(RecordOrder.comparator)
        assertEquals(expected, r.measurements())
        assertEquals(extra.filterIsInstance<TrustAssessment>(), r.trust())
        assertEquals(extra.filterIsInstance<PositionEstimate>(), r.estimates())
    }

    @Test
    fun `exporters produce output`() {
        val recs = DriveSimulator.generate(SimConfig()).records.take(5000)
        val gl = StringWriter().also { Exporters.gnssLogger(recs, it) }.toString()
        assertTrue(gl.lines().any { it.startsWith("Fix,GPS,") })
        assertTrue(gl.lines().any { it.startsWith("Status,") })
        val dir = Files.createTempDirectory("csv").toFile()
        val files = Exporters.csv(recs, dir)
        assertTrue(files.map(File::getName).containsAll(listOf("location.csv", "imu.csv")))
        dir.deleteRecursively()
    }
}
