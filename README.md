# SES 기반 LLM 시나리오 생성 서버

서브태스크 템플릿으로 설계도를 구성하고, 요청에 필요한 모델을 SES 트리로 정리하며,
입력이 부족하면 되묻고 충분하면 시뮬레이션 시나리오를 생성하는 Spring 서버.

[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) 의 구현체다.

| 항목 | 값 |
|---|---|
| 언어 | Java 21 |
| 프레임워크 | Spring Boot 3.3.5 |
| 저장소 | PostgreSQL 16 + JSONB (Flyway) / 인메모리 |
| LLM | Anthropic / OpenAI 호환(Ollama·vLLM·llama.cpp), 없어도 동작 |
| 시뮬레이션 | 자체 DEVS 엔진 (순수 Java) |
| 빌드 | Gradle 8.8 멀티모듈 (Kotlin DSL) |

---

## 빠른 시작

### DB 없이 띄우기

가장 빠른 경로다. PostgreSQL 도 API 키도 필요 없다.

```bash
./gradlew :app:bootRun --args='--spring.profiles.active=memory'
```

```bash
curl -s -X POST localhost:8080/api/v1/sessions -H 'Content-Type: application/json' -d '{"request":"리조트 하나 시뮬레이션 돌려줘"}'
```

첫 응답에 `이동설비`, `숙박시설` 질문이 온다. 답을 보내면 그 선택에 따라
새 질문이 파생된다.

```bash
curl -s -X POST localhost:8080/api/v1/sessions/{sessionId}/answers -H 'Content-Type: application/json' -d '{"answers":{"이동설비":"케이블카","숙박시설":"호텔"}}'
```

### PostgreSQL 과 함께 띄우기

```bash
docker compose up -d
```

```bash
./gradlew :app:bootRun
```

### LLM 켜기

LLM 은 라우팅과 슬롯 추출 두 곳에 쓰인다. 없으면 각각 키워드 매칭과 질문으로
자동 대체되므로, 어느 쪽이든 서버는 뜨고 전체 흐름이 돈다.

**오픈 모델 (로컬)** — 키가 필요 없다.

```bash
ollama serve && ollama pull qwen2.5:7b
```

```bash
./gradlew :app:bootRun --args='--spring.profiles.active=memory,ollama'
```

OpenAI 호환 엔드포인트를 쓰므로 vLLM · llama.cpp server · LM Studio · OpenRouter 도
`llm.base-url` 과 `llm.model` 만 바꾸면 그대로 붙는다.

**Anthropic**

```bash
ANTHROPIC_API_KEY=sk-... ./gradlew :app:bootRun
```

---

## 설계의 중심

**SES 트리가 단일 진실 원천이다.** 템플릿이 슬롯 목록을 손으로 들고 있지 않다.
트리의 미결정 지점이 곧 질문 목록이다.

| 트리의 상태 | 질문 |
|---|---|
| `SpecNode.selectedVariantId == null` | "무엇을 고를까요?" |
| `MultiAspectNode.count == null` | "몇 개인가요?" |
| `VarDef.value == null && defaultValue == null` | "값은 얼마인가요?" |

`SlotResolver` 는 **미해결 `SpecNode` 의 하위를 순회하지 않는다.** 케이블카를 고르기
전에는 케이블카의 "정원" 변수가 질문 목록에 존재조차 하지 않는다. 답변이 들어와
pruning 되면 다음 스캔에서 자연스럽게 나타난다. 이것이 "파생 슬롯"의 구현 전부이고,
별도의 의존성 그래프를 손으로 유지하지 않는 이유다.

CLI 로 직접 확인할 수 있다. Spring 도 DB 도 LLM 도 쓰지 않는다.

```bash
./gradlew :app:exportSamples && ./gradlew :app:pruneCli -PcliArgs="samples/resort-ses.json samples/answers-shuttle.json" -q
```

```
== 턴 1 — 열린 슬롯 2개 ==
  - spec-transport [SELECT] @ 리조트/이동설비 선택지=[케이블카, 셔틀버스, 모노레일]
== 턴 2 — 열린 슬롯 2개 ==
  - multi-bus [MULTIPLICITY] @ 리조트/이동설비/셔틀버스/버스      <- 셔틀버스 선택으로 파생
== 턴 3 — 열린 슬롯 3개 ==
  - ent-bus#0.정원 [VALUE] ...                                  <- 대수 결정으로 파생
  - ent-bus#1.정원 [VALUE] ...
  - ent-bus#2.정원 [VALUE] ...
```

