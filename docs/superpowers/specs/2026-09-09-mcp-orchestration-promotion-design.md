# MCP 오케스트레이션 경로 승격 — 설계

작성일: 2026-09-09
대상 저장소: `subtask_v2` (`ses-scenario-server`)
상태: 설계 확정, 구현 계획 대기

## 1. 목적

`experiments/queue-mcp`의 Python 실험 어댑터가 수행하는 파이프라인을 Spring 백엔드의 정식
경로로 옮긴다. 승격이 끝나면 사용자 요청 하나로 다음이 서버 안에서 완결된다.

```
자연어 요청
  → LLM이 SES 구조 초안 제안 (이름·역할·연결만)
  → 결정론적 초안 검증 (선언된 토폴로지 계약 대조)
  → MCP search_models / describe_model 로 외부 모델 계약 조회
  → 모델 계약에서 슬롯·질문·범위·단위를 생성해 SES + 서브태스크 템플릿으로 컴파일
  → 기존 등록 검증(공리·구조·정합성) 통과 후 레지스트리 등록
  → 기존 대화 경로로 미충족 파라미터를 사용자에게 질문
  → PES 확정
  → MCP provision_model / run_processor 로 외부 모델 실행, 결과 집계
```

승격의 부수 목표는 웹 프론트엔드가 호출할 HTTP 표면을 만드는 것이다. 현재 `design` 단계가
Python 스크립트에만 있어 프론트엔드가 붙을 대상이 없다.

## 2. 범위

### 포함

- `modules:mcp` — Java MCP stdio 클라이언트 (신규 모듈)
- `modules:design` — 초안 생성·검증·SES 컴파일 (신규 모듈)
- `POST /api/v1/design` — 요청문 하나로 설계·등록·세션 생성까지
- `Purpose.STRUCTURE_DRAFT` — LLM 구조 초안용 목적 추가
- `LlmProperties`에 `seed`, `temperatureByPurpose` 추가 및 OpenAI 호환 경로의
  `json_schema` 지원
- `McpBatchScenarioExecutor` — `engine = "queue-mcp-batch"` 실행 경로
- `queue-local-source` / `queue-local-sink`를 정식 `ModelBaseEntry`로 등록
- `ModelBaseEntry.params`에 `range`, `question` 키 추가
- 토폴로지 계약을 데이터(`queue.topology.json`)로 외부화
- MCP 교환 감사로그 영속화 (`V5__mcp_exchange.sql`)
- `ResultFormatter`에 대기행렬 지표 요약 추가

### 제외 (의도적으로 미룸)

- **SES 라이브러리 재사용 매칭** — 검증 통과한 SES의 영속화는 포함하지만, 유사 요청에
  기존 SES를 재사용하는 매칭 로직은 포함하지 않는다. 카탈로그에 모델이 1건뿐이라 재사용
  판정을 검증할 대상이 없다. 매 요청마다 새 SES를 컴파일해 등록한다.
- **모델 능력 기반 역할 자동 추론, 포트 타입 매칭 기반 임의 토폴로지 조립, 다중 토폴로지
  계약 선택** — 두 번째 외부 모델이 생길 때까지 착수하지 않는다. 검증할 대상이 없는
  일반화는 추측이다.
- **웹 프론트엔드** — 이 스펙은 프론트엔드가 호출할 API까지만 다룬다.
- **분산 DEVS 시간 동기화** — 실행 방식은 실험과 동일하게 피드포워드 이벤트 일괄 전달을
  유지한다. 로컬 DEVS 엔진과 컨테이너 모델의 시간 동기화는 이 스펙의 대상이 아니다.

## 3. 현재 상태

### 3.1 이미 있는 것

| 구성요소 | 상태 |
|---|---|
| `ScenarioExecutor` | `supports(String engine)`을 가진 인터페이스. `ScenarioRunner`가 `List<ScenarioExecutor>`를 주입받아 엔진 이름으로 선택 |
| `ExecutionSpec.mcpTools` | 필드 존재. 등록된 시드 템플릿 3건은 모두 빈 목록 |
| `LlmGateway.complete(...)` | 구조화 출력 + 파싱 실패 재시도. `OPENAI_COMPATIBLE`이 Ollama를 커버 |
| `ModelBaseEntry` | `modelId`, `kind`, `ports`, `stateVars`, `params`, `requiredParams()` |
| `POST /api/v1/models`, `/ses-definitions`, `/templates` | 등록 엔드포인트 |
| 등록 검증 3종 | `SesAxiomValidator`(공리 12코드), `SesStructureChecker`(배선 4코드), `TemplateConsistencyChecker`(정합성 12코드) |
| 대화 검증 4단계 | `TypeValidator` → `CrossFieldValidator` → `UnitConsistencyValidator` → `StructuralIntegrityValidator`. `ValidationChain`이 책임 연쇄로 실행 |
| 완료 판정 | `CompletionJudge` — 슬롯 충족 + 검증 통과 + PES 유일성 |
| 시드 도메인 | `resort-ses`(원자모델 7종), `evcharge-ses`(4종), 템플릿 3건 |

`grep -li mcp`가 `ExecutionSpec.java` 한 건만 잡는다. **Java 쪽에 MCP 코드는 없다.**

