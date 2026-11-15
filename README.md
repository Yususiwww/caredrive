# 케어드라이브 (CareDrive)

갤럭시 워치가 아는 **몸 상태**(수면·심박·부정맥 알림·맥박)와 차가 아는 **운전 상태**(기어·속도·핸들·브레이크)를 합쳐, 출발 전 휴식 간격을 정하고 운전 중 이상이 보이면 차가 먼저 묻고 돕는 Pleos 앱. 현대자동차그룹 차량용 앱 개발 공모전(Pleos) 출품작.

> 현재 워치·폰 연동은 웹 폰 시뮬레이터(`phone-sim/`)로 대신합니다. 시연의 건강 데이터는 시뮬레이터로 넣은 시험 값이며, 실제 워치·폰 앱은 2차 발표 전까지 구현할 예정입니다.

**주요 흐름**
- **심장 이상:** "심장 박동이 평소와 달라요. 괜찮으세요?"라고 묻는다. 도움 요청이나 30초 무응답이면 비상등과 정차를 안내하고, 실내 조명을 빨갛게 바꾸고, 가까운 쉼터를 경유지로 넣는다. 정차하면 보호자에게 전화하고 119 연결을 준비한다.
- **맥박 없음:** 바로 묻는다. 핸들·브레이크 입력이 없으면 30초(입력이 있었으면 1분)에 보호자에게 미리 알리고, 3분 동안 대답이 없으면 보호자에게 자동으로 전화하고 위치 문자를 보낸다. 119는 다이얼러에 번호를 채워 띄운다(일반 앱은 긴급번호로 자동 발신할 수 없다).
- **긴장:** "힘든 일이 있었나요?"라고 묻고, "좀 그래요"라고 답하면 폰이 미리 정한 플레이리스트를 연다.
- **졸음:** 수면, 운전 시간, 시간대, 워치 심박·심박변이도, **핸들 각도 패턴**을 합친 점수로 판단해 휴식이나 졸음쉼터를 안내한다.
- **시연 영상:** [GitHub Release v1.0.0](https://github.com/Yususiwww/caredrive/releases/tag/v1.0.0)의 `caredrive-demo.mp4`(2분 54초, 1080p, 소리 포함)와 `caredrive-demo-720p.mp4`. 에뮬레이터 화면과 소리를 그대로 녹화했고, 중간중간 들어간 AI 연출 장면에는 모두 "연출 장면 · AI 생성"이라고 표기했다.

> 케어드라이브는 진단하지 않습니다. 삼성 헬스 모니터가 보낸 알림과 일반 심박 정보를 바탕으로 "쉬어 가세요"를 안내합니다.

## 공모전 제출 정보

| 항목 | 내용 |
|---|---|
| 앱 이름 | **케어드라이브 (CareDrive)** |
| 한 줄 소개 | 갤럭시 워치가 아는 운전자 컨디션으로 출발 전 휴식 간격을 정하고, 운전 중 이상 징후가 보이면 차가 먼저 쉬어 가자고 말하는 Pleos 앱 |
| 팀 | Twelveone |
| APK | [`apk/Twelveone_CareDrive_release.apk`](apk/Twelveone_CareDrive_release.apk) (release, 로컬 디버그 키로 서명, x86_64 · arm64-v8a) |
| 개발 에뮬레이터 | Pleos Connect Emulator 2.1.0 (API 34, AVD `Pleos_Connect`), Android Emulator 37.2.12 |
| 화면 규격 | 2560×1440 가로 화면. 앱은 오른쪽 앱 영역(넓은 창)과 반으로 나뉜 좁은 창 모두 지원 |
| 언어·도구 | Kotlin 2.4 · Jetpack Compose · AGP 9.4 · compileSdk 37 / targetSdk 34 / minSdk 28, 중계 서버 Node.js 22 |

### 빠른 실행

```bash
# 1) Pleos Connect 에뮬레이터 실행 후 APK 설치
adb install -r -g apk/Twelveone_CareDrive_release.apk
adb shell am start -n ai.caredrive.car/.MainActivity

# 2) (선택) 폰 시뮬레이터와 연결해 실시간 데이터로 시연
cd relay && npm install && npm start      # 맥에서 http://localhost:8787/ 열기
```

- 중계 서버 주소는 에뮬레이터 기준 `ws://10.0.2.2:8787/car`로 고정되어 있다. 중계 서버 없이 실행하면 "폰 연결 안 됨"으로 표시되고 화면은 정상 동작한다.
- 시연용 상황 버튼(출발 전·주행·저혈당·부정맥 등)은 디버그 빌드에만 있다: `cd car-app && ./gradlew :app:installDebug`
- 공조 제어를 보려면 에뮬레이터 Virtual Console에서 공조 값을 한 번 publish해야 한다(아래 "에뮬레이터 주의" 참고).
- 테스트: `cd car-app && ./gradlew :app:testDebugUnitTest`, `cd relay && npm test`

## 구성

| 폴더 | 내용 | 상태 |
|---|---|---|
| `car-app/` | Pleos 차량 앱 (AAOS · Kotlin · Compose) | 동작 (`./gradlew :app:testDebugUnitTest`, 단위 테스트 85개) |
| `relay/` | 폰 ↔ 차 중계 서버 (Node.js · WebSocket) | 동작 (`npm test`) |
| `phone-sim/` | 폰 시뮬레이터 웹페이지 (컨디션·심박·혈당·부정맥 알림·맥박 없음·졸음 신호 입력, 이상 기록, 연락·음악 표시) | 동작 |
| `docs/decisions/` | 결정 기록 | |
| `docs/demo/` | 시연 영상 제작 계획·AI 연출 장면 규칙과 점검 이미지 (영상 파일은 Release에 있음) | |
| `docs/design/` | 화면 시안 (계기판형, 넓은 창·좁은 창) | |
| `docs/research/` | 조사 자료 | |

## 판정 로직 (`car-app/.../engine/`)

**출발 전 컨디션** (`ConditionRules`): 수면 5시간 미만·수면무호흡 기록·최근 부정맥 알림·에너지 점수 40 미만이면 "주의"(1시간마다 휴식), 수면 6시간 30분 미만·에너지 60 미만이면 "보통"(1시간 30분), 그 외 "좋음"(2시간). 데이터가 없으면 나쁘게 보지 않는다. 최근 30분 안 운동은 참고 정보(심박 기준 +10).

**운전 중 단계** (`RiskEngine`):

| 단계 | 조건 (설계 예시) | 차량 대응 |
|---|---|---|
| L0 정상 | 아래에 해당 없음 | 상태 표시만 |
| L1 주의 | 휴식 시간 도달 · 심박이 안정 심박 +30(최근 부정맥 알림 시 +20) 이상 5분 지속 | 음성 안내, 공조 시원하게(최저 20℃) |
| L2 경고 | 새 부정맥 알림 · 혈당 70 미만 · 심박 <40 또는 >140 · 휴식 30분 초과 · L1이 15분 지속 | 화면 + 음성으로 휴게소 안내 제안 |
| L3 정차 후 | L2 이후 정차 | 이유별 안내(심전도·당 보충·스트레칭), 보호자 알림, 119 |

L1에는 졸음 점수 40 이상, L2에는 졸음 점수 60 이상(가까운 졸음쉼터를 바로 경유지로)이 더해졌다.

"다음에"는 같은 이유를 15분 조용히 하고, 10분 이상 정차하면 연속 운전 시간이 초기화된다. 숫자는 모두 설계 예시이며 의료 자문이 필요하다.

**차가 묻고 답에 따라 움직이기** (`CheckinEngine`, 설계: [docs/decisions/2026-11-15-응급-대화-졸음-설계.md](docs/decisions/2026-11-15-응급-대화-졸음-설계.md)). 질문은 음성과 화면이 같은 문장이고, 답은 큰 버튼 2개로 받는다.

| 질문 | 언제 | 답과 동작 |
|---|---|---|
| 심장 확인 "심장 박동이 평소와 달라요. 괜찮으세요?" | 주행 중 L2 심장 이유(부정맥 알림, 심박 <40·>140, 높은 심박 지속) | **도움이 필요해요** 또는 30초 무응답 → "비상등을 켜고 세워 주세요", 실내 조명 빨강, 가까운 휴게소 경유지 → 기어 P가 되면 폰에 연락 요청(보호자 자동 전화 · 119 연결 준비), 실내등 켜기. **계속 달리는 중**이어도 그 뒤 60초 동안 대답도 입력(브레이크, 핸들 2° 넘게)도 없으면 무맥박과 같은 단계(다시 묻기 → 보호자 미리 알림 → 180초 보호자 전화 · 119 연결 준비)를 밟고, 어떤 대답이나 입력이든 멈춘다. **괜찮아요** → 기존 L2 흐름, 15분 다시 묻지 않음 |
| 맥박 없음 "괜찮으세요? 대답해 주세요." | 워치 착용 중 맥박이 안 잡힘(`pulse: absent`) | 경고음 3번, 실내 조명 빨강, 3분 원형 타이머. 미리 알림 전까지 20초마다 다시 물음. **차량 신호로 보정:** 최근 20초 안에 핸들(3° 이상)이나 브레이크 입력이 있으면 워치 센서 문제일 수 있다고 보고 타이머를 멈추고(취소 아님) "운전 입력이 있어요. 워치 신호 문제일 수 있어요…"를 한 번 말한다. 입력이 전혀 없으면 보호자 미리 알림을 30초로 앞당기고, 입력이 있었으면 60초. 180초(입력 없던 시간만 셈): 보호자 전화 · 119 다이얼러 준비, 정차 중이면 실내등. **괜찮아요**는 언제든 취소, 맥박이 돌아오면 "맥박이 다시 잡혀요" |
| (질문 없음) | 워치 벗음 / 신호 약함 | "워치를 다시 차 주세요" / "워치 신호가 약해요"만 말하고 단계를 올리지 않음(오탐 방지) |
| 긴장 대화 "많이 긴장하신 것 같아요. 힘든 일이 있었나요?" | 주행 중 L1 심박 지속 상승 또는 HRV 스트레스, 다른 경고 없음 | **좀 그래요** → 폰이 플레이리스트를 연다("♪ 편안한 플레이리스트 재생 중"), 공조 시원하게 유지. **괜찮아요** → 호흡 안내만. 어느 쪽이든 15분 다시 묻지 않음. 음악은 묻지 않고 틀지 않는다 |
| 졸음 "졸음이 올 수 있어요. 잠깐 쉬어 갈까요?" | 졸음 점수 40 이상(L1) | 공조 시원하게. **쉬어 갈게요** → 가까운 쉼터 경유지. **괜찮아요** → 15분 조용히 |
| 정체 확인 "정체 구간인가 봐요. 노래 틀어 드릴까요?" | 기어 D·R·N, 속도 0이 120초, 최근 60초 브레이크·핸들(2° 넘게) 입력 없음, 운전석 착석·문 안 열림·벨트 안 풂(`Seat`, `Door`), 등록 장소(집·회사) 300m 밖, 최근 5분 최고 속도 30km/h 이상 | **틀어 줘** → 플레이리스트. **괜찮아** → 아무것도 안 함. 답하거나 다시 출발하면 2시간 묻지 않음. **30초 무응답·무입력** → 무맥박과 같은 단계(경고음 3번, 실내 조명 빨강, 보호자 미리 알림, 180초 보호자 전화 · 119 연결 준비) |

연락할 때 약속하는 말은 항상 같다: 보호자에게는 **자동 전화 + 위치 문자**, 119는 **연결 준비**(폰 다이얼러에 번호를 채워 두고 누르면 연결). 일반 앱은 119를 직접 걸 수 없다. 보호자 문자에는 위치·진행 방향·가까운 휴게소·차종·마지막 심박과 "119에 이렇게 말해 주세요" 대본이 들어간다(`call.script`). 차 음성은 "119"를 "일일구"로 읽는다(화면 글자는 그대로).

**졸음 점수** (`Drowsiness`, 0–100, 설계 예시): 수면 5시간 미만 +25 / 6시간 30분 미만 +10, 오늘 휴식 간격의 80% 이상 연속 운전 +15, 02–06시 +20 / 14–16시 +10(차 시계), 안정 심박보다 8bpm 이상 낮은 상태 3분 +20, RMSSD가 평소보다 30% 높은 상태 3분 +10, 핸들 +20. 핸들(`SteeringMonitor`, Pleos `SteeringWheel` 각도)은 10초 넘게 조향이 없다가 1.5초 안에 10° 이상 수정 조향이 나오거나(5분간 유지), 1분 반전 횟수(2° 이상)가 평소의 절반 아래로 떨어지면 점수를 더한다. 수면·시간처럼 실시간이 아닌 값만으로는 알리지 않는다(휴식 간격만 앞당김).

테스트: `./gradlew :app:testDebugUnitTest` (85개: 단계·질문 타이밍·연락 단계·주행 중 연락·정체 확인 조건·졸음 점수·핸들 분석·보호자 문자·문구).

## 폰 시뮬레이터로 시연 (4단계)

```bash
cd relay && npm install && npm start
```

1. Pleos 에뮬레이터에서 차 앱 실행 → 자동으로 `ws://10.0.2.2:8787/car`에 연결 (3초마다 재시도)
2. 맥 브라우저에서 http://localhost:8787/ 열기 → 컨디션·심박·혈당 슬라이더, 부정맥 알림, 주행/정차, 시간 감기, 시나리오
3. 차 화면 상태가 폰 페이지 "차 화면 상태"에 실시간으로 보인다

메시지 형식: [docs/protocol.md](docs/protocol.md)

**휴게소 미리보기 지도**: 에뮬레이터 내장 내비는 지도 스타일이 비어 있어(`connect_empty`, 바탕·해안선만) 경로선만 보인다. 그래서 경고(L2) 화면에 우리 앱의 지도를 넣었다. MapLibre Android 13.7.1 + OpenFreeMap(OpenStreetMap 데이터, API 키 없음), 내 차(파랑)와 가장 가까운 휴게소·졸음쉼터(주황)를 한 화면에 맞춘다. 조작·애니메이션 없음, 지명은 한국어, 도로명·도로번호 라벨은 숨김(일부 한글 글리프 누락). 출처 표기: © OpenFreeMap © OpenMapTiles © OpenStreetMap.

**심박 추이 차트**: 실제 심박 숫자(5초 간격, 최근 30분)를 선으로 그린다. 세로축은 보이는 값에 맞춰 자동으로 늘어나고(최소 24bpm 폭), BPM 옆에는 정적인 심박 아이콘이 있다. 폰 시뮬레이터와 폰 없는 데모 모드는 실제처럼 심박이 천천히 흔들리고 호흡 주기(약 5초)로 오르내린다. 파형이 아니고 애니메이션도 없다(Pleos 운전 중 디자인 원칙). 주행 중에는 상태 패널에 작게, 정차 중에는 크게 보이며 안정 심박(회색 점선)과 주의 기준(노란 점선)을 함께 표시한다.

## 시연 영상 만들기

중계 서버와 에뮬레이터(차 앱)가 켜진 상태에서:

```bash
cd relay
node demo/record.mjs /tmp/caredrive-demo     # 폰 페이지(헤드리스 Chrome) + 차 화면(scrcpy) 녹화, 약 2분
python3 demo/compose.py /tmp/caredrive-demo  # 나란히 합치고 장면 제목·차 음성 자막 → caredrive-demo.mp4
```

- 장면: 1 출발 전 컨디션 → 2 주행(실제 기어 D, 속도 0→60→80) → 3 긴장 대화 → '좀 그래요' → 플레이리스트 → 4 졸음(실제 핸들 신호: 조향 없음 뒤 큰 수정) → '쉬어 갈게요' → 5 정체 확인(기어 D로 속도 0, 입력 없음 2분을 20배 빠르게 → 질문하면 차가 시계를 실제 속도로 되돌림 → '괜찮아' → 다시 출발) → 6 부정맥 → 심장 확인 → '도움이 필요해요' → 비상등 표시등 · 감속 · 기어 P → 연락 → 7 맥박 없음(정차, 핸들·브레이크 입력 없음) → 30초 미리 알림 → 180초 연락 (3분 타이머를 10배 빠르게, 화면 설명에 표시).
- 녹화 시작 때 Virtual Console로 공조를 22℃·풍량 3으로 맞춘다(`PUT /vehicle/hvac?power=true&driver_temp=22&passenger_temp=22&fan_speed=3`). 그래서 긴장·졸음 장면에서 차가 낮춘 온도(20℃)가 Pleos 하단 바에 보인다.
- 휴게소·쉼터 경유지를 넣은 뒤(졸음 '쉬어 갈게요', 심장 '도움이 필요해요') Pleos 내비(`ai.umos.maps.android.navigation.app/.MainActivity`)를 3초 앞에 띄워 경로를 보여 주고 케어드라이브로 돌아온다.
- `timeline.json`의 `events`: 녹화 중 Virtual Console 차량 상태(`GET /vehicle/state`)를 0.5초마다 읽어 차 앱이 바꾼 것(실내 조명 빨강 `#ff0000`, 실내등, 운전석 온도, 풍량)과 내비 장면을 시각과 함께 남긴다. 영상의 작은 표시에 쓴다.
- 기어·속도·브레이크·핸들은 Virtual Console로 넣은 **차량 신호**라 Pleos 계기판(왼쪽 화면)의 기어·속도도 같이 바뀐다. 부정맥 알림·심박·맥박 없음은 폰 시뮬레이터 값이다.
- 졸음 장면은 에뮬레이터 시계가 02–06시나 14–16시면 시간대 점수가 더해져 바로 L2(질문 없이 졸음쉼터)로 간다. 그 시간대에는 피해서 녹화한다.
- 녹화 전 브라우저에 열린 폰 시뮬레이터 탭은 닫는다(중계 서버는 폰 1대만 연결).
- 영상에는 차의 실제 소리(경고음·음성 안내)가 들어간다(scrcpy `--audio-source=playback`, 에뮬레이터를 `-no-audio` 없이 실행). 차가 말한 문장은 자막으로도 들어간다.

## 데모 모드 (디버그 빌드)

화면 아래 버튼 또는 adb로 상황을 넣는다.

```bash
adb shell am broadcast -a ai.caredrive.car.DEMO -p ai.caredrive.car --es preset scenario
```

- preset: `predrive` `drive` `break` `tense` `stress` `glucose` `ihrn` `park` `nodata`(워치 데이터 끊김) `scenario`
- 질문 화면 preset(실제 엔진으로 흐른다): `cardiac`(심장 확인) `nopulse`(맥박 없음 3분 타이머) `stresstalk`(긴장 대화) `drowsy`(졸음 질문) `stationary`(정체 확인: 50km/h에서 멈춘 지 115초로 앞당김) `contacting`(도움 요청 후 정차 → 연락 중)
- 질문 답하기: `--es answer yes|no` · 맥박 상태: `--es pulse absent|present|offwrist|weak` · 연락 취소: `--ez cancelContact true` · 시계 빠르기: `--ei speed 10`
- 119 카운트다운 보기: `--ez call119 true` (10초 뒤 폰에 119 연결 화면을 열도록 요청, 화면에서 취소 가능)
- 좁은 창(Pleos 화면 분할) 배치 보기: `--ez compact true` (되돌리기: `--ez compact false`)
- 개별 값: `--ei hr 110` `--ei glucose 62` `--ez ihrn true` `--ez drive true` `--ei advance 30`(분)
- `scenario`: 수면 부족 출발 → 1시간 휴식 알림 → 심박 상승 → 부정맥 알림을 60배속으로 약 75초에 재생

## 실행 (차량 앱)

```bash
# Pleos Connect Emulator (AVD: Pleos_Connect) 실행 후
cd car-app
./gradlew :app:installDebug
adb shell am start -n ai.caredrive.car/.MainActivity
```

글꼴: Pleos 시스템 글꼴 Asta Sans(SIL OFL 1.1, `car-app/third_party/AstaSans-OFL.txt`)를 앱에 넣어 쓴다.

JDK 17 필요 (Android Studio 내장 JBR 사용 가능). 디버그 빌드 하단의 DEMO 버튼으로 단계 화면을 바꿔 볼 수 있습니다.

## 차량 기능 (2단계)

| 기능 | Pleos API | 에뮬레이터 확인 |
|---|---|---|
| 음성 안내 | TextToSpeech `ON_DEVICE`, ko-KR | 확인 |
| 공조 시원하게 | Vehicle HVAC: 전원 켜기 → 운전석 온도 −2℃, 풍량 +2 | 확인 (22→20℃, 3→5단) |
| 휴게소 안내 | NaviHelper: 현재 위치 → 가장 가까운 휴게소·졸음쉼터 → 목적지 또는 경유지 | 확인 (판교 → 성남졸음쉼터 3.6km) |
| 주행/정차 | Vehicle Gear 콜백 (경고 중 P → 정차 후 조치) | 확인 |
| 속도 | AAOS `CarPropertyManager` `PERF_VEHICLE_SPEED` (Pleos SDK에 속도 API 없음, `CAR_SPEED` 권한) | 확인 (0→60→80km/h) |
| 브레이크 | Vehicle Brake `registerBrakePedalStatus` | 확인 |
| 핸들 각도 (졸음) | Vehicle SteeringWheel `registerSteeringWheelAngle` | 확인 (부호가 콘솔과 반대) |
| 실내 조명 빨강 / 복원 | Vehicle Light `setAmbientLightBrightnessColor` | 확인 (콘솔 앰비언트 `#ff0000`) |
| 실내등 | Vehicle Light `setCabinLightSwitch` | 확인 (무맥박 180초, 정차 중) |
| 운전석 착석 / 안전벨트 / 운전석 문 (정체 확인) | Vehicle Seat `registerSeatOccupancy`, `registerSeatBeltBuckledState`, Door `registerDoorPosition` | 착석·문 확인. 에뮬레이터는 착석 값이 **반대로** 온다(콘솔 Empty → 앱 occupied, 이벤트·조회 모두). 벨트는 콘솔에 조작이 없어 항상 '안 맴'이라, 한 번도 '맴'이 오지 않은 경우 벨트는 조건에서 뺀다 |
| 방향지시등 / 비상등 | Vehicle TurnSignal(OFF·LEFT·RIGHT만, 비상등 값 없음), AAOS `HAZARD_LIGHTS_STATE` | 미확인: 콘솔 telltale은 TurnSignal로 오지 않고, `CAR_EXTERIOR_LIGHTS`는 일반 앱에 허용되지 않음. 비상등은 앱이 켤 수 없어 "켜 주세요"라고 말한다 |

- 신호마다 따로 등록하고, 실패하면 화면 "최근 동작"에 "… 사용 불가"로 보인다(앱은 멈추지 않는다).
- 에뮬레이터에서 차량 신호 넣기: Virtual Console HTTP API. `adb shell cmd car_service inject-vhal-event`는 user 빌드라 막혀 있다(`requires non-user build`), `adb root`도 안 된다.

```bash
adb forward tcp:8088 tcp:8088
# 콘솔은 살아 있는 세션이 있어야 쓰기를 받는다(3초 타임아웃): 1초마다
curl -X POST 'http://127.0.0.1:8088/browser/session?client=demo'
curl -X PUT  'http://127.0.0.1:8088/vehicle/gear?gear=Drive'          # Park / Reverse / Neutral / Drive
curl -X PUT  'http://127.0.0.1:8088/vehicle/controls?speed=60&accelerator=30&brake=0&steering=0'   # km/h, %, %, 도
curl -X PUT  'http://127.0.0.1:8088/vehicle/telltales/simulator?type=TurnSignalLeft&state=Blink&color=Green'   # 계기판 표시등만
curl -X PUT  'http://127.0.0.1:8088/vehicle/hvac?power=true&driver_temp=22&passenger_temp=22&fan_speed=3'
curl -X PUT  'http://127.0.0.1:8088/vehicle/seat?kind=occupied&area=driver&value=true'       # 운전석 착석
curl -X PUT  'http://127.0.0.1:8088/vehicle/body?door_fl=false'                            # 운전석 문
curl         'http://127.0.0.1:8088/vehicle/state'                   # 현재 값(앰비언트 색, 실내등 포함)
```
- NaviHelper 3.0.5에는 장소 검색이 없어서, OpenStreetMap의 휴게소·졸음쉼터 1,216곳(`assets/rest_areas.json`, © OpenStreetMap contributors, ODbL)에서 진행 방향 ±70° 안의 가장 가까운 곳을 고른다.
- 에뮬레이터 주의: 공조 값은 Virtual Console(`adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088)에서 한 번 값을 바꿔 "publish"해야 앱에서 읽힌다. 그 전에는 `PropertyNotAvailableException`.
- 공조 영역은 차마다 다르다(전원·풍량은 1열 전체, 온도는 좌석별). 항상 capability로 받은 영역을 쓴다.

## 사용 SDK

- Pleos Connect SDK: `ai.pleos.playground:Vehicle:2.3.2`, `NaviHelper:3.0.5`, `TextToSpeech:3.3.1`
- 예정: Samsung Health Data SDK 1.1.0 (폰), Samsung Health Sensor SDK (워치, 필요 시)

## 출처와 라이선스

- 음악 (시연 영상)
  - Erik Satie, Gymnopédie No.1 · No.3: 퍼블릭 도메인 녹음(CC0), Wikimedia Commons
  - "Canon in D Major" Kevin MacLeod (incompetech.com), Licensed under Creative Commons: By Attribution 3.0 (http://creativecommons.org/licenses/by/3.0/)
- AI 연출 장면 (시연 영상): Google Gemini(Veo)로 생성. 실존 인물·브랜드 로고 없음. 영상 안에 "연출 장면 · AI 생성"으로 표기
- 내레이션: Microsoft Edge 음성 합성(ko-KR)
- 글꼴: Asta Sans, SIL Open Font License 1.1 ([사본](car-app/third_party/AstaSans-OFL.txt))
- 휴게소·졸음쉼터 위치: © OpenStreetMap contributors, ODbL