**LLM 은 자연어 경계에만 쓴다.** pruning, 검증, 완료 판정은 전부 결정론적 Java 코드다.
LLM 이 개입하는 곳은 세 군데뿐이고, 세 곳 모두 폴백이 있다.

| 지점 | 실패 시 |
|---|---|
| 템플릿 라우팅 | 키워드 매칭 |
| 요청문에서 슬롯 값 추출 | 추출 생략, 전부 질문 |
| 질문/결과 문구 다듬기 | 템플릿 원문 그대로 |

추출된 값도 예외 없이 `ValidationChain` 을 지난다. LLM 이 "정원 500명"을 뽑아냈어도
범위가 1~200 이면 채우지 않는다.

---

## 모듈 구조

```
ses-scenario-server/
├─ modules/
│  ├─ core-ses/      SES·PES 자료구조, SlotResolver, PruningEngine, PesBuilder  [Spring 무의존]
│  ├─ core-devs/     AtomicModel, Coordinator, 고전 GPT 예제                     [Spring 무의존]
│  ├─ template/      SubtaskTemplate, Registry, 템플릿-SES 정합성 검사
│  ├─ dialogue/      DialogueController, ValidationChain, QuestionPlanner, 라우팅
│  ├─ llm/           LlmGateway, 구조화 출력 파서, 감사 로그
│  ├─ scenario/      PesFlattener, ModelFactory, ScenarioExecutor, 도메인 모델
│  ├─ persistence/   JPA 엔티티, JSONB 매핑, Repository 어댑터
│  └─ api/           Controller, DTO, 예외 처리
└─ app/             Spring Boot 부트스트랩, 설정, Flyway, 시연 도메인
```

`core-ses` 와 `core-devs` 에는 `org.springframework` import 가 하나도 없다.
단위 테스트가 밀리초 단위로 끝나고, CLI 나 배치에서 그대로 재사용된다.

---

## API

```
POST   /api/v1/sessions                 요청문 -> 세션 생성 · 라우팅 · 첫 질문
GET    /api/v1/sessions/{id}            현재 상태 · 진행률
POST   /api/v1/sessions/{id}/answers    답변 제출 -> 다음 질문 또는 완료
POST   /api/v1/sessions/{id}/undo       직전 턴 되돌리기
GET    /api/v1/sessions/{id}/ses        현재 pruning 상태 트리 (시각화용)
POST   /api/v1/sessions/{id}/scenario   시나리오 생성

GET    /api/v1/scenarios/{id}           PES · 파라미터 조회
POST   /api/v1/scenarios/{id}/runs      시뮬레이션 실행 (202; ?wait=true 면 동기)
GET    /api/v1/runs/{id}                실행 상태 · 결과

GET    /api/v1/templates                템플릿 목록
POST   /api/v1/templates                템플릿 등록 (정합성 검사 통과 필수)
GET    /api/v1/templates/{id}/plan      서브태스크 실행 순서 (선행 의존 위상 정렬)

GET    /api/v1/ses-definitions          도메인 SES 목록
GET    /api/v1/ses-definitions/{id}     도메인 SES 조회
POST   /api/v1/ses-definitions          도메인 SES 등록 (공리·배선 검사 통과 필수)

GET    /api/v1/models                   모델 베이스 목록 (명세 + 구현체 유무)
GET    /api/v1/models/{modelId}         모델 명세 조회
POST   /api/v1/models                   모델 베이스 등록
```

응답의 `derivedFrom` 필드가 "왜 갑자기 이 질문이 나왔는지"를 설명한다. 파생 슬롯
구조에서는 이 설명이 없으면 질문이 끝없이 늘어나는 것처럼 느껴진다.

---

## 테스트

```bash
./gradlew test              # 119개, Docker/API 키 불필요
./gradlew integrationTest   # 4개, Testcontainers + PostgreSQL 16 (Docker 필요)
./gradlew :modules:llm:liveTest   # 3개, 실제 로컬 모델 호출 (Ollama 필요)
```

`integrationTest` 는 실 데이터베이스를 띄우므로 `ddl-auto: validate` 가 함께 돈다.
JPA 매핑과 Flyway 스키마가 어긋나면 이 태스크가 먼저 깨진다.

> **Docker Engine 29 이상을 쓴다면** 별도 설정은 필요 없다. 빌드 스크립트가
> `api.version=1.44` 를 지정해 둔다. Engine 29 는 API 1.44 미만을 거부하는데
> Testcontainers 가 쓰는 docker-java 의 기본값이 1.32 라, 이 설정이 없으면
> 데몬이 멀쩡히 떠 있어도 첫 Info 호출이 400 으로 떨어지고
> "Could not find a valid Docker environment" 라는, 원인과 한참 떨어진 메시지만 남는다.
> 구형 데몬이라면 `-PdockerApiVersion=1.32` 로 내릴 수 있다.

