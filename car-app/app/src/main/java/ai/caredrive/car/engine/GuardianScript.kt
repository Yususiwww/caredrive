package ai.caredrive.car.engine

import ai.caredrive.car.model.Reason
import java.util.Locale

/**
 * The text the phone sends to the guardian with a `call` (relay field `script`). It gives the guardian
 * what 119 needs, ready to read out: CareDrive cannot call 119 itself (a normal app only opens the dialer).
 * [VEHICLE_PLACEHOLDER] is filled in by the phone from the car the user registered.
 */
object GuardianScript {
    const val VEHICLE_PLACEHOLDER = "[차종·색]"

    fun build(
        stage: String,
        reason: Reason,
        lat: Double?,
        lon: Double?,
        headingDeg: Double? = null,
        address: String? = null,
        /** Nearby landmark, e.g. the nearest rest area: "성남졸음쉼터" and its distance in km. */
        near: Pair<String, Double>? = null,
        lastHr: Int? = null,
    ): String {
        val what = when (reason) {
            Reason.NO_PULSE -> "워치에 맥박이 잡히지 않고"
            Reason.STATIONARY -> "차가 멈춘 채 핸들·브레이크 입력이 없고"
            Reason.IHRN_NEW, Reason.HR_HIGH, Reason.HR_LOW, Reason.HR_SUSTAINED -> "심장 이상 신호 뒤"
            else -> ""
        }
        val head = when (stage) {
            "pre_alert" -> "[케어드라이브] 미리 알림: ${what} 운전자가 대답하지 않아요. 3분이 되면 전화드려요.".replace("  ", " ")
            "cancel" -> "[케어드라이브] 운전자가 괜찮다고 답했어요. 연락을 취소해요."
            else -> "[케어드라이브] ${what} 운전자 무응답 3분.".replace("  ", " ")
        }
        if (stage == "cancel") return head
        val coords = if (lat != null && lon != null) String.format(Locale.US, "%.5f, %.5f", lat, lon) else null
        val where = listOfNotNull(address?.takeIf { it.isNotBlank() }, coords).joinToString(" (") +
            (if (address?.isNotBlank() == true && coords != null) ")" else "")
        val parts = listOfNotNull(
            "위치: ${where.ifBlank { "확인 중" }}",
            headingDeg?.let { "${direction(it)} 방향으로 가던 중" },
            near?.let { (name, km) -> String.format(Locale.KOREA, "%s에서 약 %.1fkm", name, km) },
            "차량: ${VEHICLE_PLACEHOLDER}",
            lastHr?.let { "마지막 심박 $it bpm" },
        )
        val landmark = near?.first ?: address?.takeIf { it.isNotBlank() } ?: "지금 위치"
        val say = "'$landmark 근처 도로에서 운전자가 의식이 없는 것 같아요. 위치는 ${where.ifBlank { "문자로 받은 곳" }}입니다. 차는 ${VEHICLE_PLACEHOLDER}입니다.'"
        return "$head ${parts.joinToString(" · ")}. 119에 이렇게 말해 주세요: $say"
    }

    /** 8-point compass in Korean, 0° = north. */
    fun direction(deg: Double): String {
        val names = listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
        return names[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8] + "쪽"
    }
}
