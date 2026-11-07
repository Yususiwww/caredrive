# 폰 ↔ 차 메시지 형식 (relay)

중계 서버(`relay/server.js`)는 폰 1대와 차 1대를 WebSocket으로 잇는다. 모든 메시지는 JSON이고 `type`으로 구분한다. 허용되지 않은 `type`은 서버가 버린다.

| 연결 | 주소 |
|---|---|
| 폰 / 폰 시뮬레이터 | `ws://<맥>:8787/phone` (시뮬레이터 페이지: `http://localhost:8787/`) |
| 차 (Pleos 에뮬레이터) | `ws://10.0.2.2:8787/car` |

## 폰 → 차

| type | 필드 | 뜻 |
|---|---|---|
| `condition` | `condition: { sleepMinutes, sleepApnea, energyScore, recentIhrn, restingHr, exerciseEndedMinAgo, nightSpo2Min, skinTempDeltaC, systolicBp, baselineRmssd, mood }` | 출발 전 컨디션 요약 (삼성 헬스 + 워치 앱 + 직접 입력). 없는 값은 `null`. `mood`는 `GOOD / OK / TIRED / STRESSED`이며, `null`이면 차 화면에서 고른 값을 유지 |
| `live` | `hr`, `glucose` (mg/dL 또는 `null`), `rmssd` (ms 또는 `null`) | 실시간 값. 약 1초마다. `rmssd`는 워치 앱이 박동 간격으로 계산한 심박변이도이고, 차가 평소 값과 비교해 스트레스(0–100)를 계산 |
| `ihrn` | – | 새 부정맥 알림 도착 |
| `drive` | `driving: true / false / null` | 시연용 주행 상태 덮어쓰기. `null`이면 실제 기어 사용 |
| `advance` | `minutes` | 시연용: 시간을 앞으로 감기 |
| `scenario` | – | 시연용: 자동 시나리오 재생 |

## 차 → 폰

| type | 필드 |
|---|---|
| `status` | `level`(L0_NORMAL…L3_STOPPED), `reason`, `parked`, `drivingMin`, `nextBreakInMin`, `grade`, `stress`, `mood`, `lastEvent` |

## 서버 → 양쪽

| type | 필드 |
|---|---|
| `peers` | `phone: bool`, `car: bool` |

- 서버는 마지막 `condition`과 `live`를 기억했다가 차가 (재)연결하면 다시 보낸다.
- 프로토타입에서는 판정 엔진이 차 앱에 있어서 폰이 컨디션 요약과 심박 숫자를 보낸다. 원본 건강 기록(수면 단계, 측정 이력)은 보내지 않는다. 실서비스에서는 엔진을 폰으로 옮기고 차에는 단계와 이유만 보내는 방안을 검토한다.