| 대상 | 방식 |
|---|---|
| `core-ses` | 속성 기반 (jqwik) — pruning 교환법칙, PES 유일성, 단조 감소 |
| `core-devs` | 골든 테스트 — GPT 예제의 이론값 대조, zeno/타임아웃 안전장치 |
| `template` | 템플릿-SES 정합성 위반 케이스, 서브태스크 DAG 위상 정렬·순환 검출 |
| `scenario` | 계층 PES 평탄화, EIC/EOC 전개, 도메인 모델 물리 검산 |
| `api` | 자산 등록 거부 조건 (중복 노드 id, 포트 단위 불일치), 라우팅 후보 응답 |
| `llm` | WireMock — 코드펜스 제거, 스키마 되돌림 복구, 파싱 재시도, 상태코드 구분 |
| live | `OllamaLiveTest` — 실제 오픈 모델이 스키마대로 응답하는지 (`@Tag("live")`) |
| E2E | `ResortDialogueE2eTest` (LLM 없음) + `LlmAssistedDialogueTest` (스텁 LLM) |
| 통합 | `PostgresPersistenceIT` — Flyway 스키마, JSONB 폴리모픽 왕복, 매핑 검증 |

`ResortDialogueE2eTest` 와 `LlmAssistedDialogueTest` 가 **함께** 통과하는 것이
폴백 설계의 증거다. 전자는 LLM 을 끈 채로, 후자는 켠 채로 같은 결과에 도달한다.

---

## 도메인은 데이터다

시연 도메인은 Java 코드가 아니라 JSON 리소스에 있다.

```
app/src/main/resources/seed/
├─ resort.ses.json                        도메인 SES (공리·배선 검사 후 등록)
├─ resort.models.json                     모델 베이스 명세 7건
├─ resort-simulation.template.json        서브태스크 템플릿
└─ resort-capacity-review.template.json   선행 의존이 있는 두 번째 템플릿
```

`DomainSeeder` 가 기동 시 이름 규칙(`*.ses.json` → `*.models.json` → `*.template.json`)으로
찾아 순서대로 등록한다. 오류가 있으면 서버가 뜨지 않는다 — 시드라고 검사를 건너뛰면
정작 그 검사가 잡아야 할 오류를 기본 데이터가 들고 온다.

운영 중에 새 도메인을 넣을 때는 REST 로 같은 순서를 따른다. 코드 수정은 없다.

## 확장

| 확장 대상 | 필요한 작업 | 코드 수정 |
|---|---|---|
| 새 도메인 | `POST /ses-definitions` → `/models` → `/templates` | 없음 |
| 시드 도메인 추가 | `app/src/main/resources/seed/` 에 JSON 배치 | 없음 |
| 동의어 추가 | 엔티티 JSON 의 `aliases` | 없음 |
| 새 원자 모델 | `AtomicModelFactory` 구현 + `model_base` 행 | 클래스 1개 |
| 새 검증 규칙 | `SlotValidator` 구현 | 클래스 1개 |
| LLM 공급자 추가 | `HttpLlmGateway` 상속 (경로·본문·응답 매핑 3개) | 클래스 1개 |
| 시뮬레이터 교체 | `ScenarioExecutor` 구현 추가 | 인터페이스 뒤 |

`AtomicModelFactory` 와 `SlotValidator` 는 Spring 이 구현체를 모아 주므로 등록
코드조차 필요 없다.

---

## 계획서와 달라진 점

구현하면서 계획서를 그대로 따르지 않은 곳이 있다. 이유와 함께 남긴다.

**1. `SlotResolver.scan()` 이 템플릿을 받지 않는다.**
계획서의 시그니처는 `scan(SesNode, SubtaskTemplate)` 이지만, 그러면 `core-ses` 가
`template` 을 알아야 해서 의존 방향(`template → core-ses`)이 순환한다. 스캔은 SES 만
보고, 템플릿의 표현 정보는 `dialogue` 의 `SlotJoiner` 가 앵커로 결합한다. 결과적으로
"SES 가 단일 진실 원천"이라는 원칙이 코드 구조로도 강제된다.