### 3.2 승격 대상 (`experiments/queue-mcp/experiment.py`, 465줄)

| 조각 | 내용 | 목적지 |
|---|---|---|
| `Mcp` 클래스 | stdio JSON-RPC, `mcp.jsonl` 감사로그 | `modules:mcp` |
| LLM 초안 생성 + `validate_draft` | Ollama `/api/chat`, 3노드 고정 검증 | `modules:design` |
| `compile_ses` | 카탈로그 `parameters`→슬롯, `question`→질문 문구 | `modules:design` |
| `assemble` → `simulator.json` | PES → 실행 명세 | `modules/scenario/exec/mcp` |
| `execute` + `collect` | MCP 호출 + 결과 집계·검증 | 같은 곳 |

### 3.3 실측한 런타임 환경 (2026-09-09)

| 항목 | 상태 |
|---|---|
| Java | 21.0.10 LTS |
| Ollama | 0.33.3 실행 중. `qwen2.5:7b`, `gemma2:9b`, `gemma:2b`, `llama3.2:3b`, `llama3:latest` |
| Docker | 미실행 — 통합 테스트 전에 Docker Desktop을 띄워야 한다 |
| `ANTHROPIC_API_KEY` | 미설정. 기본 프로파일에서 LLM은 `disabled`로 확정된다 |

### 3.4 미커밋 작업과의 관계

이 스펙은 **2026-09-08 커밋 `4955324` 이후의 워킹트리 상태**를 기준으로 작성했다. 그 시점에
`main`에 커밋되지 않은 변경 19파일(+454/-196)이 있었고, 그중 다음이 이 설계와 겹친다.

| 파일 | 겹치는 지점 |
|---|---|
| `SessionResponse` | `provenance`, `sesSnapshot`, `candidates` 필드가 이미 있다 → §5.2의 응답 설계가 이를 재사용한다 |
| `ResultFormatter` | `summary()`가 리조트 도메인 지표에 하드코딩되어 있다 → §7.4의 과제가 된다 |
| `DialogueController`(+256), `SessionState`(+42) | 대화 경로 본체. `POST /api/v1/design`이 세션 생성에 이 경로를 재사용한다 |
| `Scenario`, `ScenarioEntity`, `JpaScenarioStore` | `dataEvidence()` 추가. `ResultFormatter`가 이미 사용 |

**구현 착수 전에 이 변경들이 커밋 또는 정리되어야 한다.** 위 네 파일이 더 바뀌면 §5.2와
§7.4를 다시 확인해야 한다.

## 4. 모듈 경계

### 4.1 신규 모듈 2개, 변경 3개

```
modules:mcp      (신규)  → core-ses
modules:design   (신규)  → core-ses, template, llm, mcp
modules:scenario (변경)  + mcp 의존.  exec/mcp 패키지 추가
modules:api      (변경)  + design 의존.  DesignController 추가
app              (변경)  + design 의존.  설정 키 추가
```

`settings.gradle.kts`에 `"modules:mcp"`, `"modules:design"` 추가.

**`design`을 별도 모듈로 두는 이유.** 컴파일러는 LLM 초안, MCP 카탈로그, SES 자료구조,
템플릿 자료구조를 모두 알아야 한다. 이를 `template` 안에 넣으면 순수 자료구조 모듈이
LLM과 Docker에 의존하게 되고, `template`을 의존하는 `dialogue`·`scenario`·`persistence`가
전부 그 의존을 물게 된다. 현재 의존 그래프에서 `scenario`는 `llm`을 모르며, 이 성질을
유지한다.

**`scenario`에 `mcp`를 붙이는 것은 안전하다.** `llm` 의존이 새로 생기지 않고,
`ScenarioExecutor` 구현체가 한 모듈에 모여 `ScenarioRunner.pick()`의 주입 방식과 일관된다.

### 4.2 MCP 서버 배치

`experiments/queue-mcp/mcp_server.py`를 `mcp/queue-models/`로 옮긴다. `catalog.json`과
`processor/`도 함께 옮긴다. `experiments/queue-mcp/`에는 통합 테스트 클라이언트
(`verify_live.py`)만 남긴다.

실행 커맨드는 설정으로 외부화한다.

```yaml
mcp:
  max-concurrent: 1
  servers:
    - name: queue-models
      command: [python, mcp/queue-models/mcp_server.py]
      tools: [search_models, describe_model, provision_model, run_processor]
      timeout-ms:
        default: 30000
        provision: 600000
        run: 60000
```

경로를 코드에 박으면 `ExecutionSpec.mcpTools`가 이미 데이터로 들고 있는 도구 목록과
어긋난다.

### 4.3 프로세스 수명

요청 스코프 단발 프로세스. 실험과 동일하게 호출 묶음마다 프로세스를 띄우고 닫는다.
`run_processor`가 Docker 컨테이너를 띄우므로 프로세스 상태를 공유해서 얻을 것이 없고,
프로세스 풀은 좌초된 컨테이너 정리 책임을 서버가 지게 만든다.

동시 실행은 세마포어로 1개로 제한한다. 제한이 없으면 `provision_model`이 Docker 빌드를
병렬로 여러 번 실행한다.

종료 절차는 실험을 그대로 따른다 — `stdin` 닫고 3초 대기, 그 후 강제 종료.

