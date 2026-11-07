# 케어드라이브 (가칭)

갤럭시 워치가 아는 운전자 컨디션(수면·에너지 점수·심박·부정맥 알림·혈당)을 Pleos 차량 앱으로 연결해, 출발 전 휴식 간격을 정하고 운전 중 이상 징후가 보이면 휴식을 제안하는 앱. 현대자동차그룹 차량용 앱 개발 공모전(Pleos) 출품작.

> 케어드라이브는 진단하지 않습니다. 삼성 헬스 모니터가 보낸 알림과 일반 심박 정보를 바탕으로 "쉬어 가세요"를 안내합니다.

## 구성

| 폴더 | 내용 | 상태 |
|---|---|---|
| `car-app/` | Pleos 차량 앱 (AAOS · Kotlin · Compose) | 4단계: 중계 연결·심박 추이 차트 |
| `relay/` | 폰 ↔ 차 중계 서버 (Node.js · WebSocket) | 동작 (`npm test`) |
| `phone-sim/` | 폰 시뮬레이터 웹페이지 (컨디션·심박·혈당·부정맥 알림 입력) | 동작 |
| `docs/decisions/` | 결정 기록 | |

## 판정 로직 (`car-app/.../engine/`)

**출발 전 컨디션** (`ConditionRules`): 수면 5시간 미만·수면무호흡 기록·최근 부정맥 알림·에너지 점수 40 미만이면 "주의"(1시간마다 휴식), 수면 6시간 30분 미만·에너지 60 미만이면 "보통"(1시간 30분), 그 외 "좋음"(2시간). 데이터가 없으면 나쁘게 보지 않는다. 최근 30분 안 운동은 참고 정보(심박 기준 +10).

**운전 중 단계** (`RiskEngine`):

| 단계 | 조건 (설계 예시) | 차량 대응 |
|---|---|---|
| L0 정상 | 아래에 해당 없음 | 상태 표시만 |
| L1 주의 | 휴식 시간 도달 · 심박이 안정 심박 +30(최근 부정맥 알림 시 +20) 이상 5분 지속 | 음성 안내, 공조 시원하게(최저 20℃) |
| L2 경고 | 새 부정맥 알림 · 혈당 70 미만 · 심박 <40 또는 >140 · 휴식 30분 초과 · L1이 15분 지속 | 화면 + 음성으로 휴게소 안내 제안 |
| L3 정차 후 | L2 이후 정차 | 이유별 안내(심전도·당 보충·스트레칭), 보호자 알림, 119 |

"괜찮아"는 같은 이유를 30분 조용히 하고, 10분 이상 정차하면 연속 운전 시간이 초기화된다. 숫자는 모두 설계 예시이며 의료 자문이 필요하다. 테스트: `./gradlew :app:testDebugUnitTest` (13개).

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
node demo/record.mjs /tmp/caredrive-demo     # 폰 페이지(헤드리스 Chrome) + 차 화면(adb screenrecord) 녹화, 약 80초
python3 demo/compose.py /tmp/caredrive-demo  # 나란히 합치고 장면 제목·차 음성 자막 → caredrive-demo.mp4
```

- 녹화 전 브라우저에 열린 폰 시뮬레이터 탭은 닫는다(중계 서버는 폰 1대만 연결).
- 영상에 소리는 없고, 차가 말한 문장은 자막으로 들어간다.

## 데모 모드 (디버그 빌드)

화면 아래 버튼 또는 adb로 상황을 넣는다.

```bash
adb shell am broadcast -a ai.caredrive.car.DEMO -p ai.caredrive.car --es preset scenario
```

- preset: `predrive` `drive` `break` `tense` `stress` `glucose` `ihrn` `park` `scenario`
- 개별 값: `--ei hr 110` `--ei glucose 62` `--ez ihrn true` `--ez drive true` `--ei advance 30`(분)
- `scenario`: 수면 부족 출발 → 1시간 휴식 알림 → 심박 상승 → 부정맥 알림을 60배속으로 약 75초에 재생

## 실행 (차량 앱)

```bash
# Pleos Connect Emulator (AVD: Pleos_Connect) 실행 후
cd car-app
./gradlew :app:installDebug
adb shell am start -n ai.caredrive.car/.MainActivity
```

JDK 17 필요 (Android Studio 내장 JBR 사용 가능). 디버그 빌드 하단의 DEMO 버튼으로 단계 화면을 바꿔 볼 수 있습니다.

## 차량 기능 (2단계)

| 기능 | Pleos API | 에뮬레이터 확인 |
|---|---|---|
| 음성 안내 | TextToSpeech `ON_DEVICE`, ko-KR | 확인 |
| 공조 시원하게 | Vehicle HVAC: 전원 켜기 → 운전석 온도 −2℃, 풍량 +2 | 확인 (22→20℃, 3→5단) |
| 휴게소 안내 | NaviHelper: 현재 위치 → 가장 가까운 휴게소·졸음쉼터 → 목적지 또는 경유지 | 확인 (판교 → 성남졸음쉼터 3.6km) |
| 주행/정차 | Vehicle Gear 콜백 (경고 중 P → 정차 후 조치) | 확인 |

- NaviHelper 3.0.5에는 장소 검색이 없어서, OpenStreetMap의 휴게소·졸음쉼터 1,216곳(`assets/rest_areas.json`, © OpenStreetMap contributors, ODbL)에서 진행 방향 ±70° 안의 가장 가까운 곳을 고른다.
- 에뮬레이터 주의: 공조 값은 Virtual Console(`adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088)에서 한 번 값을 바꿔 "publish"해야 앱에서 읽힌다. 그 전에는 `PropertyNotAvailableException`.
- 공조 영역은 차마다 다르다(전원·풍량은 1열 전체, 온도는 좌석별). 항상 capability로 받은 영역을 쓴다.

## 사용 SDK

- Pleos Connect SDK: `ai.pleos.playground:Vehicle:2.3.2`, `NaviHelper:3.0.5`, `TextToSpeech:3.3.1`
- 예정: Samsung Health Data SDK 1.1.0 (폰), Samsung Health Sensor SDK (워치, 필요 시)