**2. `MultiAspectNode` 에 `instances` 를 추가했다.**
계획서에는 `count` 만 있는데, 그러면 복제본 3개가 프로토타입 하나를 공유해서
"2번 버스의 정원"을 따로 물어볼 수 없다. `count` 가 정해지는 순간 서브트리를 복제하고
노드 id 에 인스턴스 접미사(`ent-bus#2`)를 붙인다. 커플링 양 끝도 함께 고쳐야
복제본 내부 배선이 다른 복제본으로 새지 않는다.

**3. `EntityNode` 에 `modelRef`, `ports`, `couplings` 를 추가했다.**
`modelRef` 는 PES 리프가 어떤 원자 모델이 되는지 알아야 해서, `ports` 는 커플링
무결성 검사에 필요해서다. `couplings` 는 형제 축을 가로지르는 배선(자기 입력을
multi-aspect 복제본에 EIC 로 흘리는 등)을 담을 곳이 아무 데도 없어서 추가했다.

**4. 검증을 "적용 전"과 "적용 후"로 나눴다.**
계획서는 한 번에 검증하지만, 값에 의존하는 검사(교차 제약, 단위)는 답변이 트리에
들어가야만 가능하다. 트리가 불변이라 "적용해 보고 문제가 있으면 원래 트리를 그대로
다시 쓰는" 롤백이 공짜다. 이 순서가 아니면 "운행 간격 0.5분 vs 시간 해상도 1분"
같은 조합은 실행할 때까지 드러나지 않는다.

**5. `SlotKind` 를 `SELECT` / `MULTIPLICITY` / `VALUE` 로 세분했다.**
계획서의 `STRUCTURAL` / `VALUE` 로는 질문을 만들 때 다시 분기해야 한다.
`isStructural()` 로 원래의 두 갈래를 그대로 쓸 수 있다.

**6. `dialogue_session` 에 컬럼 넷을 더했다.**
`pending_questions`, `derived_from` 이 없으면 세션을 다시 읽었을 때 "무엇을 묻고
있었는지"가 사라지고, `history` 가 없으면 undo 를 구현할 수 없다. `request_text` 는
슬롯 추출을 다시 돌릴 때 필요하다.

**7. `llm_call_log.request/response` 를 `TEXT` 로 두었다.**
`JSONB` 로 두면 응답이 JSON 이 아닐 때 저장 자체가 실패한다. 파싱 실패가 정확히 그
경우이므로, 조사해야 할 사례만 골라서 사라진다.

**8. `model_base.impl_class` 를 쓰지 않는다.**
FQCN 을 DB 문자열로 들고 있으면 리팩터링 한 번에 조용히 깨지고, 깨진 사실은 실행
시점에야 드러난다. `AtomicModelFactory` 구현을 Spring 이 모아 `model_id` 로 색인한다.

**9. `app.persistence=memory` 프로파일을 추가했다.**
계획서의 "Phase 1~3 은 LLM 없이 완성한다"를 DB 까지 확장했다. E2E 테스트가 Docker
없이 밀리초 단위로 돌고, 시연할 때 PostgreSQL 을 띄우지 않아도 된다.

**10. Gradle 은 8.8 래퍼를 쓴다.**
이 환경에 설치된 Gradle 은 9.4.1 이지만 Spring Boot 3.3 플러그인과 맞지 않는다.
래퍼가 8.8 을 내려받으므로 `./gradlew` 만 쓰면 된다.

**11. `EntityNode` 에 별칭(aliases)을 두었다.**
계획서에는 없는 필드다. 요청문의 "곤돌라"를 선택지 "케이블카"에 맞추는 일을 LLM 의
추측에 맡기면, 그 추측은 어떤 검증으로도 잡히지 않는다 — 값은 범위 안이고 근거 문구도
문장에 실제로 있기 때문이다. 실제로 `llama3:latest` 는 "8인승 곤돌라"를 셔틀버스 8대로
읽고 모든 검증을 통과했다. 도메인이 아는 동의어는 도메인이 말해 줘야 한다.
별칭은 pruning 매칭, 타입 검증, 추출 프롬프트, 질문 힌트 네 곳에서 함께 쓰인다.

**12. 구조 결정은 신뢰도와 무관하게 확인받는다.**
값 슬롯은 근거가 확인되고 범위 안이면 조용히 채워도 되지만, 구조가 바뀌면 시뮬레이션
전체가 다른 이야기가 된다. 확신에 찬 오해를 막을 방법이 사용자 확인뿐이다.

**13. 추출값 채택을 신뢰도 대신 근거로 판단한다.**
모델의 자기보고 confidence 는 신호가 아니다. `gemma2:9b` 는 값을 정확히 뽑아 놓고
프롬프트 예시의 `0.0` 을 그대로 베껴 보냈고, 신뢰도 문턱에서 전부 버려졌다.
대신 모델이 함께 주는 `evidence` 를 요청문과 대조한다 — 이건 검증할 수 있다.

