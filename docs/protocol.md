# 폰 ↔ 차 메시지 형식 (relay)

중계 서버(`relay/server.js`)는 폰 1대와 차 1대를 WebSocket으로 잇는다. 모든 메시지는 JSON이고 `type`으로 구분한다. 허용되지 않은 `type`은 서버가 버린다.

| 연결 | 주소 |
|---|---|
| 폰 / 폰 시뮬레이터 | `ws://<맥>:8787/phone` (시뮬레이터 페이지: `http://localhost:8787/`) |
| 차 (Pleos 에뮬레이터) | `ws://10.0.2.2:8787/car` |

## 폰 → 차

| type | 필드 | 뜻 |
|---|---|---|
| `condition` | `condition: { sleepMinutes, sleepApnea, energyScore, recentIhrn, restingHr, exerciseEndedMinAgo, nightSpo2Min, skinTempDeltaC, systolicBp, baselineRmssd, mood, places }` | 출발 전 컨디션 요약 (삼성 헬스 + 워치 앱 + 직접 입력). 없는 값은 `null`. `mood`는 `GOOD / OK / TIRED / STRESSED`이며, `null`이면 차 화면에서 고른 값을 유지. `places`(선택): `[{name, lat, lon}]` 집·회사 같은 등록 장소, 반경 300m 안에서는 정체 확인을 하지 않는다 |
| `live` | `hr`, `glucose` (mg/dL 또는 `null`), `rmssd` (ms 또는 `null`) | 실시간 값. 약 1초마다. `rmssd`는 워치 앱이 박동 간격으로 계산한 심박변이도이고, 차가 평소 값과 비교해 스트레스(0–100)를 계산 |
| `ihrn` | – | 새 부정맥 알림 도착 |
| `drive` | `driving: true / false / null` | 시연용 주행 상태 덮어쓰기. `null`이면 실제 기어 사용 |
| `advance` | `minutes` | 시연용: 시간을 앞으로 감기 |
| `scenario` | – | 시연용: 자동 시나리오 재생 |
| `pulse` | `state`: `present` / `absent` / `offwrist` / `weak` | 워치 맥박 상태. `absent`(착용 중인데 맥박이 안 잡힘)만 질문과 3분 타이머를 시작한다. `offwrist`(벗음)·`weak`(신호 약함)는 말만 하고 단계를 올리지 않는다. 같은 값이 반복되면 무시 |
| `drowsy` | `steeringIdleSec`, `hrDrop`, 또는 `clear: true` | **시연용 주입.** 실차에서는 차 핸들 신호와 워치 심박으로 계산한다. `steeringIdleSec` 10 이상 = "조향 없음 뒤 큰 수정" 1회(5분 유지), `hrDrop` 8 이상 = 안정 심박보다 낮은 상태가 지속됨 |
| `speed` | `x` (1–60) | **시연용** 시계 빠르기. 3분 타이머를 짧게 보여 줄 때 `10` |
| `answer` | `ask`, `yes` | **시연용**: 폰에서 차 질문에 대신 답하기 (차 버튼과 같은 처리) |

## 차 → 폰

| type | 필드 |
|---|---|
| `status` | `level`(L0_NORMAL…L3_STOPPED), `reason`, `parked`, `drivingMin`, `nextBreakInMin`, `grade`, `stress`, `mood`, `lastEvent` |
| `call` | `to`(배열), `reason`, `stage`, `location`(`{lat, lon}`, 알 때만), `script` | 폰에 연락을 요청한다. 실제 전화·문자는 폰이 한다. 아래 표. `reason`에 `STATIONARY`(정차 중 응답 없음)가 더해졌다 |
| `play` | `playlist`: `"calm"` | 운전자가 긴장 대화에서 "좀 그래요"를 누른 뒤에만 보낸다. 폰이 정해 둔 플레이리스트를 연다(자동 재생 없음) |
| `ask` | `ask`: `CARDIAC_CHECK` / `NO_PULSE` / `STRESS_TALK` / `DROWSY` / `STATIONARY`, `answer`: `null`(묻는 중) / `help` / `ok` / `none`(무응답) / `rest` / `play` / `pulse_back` / `driver_input`(무맥박 중 핸들·브레이크 입력, 타이머 멈춤) / `moved`(정체 확인 중 다시 출발) / `none_moving`(심장 확인 뒤 주행 중 60초 대답·입력 없음, 연락 단계 시작) | 폰의 이상 기록용. 대답 내용만 남기고 분석하지 않는다 |

