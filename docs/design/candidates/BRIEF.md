# 케어드라이브 디자인 후보 브리프

후보마다 HTML 목업 한 장을 만들어 비교한다. 실제 앱 코드는 건드리지 않는다.

## 화면 (후보마다 같은 4장)
1. **주행 중 정상 (L0)**: 심박 76 bpm, "편안한 운전 중이에요", "다음 휴식 60분 뒤".
2. **경고 (L2)**: "혈당이 낮아요", "휴게소로 안내할까요?", 보조 "휴게소에서 단 것을 드세요". 휴게소 "성남졸음쉼터 · 3.6km" 지도 자리(단색 플레이스홀더도 됨). 버튼은 [휴게소 안내](주) [다음에].
3. **정차 후 (L3)**: "운전을 잠시 쉬어 주세요", "워치로 심전도를 재 주세요". 보조 "케어드라이브는 진단하지 않아요. 가슴이 아프거나 숨이 차면 119를 누르세요." 버튼은 [심전도 재기] [보호자 알림] [119 전화].
4. **폰 화면**: 390x844. 차 상태 요약과 "차로 보내기" 버튼. 컨디션 입력 몇 줄(수면 4시간 40분, 에너지 45점, 심박 72 bpm).

## 크기
- 차 화면은 Pleos 앱 영역 기준으로 1700x1150 px.
- 한 HTML 안에 4장을 나란히 둔다. 차 화면은 1700x1150 프레임 3개, 폰은 390x844 프레임 1개다.

## 반드시 지킬 것 (차 안전 규칙)
- 단계는 색만으로 구분하지 않는다. 모양 아이콘도 함께 쓴다(정상 원+체크, 주의 마름모!, 경고 삼각형!, 정차 팔각형).
- 주행 중 화면은 제목 + 한 줄만 둔다. 큰 글자로, 약 20자 이내.
- 경고(L2)는 버튼 최대 2개이고 확인 버튼이 먼저다.
- 글자는 차 화면에서 본문 28px 이상, 제목 48px 이상이다(2m 시야 거리 기준).
- 차 화면 글꼴은 Asta Sans다. Google Fonts에 없으면 Pretendard(cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9)로 대체하고 그 사실을 적는다.
- 문구는 위 문구를 그대로 쓴다. 의료 주장을 새로 만들지 않는다.

## 쓸 것
- 페르소나: `docs/research/2026-11-13-토스-디자이너-페르소나.md`. 토스 시니어 프로덕트 디자이너로서 일한다.
- 스킬: `.claude/skills/impeccable/SKILL.md`, `reference/craft-floor.md`, `reference/new-work.md`. 스크립트(launcher)는 실행하지 않는다.
- 스킬: `.claude/skills/design-taste-frontend/references/system.md`, `copy-tells.md`. 스크립트와 gate는 실행하지 않는다.
- 차량 UI 연구: `docs/research/2026-11-11-차량-UI-레퍼런스.md`.

## 렌더링
- 결과물: `docs/design/candidates/<id>.html`과 `docs/design/candidates/<id>.png`.
- PNG는 전체를 한 장으로 찍는다. 헤드리스 Chrome 예시:
  `"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --hide-scrollbars --force-color-profile=srgb --window-size=W,H --screenshot=out.png file://...`
  다크모드가 섞이지 않게 HTML에 `color-scheme`을 명시한다.
- 외부 리소스는 위 Pretendard CDN만 허용한다. 이미지는 CSS나 SVG로 그린다.
- 에뮬레이터는 켜지 않는다. 커밋하지 않는다. 기존 앱 코드는 수정하지 않는다.

## 보고
후보마다 한국어로 짧게 적는다.
- 이름과 한 줄 콘셉트
- 왜 이 방향인지(운전자에게 주는 이점)
- 위험 요소
- PNG 경로