**14. Spring Data 리포지토리를 파일마다 하나씩 둔다.**
처음에는 인터페이스 일곱 개를 한 파일에 중첩해 두었는데, Spring Data 의 스캐너가
중첩 인터페이스를 리포지토리 후보로 잡지 않아 빈이 만들어지지 않았다. 컨텍스트가
뜨지 않는 이유가 "리포지토리가 없다"로만 나와 원인을 짚기 어렵다. 관례대로 되돌렸다.

**15. 도메인 자산은 코드가 아니라 JSON 리소스다.**
처음에는 시연 도메인을 `ResortDomain.java` / `ResortTemplate.java` 로 빌드했다.
동작은 했지만 그러면 "새 도메인 추가 시 코드 수정이 없어야 한다"(NFR-04)가 거짓이 된다 —
아키텍처가 데이터 기반이어도 실제로 넣을 문이 없으면 도메인은 코드로 들어온다.
`seed/*.json` + `POST /ses-definitions` / `POST /models` 로 옮기고 세 클래스를 삭제했다.

**16. 포트에 단위를 두고 배선에서 대조한다.**
계획서의 배선 검사는 포트 존재와 방향만 본다. 그런데 "명"을 내보내는 포트를 "분"을
받는 포트에 이으면 시뮬레이션은 오류 없이 끝나고 숫자만 틀린다 — 이 프로젝트에서
가장 비싼 종류의 버그다. `PortDef.unit` 과 `UnitTable`(차원·환산)을 두고
`SesStructureChecker` 가 `PORT_UNIT_MISMATCH` 로 잡는다. 명/인처럼 같은 차원의
다른 표기는 통과시킨다.

**17. 라우팅 후보를 사용자에게 되돌린다.**
확신이 낮을 때 임의로 하나를 고르면, 사용자는 자기가 요청하지 않은 시뮬레이션의
질문에 답하게 된다. 신뢰도가 문턱 아래면 세션을 만들지 않고 `CHOOSE_TEMPLATE` 로
후보 목록을 응답한다(HTTP 200, sessionId 없음). 사용자가 `templateId` 를 실어
다시 요청하면 그때 세션이 생긴다.

**18. 서브태스크 DAG 는 순서만 계산한다.**
`SubtaskDagPlanner` 는 선행 관계를 닫아 위상 정렬하고 `missing` / `cycle` 을 보고한다.
여러 서브태스크를 이어 실행하며 산출물을 참조 슬롯으로 넘기는 일까지는 하지 않는다 —
그것 없이도 "무엇을 먼저 해야 하는가"를 알려 주는 값은 이미 있고, 순환을 조용히
빠뜨리지 않는 것이 그중 절반이다.

---

## 진행 상황

| Phase | 내용 | 상태 |
|---|---|---|
| 0 | 멀티모듈 골격, Flyway, Testcontainers | 완료 |
| 1 | SES 코어 — SlotResolver, PruningEngine, PesBuilder, 공리 검사 | 완료 |
| 2 | DEVS 코어 — Coordinator, 고전 예제, 안전장치 | 완료 |
| 3 | 템플릿 + 대화 — 상태머신, 4계층 검증, 세션 API | 완료 |
| 4 | LLM 통합 — 게이트웨이, 라우팅, 슬롯 추출, 감사 로그 | 완료 |
| 5 | 실행·결과 — ModelFactory, 비동기 실행, 결과 형식화, 도메인 1세트 | 완료 |
| 6 | 확장 — 서브태스크 DAG, 도메인 자산 등록 API | 부분 완료 (시각화·저작 도구 미착수) |

Phase 6 중 몬테카를로/파라미터 스윕 실행 모드는 `ScenarioRunner` 에 들어가 있다.

## 로컬 LLM · MCP · Docker 대기행렬 실험

[실험 실행 안내](experiments/queue-mcp/README.md): 로컬 LLM이 제한된 SES 초안을 제안하고,
기존 서버가 부족한 값을 질문한 뒤 MCP로 SimPy 처리 모델을 조회·준비·실행한다.
생성기와 집계기는 로컬에서, 처리기는 Docker에서 실행하는 피드포워드 실험이다.

```powershell
.\experiments\queue-mcp\run-demo.ps1
```

실험용 카탈로그 한 건을 사용하며, 공개 모델 전체 자동 검색이나 분산 DEVS 실행은 범위 밖이다.
