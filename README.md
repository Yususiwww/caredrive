# 케어드라이브 (가칭)

갤럭시 워치의 심장 데이터를 Pleos 차량 앱으로 연결해, 운전자에게 이상 징후가 보이면 휴식을 제안하는 앱. 현대자동차그룹 차량용 앱 개발 공모전(Pleos) 출품작.

> 케어드라이브는 진단하지 않습니다. 삼성 헬스 모니터가 보낸 알림과 일반 심박 정보를 바탕으로 "쉬어 가세요"를 안내합니다.

## 구성

| 폴더 | 내용 | 상태 |
|---|---|---|
| `car-app/` | Pleos 차량 앱 (AAOS · Kotlin · Compose) | 2단계: 음성·공조·휴게소·기어 연결 |
| `relay/` | 폰 ↔ 차 중계 서버 (Node.js · WebSocket) | 4단계 예정 |
| `phone-sim/` | 폰 시뮬레이터 웹페이지 (가짜 심박·부정맥 알림) | 4단계 예정 |
| `docs/decisions/` | 결정 기록 | |

## 위험 단계

| 단계 | 조건 (설계 예시) | 차량 대응 |
|---|---|---|
| L0 정상 | 개인 기준 범위 | 상태 아이콘만 |
| L1 주의 | 안정 심박 +30bpm 5분 지속 | 음성 안내, 공조 시원하게 |
| L2 경고 | 주행 중 새 부정맥 알림 · L1 15분 지속 · 심박 <40 또는 >140 | 화면 + 음성으로 휴게소 경유 제안 |
| L3 정차 후 | L2 이후 기어 P | 심전도 측정 안내, 보호자 알림, 119 |

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
