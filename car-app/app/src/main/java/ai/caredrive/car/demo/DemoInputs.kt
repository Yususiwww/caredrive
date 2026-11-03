package ai.caredrive.car.demo

import ai.caredrive.car.engine.DriverCondition

/**
 * Simulated phone/watch data for development and the demo video (step 3).
 * Step 4 replaces this with data from the relay server.
 */
data class DemoInputs(
    val condition: DriverCondition = DriverCondition(),
    val heartRate: Int? = 72,
    val glucose: Int? = null,
    /** null = use the real gear from the car. */
    val drivingOverride: Boolean? = null,
)

/** One step of the auto-play scenario: at [atMin] simulated minutes apply [change]. */
data class ScenarioStep(val atMin: Int, val label: String, val ihrn: Boolean = false, val change: (DemoInputs) -> DemoInputs)

object DemoScenario {
    /** Short sleep → hourly breaks → break due → rising heart rate → IHRN → stop. Runs at 60x. */
    val steps = listOf(
        ScenarioStep(0, "출발 전 · 수면 4시간 40분") {
            DemoInputs(DriverCondition(sleepMinutes = 280, energyScore = 45, restingHr = 68), heartRate = 72, drivingOverride = false)
        },
        ScenarioStep(1, "주행 시작") { it.copy(drivingOverride = true, heartRate = 76) },
        ScenarioStep(60, "운전 1시간") { it.copy(heartRate = 80) },
        ScenarioStep(66, "심박 상승") { it.copy(heartRate = 104) },
        ScenarioStep(74, "부정맥 알림 도착", ihrn = true) { it.copy(heartRate = 98) },
    )
    const val SPEED = 60
}
