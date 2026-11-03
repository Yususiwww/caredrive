# 케어드라이브 (가칭)

갤럭시 워치의 심장 데이터를 Pleos 차량 앱으로 연결해, 운전자에게 이상 징후가 보이면 휴식을 제안하는 앱. 현대자동차그룹 차량용 앱 개발 공모전(Pleos) 출품작.

> 케어드라이브는 진단하지 않습니다. 삼성 헬스 모니터가 보낸 알림과 일반 심박 정보를 바탕으로 "쉬어 가세요"를 안내합니다.

## 구성

| 폴더 | 내용 | 상태 |
|---|---|---|
| `car-app/` | Pleos 차량 앱 (AAOS · Kotlin · Compose) | 1단계: 단계별 화면 |
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

## 사용 SDK

- Pleos Connect SDK: `ai.pleos.playground:Vehicle:2.3.2`, `NaviHelper:3.0.5`, `TextToSpeech:3.3.1`
- 예정: Samsung Health Data SDK 1.1.0 (폰), Samsung Health Sensor SDK (워치, 필요 시)
