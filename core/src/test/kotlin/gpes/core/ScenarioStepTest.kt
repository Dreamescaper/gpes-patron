package gpes.core

import gpes.core.model.VehicleSpeedMeasurement
import gpes.core.replay.SYNTHETIC_SOURCE
import gpes.core.replay.Scenario
import gpes.core.replay.ScenarioApplier
import gpes.core.replay.ScenarioStep
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScenarioStepTest {
    @Test
    fun `drop_vehicle_speed removes recorded OBD but keeps synthetic speed`() {
        val a = ScenarioApplier(Scenario("x", steps = listOf(ScenarioStep.DropVehicleSpeed())), 0)
        assertNull(a.apply(VehicleSpeedMeasurement(1_000_000_000, 10.0, 0.3, "elm327")))
        assertNotNull(a.apply(VehicleSpeedMeasurement(1_000_000_000, 10.0, 0.3, SYNTHETIC_SOURCE)))
    }
}