`status`에는 다음도 들어간다: `ask`, `secondsLeft`, `helpRequested`, `contacting`, `nowPlaying`, `drowsiness`, `speedKmh`, `hazardOn`, `brakeOn`, `pulse`.

### `call` 단계

| `stage` | `to` | 언제 | 폰이 하는 일 |
|---|---|---|---|
| `pre_alert` | `["contacts"]` | 무응답 단계(맥박 없음, 정체 확인 30초 무응답, 심장 확인 뒤 주행 중 60초 무응답·무입력): 차량 입력이 전혀 없으면 30초, 있었으면 60초 | 보호자에게 위치가 담긴 미리 알림 문자 |
| `call` | `["contacts", "119_dial"]` | 무응답 단계 180초, 또는 "도움이 필요해요"(30초 무응답 포함) 뒤 기어 P | 보호자에게 전화(`CALL_PHONE`), **119는 다이얼러에 번호를 채워 띄움**(일반 앱은 긴급번호를 바로 걸 수 없다). 119·112 자동 문자는 사용자가 미리 동의한 경우만, 시연에서는 끔 |
| `call` | `["119_dial"]` | 정차 후 화면의 119 버튼(10초 카운트다운, 취소 가능) | 119 다이얼러 |
| `cancel` | 앞과 같음 | 운전자가 "괜찮아요"(미리 알림 뒤) 또는 연락 중 화면의 취소 | 보호자에게 괜찮다고 알림 |

**`script`**: 보호자에게 보내는 문자 본문(차가 만든다). 119에 그대로 읽어 줄 대본이 들어 있다. 예:
`[케어드라이브] 워치에 맥박이 잡히지 않고 운전자 무응답 3분. 위치: 경기 성남시 분당구 (37.41272, 127.09468) · 북동쪽 방향으로 가던 중 · 성남졸음쉼터에서 약 3.6km · 차량: [차종·색] · 마지막 심박 52 bpm. 119에 이렇게 말해 주세요: '성남졸음쉼터 근처 도로에서 운전자가 의식이 없는 것 같아요. 위치는 …입니다. 차는 [차종·색]입니다.'`
- 위치는 차의 현재 위치(내비 `CurrentLocationInfo`: 주소, 위도·경도, 진행 방향)이고, 모르는 값은 빠진다.
- `[차종·색]`은 폰이 사용자가 등록한 차로 바꿔 넣는다. `pre_alert`는 미리 알림 문구, `cancel`은 "괜찮다고 답했어요" 한 줄.

시연(폰 시뮬레이터)은 실제로 걸거나 보내지 않고 "보호자 엄마에게 전화 거는 중 · 119 다이얼러 준비됨(누르면 연결)"을 보여 준다.

## 서버 → 양쪽

| type | 필드 |
|---|---|
| `peers` | `phone: bool`, `car: bool` |

- 서버는 마지막 `condition`과 `live`를 기억했다가 차가 (재)연결하면 다시 보낸다.
- 프로토타입에서는 판정 엔진이 차 앱에 있어서 폰이 컨디션 요약과 심박 숫자를 보낸다. 원본 건강 기록(수면 단계, 측정 이력)은 보내지 않는다. 실서비스에서는 엔진을 폰으로 옮기고 차에는 단계와 이유만 보내는 방안을 검토한다.

## 데이터 끊김
- 폰이 `live`를 한 번이라도 보낸 뒤 15초 넘게 안 오면 차는 "워치 값이 안 들어와요", 폰 연결이 끊기면 "워치 연결이 끊겼어요"를 띄운다.
- 이때 심박 숫자는 흐리게 보이고, 오래된 값으로는 경고를 내지 않는다.