### 4.4 보안 경계

Java는 Docker를 직접 호출하지 않는다. 다음은 전부 `mcp_server.py`에 남는다.

- 실행 허용목록 (카탈로그 `modelId` 1건)
- 이미지 라벨 검증 (`ses.experiment=queue-mcp`)
- 페이로드 200KB 상한
- 컨테이너 격리: `--network=none --read-only --cap-drop=ALL --security-opt=no-new-privileges
  --memory=128m --cpus=1 --pids-limit=32`
- 타임아웃 시 `docker rm --force`로 컨테이너 정리
- LLM이 제안한 URL·셸 코드·Docker 명령은 실행하지 않는다

Java 쪽은 설정에 선언된 도구 이름만 호출할 수 있다.

## 5. `POST /api/v1/design`

### 5.1 처리 순서

기존 `POST /api/v1/sessions`는 템플릿이 이미 등록되어 있어야 한다
(`{"request": ..., "templateId": ...}`). 새 엔드포인트가 그 앞단을 담당한다.

요청:

```json
{ "request": "작업들이 한 처리기 앞에서 기다리는 대기행렬을 만들고 평균 대기시간을 보고 싶어" }
```

전부 동기 처리하며, 실패한 단계에서 중단한다.

| 단계 | 실패 시 |
|---|---|
| 1. LLM 초안 생성 (`Purpose.STRUCTURE_DRAFT`) | `503` — 폴백 초안을 만들지 않는다 |
| 2. `DraftValidator` 검증 | `422` + `reason`. LLM의 `supported=false`도 여기 |
| 3. MCP `search_models` → `describe_model` | `502` |
| 4. `McpModelContractValidator` 검증 | `422` |
| 5. `SesCompiler` 컴파일 | `500` — 컴파일러 결함 |
| 6. `SesRegistry` / `TemplateRegistry` 등록 (등록 검증 3종 통과) | `409` 키 충돌 / `422` 검증 실패 |
| 7. 세션 생성 + 첫 질문 (기존 경로) | 기존 동작 |

6단계가 이 설계의 핵심이다. 컴파일러 산출물을 기존 레지스트리에 등록하면
`SesAxiomValidator`·`SesStructureChecker`·`TemplateConsistencyChecker`의 제약 28개가
**LLM 산출물에 자동으로 적용된다.** 새 검증기를 만드는 것이 아니라 이미 있는 것을 쓴다.

### 5.2 응답

기존 `SessionResponse`를 재사용하고 `design` 필드 하나만 추가한다.

```json
{
  "sessionId": "...", "phase": "...", "outcome": "NEEDS_INPUT", "turn": 1,
  "questions": [ { "slot": "arrivalInterval", "text": "작업이 몇 분 간격으로 도착하나요?",
                   "unit": "분", "range": { "min": 0.01, "max": 10 } } ],
  "progress": { ... }, "issues": [], "sesSnapshot": { ... },
  "provenance": [ ... ], "candidates": [ ... ], "canUndo": false, "scenarioId": null,
  "design": {
    "templateId": "queue-a1b2c3d4e5f6",
    "sesDefinitionId": "queue-a1b2c3d4e5f6",
    "title": "단일 FIFO 대기행렬",
    "assumptions": ["단일 서버 / FIFO / 무한 대기실", "..."],
    "topologyContractId": "fifo-single-server",
    "models": [ { "modelId": "external-simpy-fifo", "role": "PROCESSOR",
                  "origin": "mcp:queue-models", "version": "1.0.0" },
                { "modelId": "queue-local-source", "role": "SOURCE", "origin": "model-base" },
                { "modelId": "queue-local-sink", "role": "SINK", "origin": "model-base" } ],
    "provenance": { "llmProvider": "openai-compatible", "model": "qwen2.5:7b",
                    "temperature": 0.0, "seed": 42, "schemaConstrained": true,
                    "llmCallId": "..." }
  }
}
```

설계 판단 세 가지.

1. **`sesSnapshot`이 이미 있으므로 `design`은 트리를 담지 않는다.** 담지 않는 것만 담는다 —
   식별자, 제목, 가정, 토폴로지 계약 id, 모델 출처, LLM 출처.
2. **출처는 `design.provenance`에 중첩한다.** `SessionResponse.provenance`는 `ProvenanceView`
   목록으로 **슬롯 단위** 출처를 담는 자리다. 구조 초안의 출처는 슬롯이 아니라 세션 단위
   사실이므로 최상위에 형제 필드로 두면 같은 이름이 두 가지를 뜻하게 된다.
3. **출처를 남기는 이유는 연구용이라서다.** 어떤 모델이 어떤 온도·시드로 어떤 구조를
   제안했는지 기록되지 않으면 결과를 재현할 수 없다. `LlmCallRecorder`가 이미 있으므로
   호출 id만 노출한다.

### 5.3 이후 흐름

기존 API가 그대로 받는다. `POST /sessions/{id}/answers` → `POST /sessions/{id}/scenario`
→ `POST /scenarios/{id}/runs`. 마지막 것이 `SimulatorConfig.engine = "queue-mcp-batch"`를
보고 새 executor를 선택한다.

**기존 엔드포인트는 하나도 변경하지 않는다.**

## 6. LLM 초안 생성

### 6.1 실측 근거

