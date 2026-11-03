package ai.caredrive.car.model

/** Risk level decided on the phone and shown in the car. See docs/설계서 §2. */
enum class Level { L0_NORMAL, L1_CAUTION, L2_WARNING, L3_STOPPED }

enum class Reason { NONE, HR_HIGH, HR_LOW, HR_SUSTAINED, IHRN_NEW }

data class CareState(
    val level: Level = Level.L0_NORMAL,
    val reason: Reason = Reason.NONE,
    val heartRate: Int? = null,
    /** A recent IHRN (irregular heart rhythm notification) was found before departure. */
    val cautionMode: Boolean = false,
    val connected: Boolean = false,
    /** True when the car is in P. Comes from the Pleos Gear API. */
    val parked: Boolean = true,
    /** Driver accepted the rest-stop suggestion and navigation is set. */
    val guidingToRest: Boolean = false,
    /** Last thing the car did (voice, HVAC, navigation), shown small on screen. */
    val lastEvent: String? = null,
)