2026-09-09에 로컬 Ollama 0.33.3의 OpenAI 호환 엔드포인트를 직접 확인했다.

| 확인 항목 | 결과 |
|---|---|
| `response_format: {"type":"json_schema", "json_schema":{...,"strict":true}}` | **200. 스키마 준수** — `minItems`/`maxItems`/`enum` 전부 지켜짐 |
| `seed: 42` + `temperature: 0`, 동일 요청 2회 | **출력 완전 동일** |
| `response_format: {"type":"json_object"}` (현재 Java 방식) | 200이지만 **스키마 위반** — `nodes` 배열 대신 `source`/`processor`/`sink` 키 3개짜리 객체 반환 |

세 번째 결과가 `application-ollama.yml`의 주석("파라미터가 적은 모델은 첫 시도에서 스키마를
놓치는 일이 잦다")과 `max-parse-attempts: 3` 설정의 근거를 재현한다.

따라서 Ollama 네이티브 게이트웨이를 새로 만들 필요가 없다. OpenAI 호환 경로를 확장한다.

### 6.2 `LlmProperties` 확장

가산적 변경이며 기본값은 현재 동작을 유지한다.

```java
public record LlmProperties(
        // ... 기존 필드 ...
        Long seed,                             // null 이면 보내지 않는다 (현재 동작)
        Map<String, Double> temperatureByPurpose,   // 비어 있으면 전역 temperature
        Set<String> schemaConstrainedPurposes       // 비어 있으면 json_object (현재 동작)
) { }
```

`OpenAiCompatibleLlmGateway`:

- `seed != null`이면 `body.put("seed", seed)`
- `temperatureByPurpose`에 해당 목적이 있으면 그 값을, 없으면 `temperature`를 사용
- 목적이 `schemaConstrainedPurposes`에 있으면 `response_format`을 `json_schema`로 보낸다.
  스키마는 기존 `JsonSchemaGenerator`가 대상 타입에서 만든다
- 엔드포인트가 `json_schema`를 거부하면(4xx) `json_object`로 1회 내려앉고 그 사실을
  `LlmCallRecord`에 남긴다. 다른 공급자·구버전 Ollama를 위한 안전장치다

`application-ollama.yml`:

```yaml
llm:
  seed: 42
  temperature-by-purpose:
    STRUCTURE_DRAFT: 0.0
  schema-constrained-purposes: [STRUCTURE_DRAFT]
```

Anthropic 경로는 변경하지 않는다. `ROUTING`·`EXTRACTION`·`PHRASING`·`SUMMARY`의 동작도
바뀌지 않는다.

### 6.3 `Purpose.STRUCTURE_DRAFT`

```java
/** 요청문 -> SES 구조 초안. LLM은 노드 이름·역할·연결만 제안한다.
 *  형식·모델 계약·수치는 DraftValidator 와 SesCompiler 가 결정론적으로 부여하고,
 *  선언된 TopologyContract 를 벗어난 초안은 거부한다. 폴백 초안은 만들지 않는다. */
STRUCTURE_DRAFT
```

`Purpose`의 클래스 javadoc을 함께 고친다. 현재 문장은 "딱 세 가지 목적에만 쓴다"인데
항목이 이미 네 개라 낡았고, "LLM이 구조를 직접 결정하게 두면 같은 입력에 다른 모델이
나온다"는 문장은 다음으로 대체한다.

> LLM은 선언된 토폴로지 계약 안에서 구조를 **제안**할 수 있다. 확정은 결정론적 검증기와
> 컴파일러가 한다. pruning, 검증, 완료 판정은 여전히 전부 결정론적 Java 코드다.

원칙을 바꾸는 변경이므로 문서에 남긴다.

### 6.4 구성 요소 (`modules:design`)

| 클래스 | 책임 |
|---|---|
| `StructureDraft` | `title`, `supported`, `reason`, `nodes[{id,name,role}]`, `connections[{from,to}]`, `assumptions[]` |
| `DraftPromptBuilder` | `TopologyContract`에서 프롬프트를 생성. 지원 범위가 문자열 상수가 아니라 데이터에서 나온다 |
| `StructureDraftGenerator` | `LlmGateway.complete(..., StructureDraft.class, STRUCTURE_DRAFT)`. 실패 시 예외, 폴백 없음 |
| `DraftValidator` | 결정론적 초안 검증 |

`DraftValidator`가 내는 코드:

| 코드 | 규칙 |
|---|---|
| `DRAFT_UNSUPPORTED` | `supported != true` |
| `DRAFT_ROLE_SET` | 역할 집합이 계약의 `roles`와 불일치 |
| `DRAFT_NODE_COUNT` | 노드 수가 계약의 `nodeCount`와 불일치 |
| `DRAFT_NODE_ID_INVALID` | id가 영숫자·하이픈이 아니거나 중복 |
| `DRAFT_NODE_ID_RESERVED` | id가 계약의 `reservedNodeIds`에 포함 |
| `DRAFT_NODE_NAME_INVALID` | 이름 중복, 60자 초과, `/[]#` 포함, 공백만 |
| `DRAFT_WIRING` | 연결이 계약의 `requiredConnections`와 불일치 (사이클·우회 포함) |

`validate_draft`가 잡던 것 중 **노드 id 유일성·이름 중복·배선 무결성은 등록 시점 검증
3종이 이미 잡는다.** `DraftValidator`에는 그들이 모르는 것만 남긴다 — 역할 집합, 계약
토폴로지 준수, 예약 id. 다만 노드 id·이름 검사는 컴파일 전에 걸러야 컴파일러가 잘못된
식별자로 트리를 만들지 않으므로 중복을 감수하고 유지한다.

## 7. 컴파일러와 하드코딩 제약 처리

`experiment.py`에는 SES·템플릿에 없고 코드에만 있는 제약이 6군데 있다. 이를 넷으로 나눠
각각 다르게 다룬다.

### 7.1 데이터로 내린다 — 로컬 모델 계약

`PARAMS` 상수(`arrivalInterval` 0.01–10분, `totalJobs` 1–100건)와 `validate_parameters`의
범위 재선언을 없앤다. `queue-local-source`·`queue-local-sink`를 정식 `ModelBaseEntry`로
등록한다.

`app/src/main/resources/seed/queue.models.json`:

```json
[
  { "modelId": "queue-local-source", "kind": "ATOMIC", "displayName": "작업 생성기",
    "ports": { "in": [], "out": ["out"] },
    "params": {
      "arrivalInterval": { "type": "DOUBLE", "unit": "분", "required": true,
                           "range": { "min": 0.01, "max": 10 },
                           "question": "작업이 몇 분 간격으로 도착하나요?" },
      "totalJobs":       { "type": "INT", "unit": "건", "required": true,
                           "range": { "min": 1, "max": 100 },
                           "question": "총 몇 건의 작업을 생성할까요?" } },
    "stateVars": { "emitted": "int" } },
  { "modelId": "queue-local-sink", "kind": "ATOMIC", "displayName": "작업 집계기",
    "ports": { "in": ["in"], "out": [] }, "params": {},
    "stateVars": { "arrived": "int", "completed": "int", "waitSum": "double" } }
]
```

이로써 내부 모델 2건과 외부 MCP 모델 1건이 **모두 같은 형태의 계약 데이터**가 되고,
범위·단위·질문 문구의 단일 출처가 생긴다. `validate_parameters`는 사라진다 — 기존
`TypeValidator`가 `Range`를 보고 같은 일을 한다.

`ModelBaseEntry.params`의 값 맵에 `range`, `question` 두 키를 추가한다. 가산적이므로 기존
시드 11건은 영향받지 않는다.

### 7.2 데이터로 내린다 — 토폴로지 계약

`app/src/main/resources/seed/queue.topology.json`:

```json
{ "contractId": "fifo-single-server",
  "nodeCount": 3,
  "roles": [ { "role": "SOURCE",    "modelRef": "queue-local-source" },
             { "role": "PROCESSOR", "capability": "fifo-single-server", "external": true },
             { "role": "SINK",      "modelRef": "queue-local-sink" } ],
  "requiredConnections": [ ["SOURCE", "PROCESSOR"], ["PROCESSOR", "SINK"] ],
  "allowFeedback": false,
  "reservedNodeIds": ["queue-root", "queue-components"],
  "rootEntityName": "대기행렬",
  "aspectName": "구성",
  "timeUnit": "분",
  "horizon": 2000,
  "unsupported": ["다중 서버", "우선순위", "확률분포", "피드백", "유한 대기실"] }
```

`DraftPromptBuilder`와 `DraftValidator`가 **같은 이 파일**을 읽는다. 현재 같은 규칙이 Python
프롬프트 문자열, `validate_draft`, `validate_manifest` 세 곳에 흩어져 있으므로 이것은
일반화가 아니라 중복 제거다.

계약은 지금 하나뿐이다. 다중 계약 선택은 §2 제외 항목이다.

### 7.3 테스트로 옮긴다 — 상수 동일성 비교

`validate_model`의 필드별 상수 비교(`ports == {...}`, `limits == {...}`, 파라미터 range의
정확한 값 비교)와 `collect`의 해석해 대조는 런타임에서 빼고 골든 테스트로 옮긴다.

런타임에 남는 `McpModelContractValidator`는 구조 검증만 한다.

| 검사 | 코드 |
|---|---|
| 필수 키 존재 (`modelId`, `capability`, `timeUnit`, `ports`, `parameters`, `limits`, `runtime`) | `CONTRACT_INCOMPLETE` |
| `capability`가 요청한 것과 일치 | `CAPABILITY_MISMATCH` |
| `timeUnit`이 `UnitTable` 별칭으로 접혀 알려진 시간 단위가 된다 | `TIME_UNIT_UNKNOWN` |
| 포트에 `name`/`dataType`/`unit` 존재 | `PORT_CONTRACT_INCOMPLETE` |
| 파라미터 `range.min <= range.max` | `PARAM_RANGE_INVALID` |
| 파라미터 `type`이 `VarType`에 있음 | `PARAM_TYPE_UNKNOWN` |
| `limits.termination`이 허용 집합에 있음 | `TERMINATION_UNSUPPORTED` |

### 7.4 새로 필요한 것 — 결과 요약

`ResultFormatter.summary()`는 리조트 도메인 지표에 하드코딩되어 있다 — `.arrived`,
`.carried`, `.maxQueue`, `.admitted`, `.rejected`, `.avgTurnaround`를 컴포넌트별로 합산한다.
대기행렬 경로의 지표(`meanWait`, `maxWait`, `completed`, `lost`)는 여기 없으므로 **MCP 경로
결과는 `scenario.summary()`만 담긴 거의 빈 요약으로 나온다.**

두 가지를 함께 한다.

1. executor가 통계를 기존 `<컴포넌트>.<지표>` 관례에 맞춰 내되, **지표 이름은 리조트
   도메인과 겹치지 않게 둔다.** `.arrived`를 재사용하면 `ResultFormatter`가 대기행렬
   작업을 "도착 방문객"으로 라벨링한다. 접미사는 `.jobsArrived`, `.jobsCompleted`,
   `.jobsLost`, `.meanWait`, `.maxWait`를 쓴다
2. `ResultFormatter`에 다섯 줄을 추가한다 — `도착 작업`(`.jobsArrived`, `appendSum`),
   `완료 작업`(`.jobsCompleted`, `appendSum`), `손실 작업`(`.jobsLost`, `appendSum`),
   `평균 대기시간`(`.meanWait`, `appendFirst`, 단위 `분`), `최대 대기시간`(`.maxWait`,
   `appendMax`)

지표 이름이 도메인마다 갈리는 것은 `ResultFormatter`가 도메인 지표를 알아야 하는 현재
구조의 결과다. 도메인이 늘어나면 이 방식은 무너지지만, 지표 이름을 `OutputSpec`이 선언하게
바꾸는 것은 이 스펙의 범위를 넘는다.

`ResultFormatter`에는 미커밋 변경(+5줄)이 있으므로 §3.4의 정리가 끝난 뒤 손댄다.

### 7.5 `SesCompiler`

입력: `StructureDraft` + `TopologyContract` + `ModelBaseEntry`(로컬) + MCP 카탈로그(외부).
출력: `SesDefinition` + `SubtaskTemplate`.

슬롯 생성 규칙은 하나다.

> 각 역할 노드에 대해 그 모델 계약의 `params`를 순회한다. `required && default == null`이면
> `VarDef`를 값 없이 만들고 `ValueSlot`을 생성한다 — `type`·`unit`·`range`·`question`은
> 계약에서 복사하고 `anchor = SesAnchor.variable(entityPath, nodeId, varName)`,
> `inferable = false`. `default`가 있으면 `VarDef.defaultValue`에 넣는다.

`default`가 있는 파라미터는 기존 `VarDef.isOpen()`이 `false`를 반환하므로 **질문이 자동으로
생기지 않는다.** 새 규칙을 만들 필요가 없다.

생성되는 SES 형태:

```
E 대기행렬 [queue-root]                       (rootEntityName)
  AND 구성 [queue-components]                 (aspectName)
    E <draft.name> [<draft.id>]  modelRef=queue-local-source   vars: arrivalInterval, totalJobs
    E <draft.name> [<draft.id>]  modelRef=external-simpy-fifo  vars: serviceTime
    E <draft.name> [<draft.id>]  modelRef=queue-local-sink     vars: (없음)
    couplings: IC <source>.out -> <processor>.in
               IC <processor>.out -> <sink>.in
```

포트는 모델 계약의 `ports`에서 만들고 `dataType`·`unit`을 그대로 옮긴다. 그러면
`SesStructureChecker`의 `PORT_UNIT_MISMATCH` 검사가 외부 모델 계약에 대해서도 작동한다.

생성되는 템플릿: `routing`(트리거 `대기행렬`, priority 1), `binding`(방금 만든 SES id,
`rootEntity` = 계약의 `rootEntityName`), `dialogue`(`maxTurns` 10,
`maxQuestionsPerTurn` 2, `unfilledPolicy` `ASK_AGAIN`), `execution`(`mcpTools` 4개,
`simulator.engine` `queue-mcp-batch`, `horizon`·`timeUnit`은 계약에서, `seed` 42,
`mode` `SINGLE`), `output`(`COMPOSITE`, `[SUMMARY, TABLE]`, `PARTIAL_RESULT`),
`meta`(`author` = `design-api`, `createdAt` = 오늘, `description` = `draft.title`).

`unfilledPolicy`를 `ASK_AGAIN`으로 두는 이유는 생성된 슬롯 3개가 모두 필수이고 기본값이
없어서, 기본값으로 채우면 사용자가 답하지 않은 수치로 시뮬레이션이 도는 것이기 때문이다.

## 8. 실행 경로

### 8.1 `modules:mcp`

| 클래스 | 내용 |
|---|---|
| `McpClient` | stdio JSON-RPC 2025-11-25. `initialize` → `notifications/initialized` → `tools/list` → `tools/call`. `AutoCloseable` |
| `McpServerProperties` | §4.2의 YAML 바인딩 |
| `McpExchangeLog` | 요청·응답 전량 기록. `mcp.jsonl`에 대응 |
| `McpToolError` | `isError: true` 응답 |
| `McpUnavailableException` | 프로세스 실패, 프로토콜 버전 불일치, 타임아웃 |

프로토콜 버전이 `2025-11-25`가 아니면 실패한다(실험과 동일). 설정에 선언되지 않은 도구
이름은 호출을 거부한다.

### 8.2 `McpBatchScenarioExecutor`

`modules/scenario/exec/mcp` 패키지. `implements ScenarioExecutor`,
`supports("queue-mcp-batch")`. Spring이 `List<ScenarioExecutor>`에 자동 등록하고
`ScenarioRunner.pick()`이 엔진 이름으로 선택한다. **`DevsScenarioExecutor`는 변경하지
않는다.**

실행 순서:

1. PES에서 `SimulatorManifest` 조립. `PesFlattener`가 이미 PES → `ModelSpec` 평탄화를
   수행하므로 재사용을 검토한다
2. manifest 검증 (§8.3)
3. 로컬 SOURCE가 도착 이벤트 생성 — `at = (i+1) * arrivalInterval`, 첫 도착은 t=0이 아니라
   `arrivalInterval` 시점
4. `provision_model` — 이미지가 이미 있으면 생략
5. `run_processor` — 페이로드는 `{serviceTime, arrivals}`
6. 로컬 SINK가 집계하고 불변식 검사 (§8.4)

`ScenarioExecutor.execute(Pes, SimConfig)`는 `SimConfig`의 `seed`·`timeoutMs`·`horizon`을
받으므로 시그니처를 바꾸지 않는다. MCP 타임아웃은 `SimConfig.timeoutMs`와 설정값 중 작은
쪽을 쓴다.

### 8.3 manifest 검증

| 검사 | 근거 |
|---|---|
| 노드 수가 토폴로지 계약의 `nodeCount`와 일치 | 계약 |
| `modelRef` 집합이 계약의 역할별 모델과 일치 | 계약 |
| 배선이 계약의 `requiredConnections`와 일치하고 전부 `IC out -> in` | 계약 |
| 중첩 노드(`children`/`couplings` 보유) 없음 | 평탄 구조 전제 |
| 파라미터가 PES 노드의 값과 일치 | PES가 단일 진실 원천 |
| `horizon`이 계약값과 일치 | 실행 상한 |

`experiment.py`의 `validate_manifest`가 상수로 비교하던 것들이 전부 계약 데이터 대조로
바뀐다.

### 8.4 런타임 불변식

`collect`의 해석해 대조(`serviceTime` 기반 정확값 계산)는 이 모델에서만 성립하므로 §7.3대로
골든 테스트로 옮긴다. 런타임에는 모델과 무관하게 성립하는 것만 남긴다.

| 불변식 | 위반 시 |
|---|---|
| 작업 보존 — 도착 수 = 완료 수 | 실패 |
| 단조성 — `startedAt >= arrivedAt`, `departedAt > startedAt` | 실패 |
| 작업 id 순서 보존 | 실패 (FIFO 계약) |
| `finishedAt <= horizon` | 실패 |
| `modelId`·`timeUnit`이 조회한 계약과 일치 | 실패 |

### 8.5 `SimulationResult` 매핑

| 필드 | 값 |
|---|---|
| `status` | `COMPLETED` / `TIMED_OUT` |
| `endTime` | `finishedAt` |
| `eventCount` | 도착 + 완료 이벤트 수 |
| `statistics` | `<sink>.jobsArrived`, `<sink>.jobsCompleted`, `<sink>.jobsLost`, `<sink>.meanWait`, `<sink>.maxWait`, `utilizationFromTimeZero`, `imageId`, `libraryVersion`, `manifestSha256` |
| `trace` | 완료 이벤트 (`departures`) |
| `outputs` | 빈 목록 |
| `note` | 조기 종료 사유 + 가정 목록 |

`<sink>` 접두사를 붙이는 이유는 §7.4대로 `ResultFormatter`의 기존 합산 관례를 그대로 쓰기
위해서다. `imageId`·`libraryVersion`·`manifestSha256`은 접두사 없이 두어 `RAW` 섹션에서
그대로 보이게 한다.

### 8.6 감사로그 영속화

`V5__mcp_exchange.sql` + `McpExchangeEntity` + `McpExchangeRepository`를 추가한다. 컬럼:
`id`, `run_id`, `session_id`, `server_name`, `direction`, `method`, `tool_name`, `payload`
(jsonb), `at`. `memory` 프로파일에서는 인메모리 구현을 쓴다 — 기존
`ConditionalOnJpaPersistence` 패턴을 따른다.

## 9. 테스트

### 9.1 단위 (Docker·Ollama 불필요, `test` 태스크 기본 실행)

| 대상 | 내용 |
|---|---|
| `DraftValidator` | 4노드, 역할 누락, 사이클, 우회, 예약 id, 60자 초과, `/[]#` 포함, 이름 중복 — 전부 거부 |
| `SesCompiler` | 계약 → 슬롯·질문 문구·범위·단위 생성. `default` 있는 파라미터는 슬롯 미생성. 포트 단위 전파 |
| `McpModelContractValidator` | 7개 코드 각각 |
| `McpClient` | 가짜 stdio 서버로 `initialize`, 버전 불일치, `tools/list`, `isError`, 타임아웃, 프로세스 조기 종료, 미선언 도구 거부 |
| `SimulatorManifest` 검증 | 노드 수·배선·중첩·파라미터 불일치·horizon 불일치 |
| 런타임 불변식 | 작업 손실, 역행 시각, id 순서 교란, horizon 초과 |
| `OpenAiCompatibleLlmGateway` | `seed` 전송, 목적별 온도, `json_schema` 전송, 4xx 시 `json_object` 폴백 |
| **회귀** | 기존 시드 템플릿 3건(`devs-internal`)이 그대로 동작. executor 추가가 `pick()`을 깨지 않음 |

### 9.2 통합 (`@Tag("integration")`, `integrationTest` 태스크)

`design → answers → scenario → runs` 전 구간을 실행한다. Docker Desktop과 Ollama가 필요하다.

골든 입력: 도착간격 2분, 처리시간 3분, 작업 5건.

| 지표 | 기대값 |
|---|---|
| 완료 | 5건 |
| 손실 | 0건 |
| 평균 대기 | 2분 |
| 최대 대기 | 4분 |
| 종료 시각 | 17분 |
| t=0 기준 이용률 | 15/17 |

작업별 상세(도착/시작/완료/대기): (2,2,5,0), (4,5,8,1), (6,8,11,2), (8,11,14,3),
(10,14,17,4).

§7.3에서 런타임에서 빼낸 상수 동일성 비교와 해석해 대조가 이 테스트에 들어간다.

### 9.3 결정론 (`@Tag("live")`, `liveTest` 태스크)

같은 요청 2회에 **구조 동형**을 확인한다 — 노드 id는 UUID 기반이라 다르므로, 역할 집합 ·
배선 · 슬롯 이름 집합 · 모델 참조 집합의 지문을 비교한다.

§6.1에서 시드가 실제로 동작함을 확인했으므로 이 테스트는 성립한다. 확인 없이는 주장할 수
없었던 항목이다.

### 9.4 TDD 순서

`McpClient` → `McpModelContractValidator` → `SesCompiler` → `DraftValidator` →
`McpBatchScenarioExecutor` → `DesignController`.

앞의 네 개는 외부 의존이 없어 순수 단위 테스트로 진행한다. `McpClient`를 가장 먼저 두는
이유는 나머지 전부가 그 위에 서기 때문이다.

## 10. 위험

| 위험 | 완화 |
|---|---|
| 미커밋 작업과 충돌 (`DialogueController` +256줄, `SessionResponse`, `ResultFormatter`) | §3.4 — 구현 착수 전 커밋/정리를 전제한다. 착수 시 §5.2·§7.4를 재확인한다 |
| Docker 미실행 | §9.2 실행 전 확인. 단위 테스트는 Docker 없이 전부 통과해야 한다 |
| Ollama 구버전이나 다른 공급자가 `json_schema` 거부 | §6.2의 `json_object` 폴백. 첫 통합 테스트에서 드러난다 |
| LLM 초안 품질 — 계약을 만족하지만 요청 의도와 다른 구조 | 구조 검증은 자연어 의도와의 일치를 증명하지 않는다. 응답의 `design.assumptions`와 `sesSnapshot`으로 사용자가 확인한다. LLM 분류 정확성 자체는 별도 평가 대상이다 |
| 모델이 1건뿐이라 계약 추상화가 과적합 | §7.2 계약을 데이터로 두되 다중 계약 선택은 만들지 않는다. 두 번째 모델이 생길 때 검증한다 |
| 세마포어 1로 동시성 제한 → 처리량 저하 | 의도된 선택. Docker 빌드 병렬 실행이 더 나쁘다. 필요해지면 provision과 run을 분리해 상한을 다르게 둔다 |

## 11. 결정 기록

| 결정 | 근거 |
|---|---|
| 전체 승격 (design 단계까지 서버로) | 웹 프론트엔드가 호출할 대상이 필요하다. 실행 경로만 먼저 옮기면 나중에 컴파일러를 끼워 넣느라 두 번 손대게 된다 |
| `design`을 별도 모듈로 | `template`이 `llm`·`mcp`를 모르게 유지한다. `scenario`가 `llm`을 모르는 현재 성질을 지킨다 |
| MCP 서버 프로세스는 요청 스코프 단발 | 컨테이너를 띄우므로 상태 공유 이득이 없고, 풀은 좌초 컨테이너 정리 책임을 서버에 넘긴다 |
| OpenAI 호환 경로 확장 (네이티브 게이트웨이 신설 안 함) | §6.1 실측 — 호환 경로가 `json_schema`와 `seed`를 모두 지원한다 |
| 로컬 SOURCE/SINK를 `ModelBaseEntry`로 승격 | 범위·단위·질문의 단일 출처를 만든다. `validate_parameters`의 이중 정의를 없앤다 |
| 토폴로지 계약을 데이터로 | 같은 규칙이 프롬프트·초안검증·manifest검증 세 곳에 흩어져 있다. 중복 제거이며 일반화가 아니다 |
| 해석해 대조를 테스트로 이동 | 그 계산은 이 모델에서만 성립한다. 런타임에는 모델 무관 불변식만 남긴다 |
| SES 재사용 매칭 제외 | 모델이 1건이라 재사용 판정을 검증할 대상이 없다 |
| `unfilledPolicy = ASK_AGAIN` | 생성 슬롯 3개가 전부 필수이고 기본값이 없다. 기본값으로 채우면 사용자가 답하지 않은 수치로 시뮬레이션이 돈다 |
