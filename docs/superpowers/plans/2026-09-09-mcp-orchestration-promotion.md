# MCP 오케스트레이션 경로 승격 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `experiments/queue-mcp`의 Python 실험 어댑터가 하는 일(LLM 구조 초안 → 검증 → MCP 모델 조회 → SES/템플릿 컴파일 → 대화 → PES → 외부 모델 실행)을 Spring 백엔드의 정식 경로로 옮긴다.

**Architecture:** 신규 모듈 `modules:mcp`(stdio JSON-RPC 클라이언트)와 `modules:design`(초안 생성·검증·컴파일)을 추가한다. 컴파일러 산출물을 기존 `SesRegistry`/`TemplateRegistry`에 등록해 이미 있는 등록 검증 28개를 LLM 산출물에 그대로 적용한다. 실행은 `ScenarioExecutor` 인터페이스에 `engine = "queue-mcp-batch"` 구현체를 하나 더 꽂아 기존 `devs-internal` 경로를 건드리지 않는다.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Gradle Kotlin DSL 멀티모듈, Jackson, JUnit 5 + AssertJ, WireMock 3.9.1(LLM 테스트), PostgreSQL + Flyway, MCP 2025-11-25 stdio

**Spec:** [docs/superpowers/specs/2026-09-09-mcp-orchestration-promotion-design.md](../specs/2026-09-09-mcp-orchestration-promotion-design.md)

**Branch:** `design/mcp-orchestration-promotion`

---

## 착수 전 차단 조건

**이 계획은 워킹트리에 미커밋 변경 35건이 있는 상태에서 작성되었다.** 스펙 §3.4가 그 목록을
담고 있다. 그중 다음이 이 계획과 직접 겹친다.

- `modules/api/.../dto/SessionResponse.java` — Task 10이 필드를 추가한다
- `modules/scenario/.../output/ResultFormatter.java` — Task 8이 5줄을 추가한다
- `modules/dialogue/.../control/DialogueController.java` (+256줄) — Task 10이 호출한다
- `modules/api/.../service/SessionFacade.java` — Task 10이 확장한다

**Task 1을 시작하기 전에 이 변경들을 커밋하거나 되돌려야 한다.** 그렇지 않으면 이 계획의
diff와 섞여 어느 변경이 무엇인지 구분할 수 없게 된다.

```bash
cd /c/Dev/subtask_v2
git status --short          # 35건이 0건이 되어야 한다
git branch --show-current   # design/mcp-orchestration-promotion 이어야 한다
```

## Global Constraints

- **Java 21.** 모든 신규 모듈은 루트 `subprojects` 블록의 toolchain을 상속한다. 별도 선언 금지.
- **모듈 빌드 파일은 `plugins { \`java-library\` }` 로 시작한다.** 기존 8개 모듈 전부 이 형태다.
- **의존 방향을 깨지 않는다.** `modules:template`은 `llm`·`mcp`를 모른다. `modules:scenario`는 `llm`을 모른다. `modules:mcp`는 `core-ses`만 의존한다.
- **MCP 프로토콜 버전은 `2025-11-25` 고정.** 다르면 실패한다.
- **Java는 Docker를 직접 호출하지 않는다.** 컨테이너 격리·이미지 라벨 검증·페이로드 상한은 `mcp/queue-models/mcp_server.py`에 남는다.
- **시간 단위는 `분`.** 토폴로지 계약의 `timeUnit`이 유일한 출처다.
- **골든 검증값** (도착간격 2분, 처리시간 3분, 작업 5건): 완료 5건, 손실 0건, 평균 대기 2분, 최대 대기 4분, 종료 t=17분, t=0 기준 이용률 15/17.
- **파라미터 범위**: `arrivalInterval` 0.01–10분, `totalJobs` 1–100건(정수), `serviceTime` 0.01–10분.
- **`horizon` 2000분**, `timeResolution` 0.01, `seed` 42, `mode` SINGLE.
- **LLM 시드 42, 온도 0.0**, `STRUCTURE_DRAFT`만 스키마 제약. 폴백 초안은 만들지 않는다.
- **기존 엔드포인트를 변경하지 않는다.** `/sessions`, `/sessions/{id}/answers`, `/sessions/{id}/scenario`, `/scenarios/{id}/runs`, `/templates`, `/ses-definitions`, `/models` 전부 그대로.
- **테스트 실행**: Git Bash에서 `./gradlew`, PowerShell에서 `.\gradlew.bat`. 로컬 Gradle 홈이 필요하면 `export GRADLE_USER_HOME="$USERPROFILE/.gradle"`.
- **커밋 메시지는 한국어**, 본문 끝에 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

---

## 태스크 순서

스펙 §9.4의 TDD 순서를 따른다 — `McpClient` → `McpModelContractValidator` → `SesCompiler`
→ `DraftValidator` → `McpBatchScenarioExecutor` → `DesignController`. Task 2(토폴로지 계약)와
Task 6(LLM 게이트웨이)이 그 사이에 선행 조건으로 들어간다.

| Task | 무엇 | 선행 |
|---|---|---|
| 1 | `modules:mcp` — MCP stdio 클라이언트 | 없음 |
| 2 | 토폴로지 계약 (`modules:template`) | 없음 |
| 3 | 모델 계약 통일 + MCP 계약 검증 | 2 |
| 4 | `SesCompiler` | 2, 3 |
| 5 | `DraftValidator` + `DraftPromptBuilder` | 2, 4 |
| 6 | LLM 게이트웨이 확장 (시드·스키마) | 없음 |
| 7 | `StructureDraftGenerator` + `DesignService` | 1, 2, 3, 4, 5, 6 |
| 8 | `McpBatchScenarioExecutor` | 1, 2, 4, 7 |
| 9 | MCP 감사로그 영속화 | 1 |
| 10 | `POST /api/v1/design` + Spring 배선 | 4, 7, 8, 9 |
| 11 | MCP 서버 승격 + 골든 통합 테스트 | 1–10 |

**앞의 여섯 개(1–6)는 외부 의존이 없어 순수 단위 테스트로 진행한다.** Docker 와 Ollama 가
필요한 것은 Task 11뿐이다. Task 1과 2, Task 6은 서로 독립이므로 순서를 바꿀 수 있다.

---

## File Structure

### 신규 모듈 `modules:mcp` (Task 1)

| 파일 | 책임 |
|---|---|
| `modules/mcp/build.gradle.kts` | `api(project(":modules:core-ses"))`, jackson-databind |
| `.../mcp/McpTransport.java` | 줄 단위 송수신 경계. 테스트가 프로세스 없이 대체할 수 있게 하는 유일한 이유 |
| `.../mcp/ProcessMcpTransport.java` | 실제 프로세스 spawn, stdout 리더 스레드, 종료 절차 |
| `.../mcp/McpClient.java` | JSON-RPC 핸드셰이크, `tools/list`, `tools/call`, 도구 허용목록 |
| `.../mcp/McpServerConfig.java` | `name`, `command`, `tools`, `timeouts` |
| `.../mcp/McpTimeouts.java` | `defaultMs`, `provisionMs`, `runMs` |
| `.../mcp/McpExchangeLog.java` | 교환 기록 경계 + `noop()` |
| `.../mcp/McpExchange.java` | 기록 한 건 |
| `.../mcp/McpToolException.java` | `isError: true` 응답 |
| `.../mcp/McpUnavailableException.java` | 프로세스 실패, 버전 불일치, 타임아웃, 미선언 도구 |
| `.../mcp/McpClientFactory.java` | 요청 스코프 클라이언트 + 동시 실행 세마포어 (Task 7) |

`McpClientFactory` 가 `modules:mcp` 에 있는 이유는 `modules:design`(Task 7)과
`modules:scenario`(Task 8)가 둘 다 이것을 쓰기 때문이다. `design` 에 두면 `scenario` 가
`design` 을 의존해야 한다. 세마포어와 프로세스 수명은 어차피 `mcp` 의 관심사다.

### 변경 — `modules:template` (Task 2)

토폴로지 계약이 `modules:template` 에 사는 이유는 `modules:design`(Task 4·5·7)과
`modules:scenario`(Task 8)가 **둘 다** 이것을 읽기 때문이다. `design` 에 두면 `scenario` 가
`design` 을 의존해야 하고, 그러면 `scenario -> design -> llm` 으로 "scenario 는 llm 을
모른다"는 기존 성질이 깨진다. 두 모듈은 이미 `template` 을 `api` 로 의존한다.

| 파일 | 책임 |
|---|---|
| `.../template/topology/TopologyContract.java` | 허용 토폴로지 계약 (데이터) |
| `.../template/topology/RoleSpec.java` | 역할 하나 — `role`, `modelRef`, `capability`, `external` |
| `.../template/topology/TopologyContractRegistry.java` | 계약 조회 |
| `.../template/topology/TopologyContractNotFoundException.java` | 미등록 계약 |

### 신규 모듈 `modules:design` (Task 3–7)

| 파일 | 책임 |
|---|---|
| `modules/design/build.gradle.kts` | `api` core-ses·template, `implementation` llm·mcp |
| `.../design/contract/ModelContract.java` | 로컬·외부 모델의 **공통** 계약 표현 |
| `.../design/contract/ParamContract.java` | 파라미터 하나 — 타입·단위·범위·질문·기본값 |
| `.../design/contract/PortContract.java` | 포트 하나 — 이름·데이터타입·단위 |
| `.../design/contract/McpModelContractValidator.java` | MCP 카탈로그 구조 검증 (7코드) |
| `.../design/compile/SesCompiler.java` | 초안 + 계약 → SES + 템플릿 |
| `.../design/compile/CompiledDesign.java` | 컴파일 산출물 |
| `.../design/draft/StructureDraft.java` | LLM 산출 초안 |
| `.../design/draft/DraftNode.java`, `DraftConnection.java` | 초안 구성요소 |
| `.../design/draft/DraftValidator.java` | 결정론적 초안 검증 (7코드) |
| `.../design/draft/DraftPromptBuilder.java` | 계약에서 프롬프트 생성 |
| `.../design/draft/StructureDraftGenerator.java` | LLM 호출 |
| `.../design/DesignService.java` | 7단계 오케스트레이션 |
| `.../design/DesignResult.java` | 서비스 산출물 |
| `.../design/DesignFailedException.java` | 단계별 실패 (HTTP 상태 매핑용) |

### 변경 — `modules:llm` (Task 6)

| 파일 | 변경 |
|---|---|
| `.../llm/gateway/Purpose.java` | `STRUCTURE_DRAFT` 추가, 클래스 javadoc 수정 |
| `.../llm/gateway/LlmProperties.java` | `seed`, `temperatureByPurpose`, `schemaConstrainedPurposes` 3필드 추가 |
| `.../llm/gateway/HttpLlmGateway.java` | `requestBody` 시그니처에 `Purpose`·`jsonSchema` 추가, `complete()`가 스키마 전달 |
| `.../llm/gateway/OpenAiCompatibleLlmGateway.java` | `seed`, 목적별 온도, `json_schema`, 4xx 폴백 |
| `.../llm/gateway/AnthropicLlmGateway.java` | 새 파라미터 무시 (시그니처만 맞춤) |

### 변경 — `modules:scenario` (Task 8)

| 파일 | 변경 |
|---|---|
| `modules/scenario/build.gradle.kts` | `implementation(project(":modules:mcp"))` |
| `.../scenario/exec/mcp/McpBatchScenarioExecutor.java` | 신규. `supports("queue-mcp-batch")` |
| `.../scenario/exec/mcp/SimulatorManifest.java` | 신규. `simulator.json`의 Java 대응 |
| `.../scenario/exec/mcp/ManifestAssembler.java` | 신규. PES → manifest (`PesFlattener` 미사용) |
| `.../scenario/exec/mcp/QueueInvariants.java` | 신규. 런타임 불변식 5개 |
| `.../scenario/exec/mcp/ArrivalEvent.java`, `DepartureEvent.java` | 신규 |
| `.../scenario/output/ResultFormatter.java` | 대기행렬 지표 5줄 추가 |

### 변경 — `modules:persistence` (Task 9), `modules:api` (Task 10), `app` (Task 2·6·9·10)

| 파일 | 변경 |
|---|---|
| `.../persistence/entity/McpExchangeEntity.java` | 신규 |
| `.../persistence/repository/McpExchangeRepository.java` | 신규 |
| `.../persistence/adapter/JpaMcpExchangeLog.java` | 신규 |
| `app/src/main/resources/db/migration/V5__mcp_exchange.sql` | 신규 |
| `.../api/controller/DesignController.java` | 신규 |
| `.../api/dto/DesignView.java` | 신규 |
| `.../api/dto/SessionResponse.java` | `design` 필드 추가 |
| `.../api/service/SessionFacade.java` | `createFromDesign(...)` 추가 |
| `settings.gradle.kts` | `modules:mcp`, `modules:design` 추가 |
| `app/src/main/resources/seed/queue.topology.json` | 신규 |
| `app/src/main/resources/seed/queue.models.json` | 신규 |
| `app/src/main/resources/application.yml`, `-ollama.yml` | `mcp.*`, `llm.seed`, `llm.temperature-by-purpose`, `llm.schema-constrained-purposes` |
| `app/.../config/LlmConfiguration.java` | 새 `@Value` 3개 |
| `app/.../config/McpConfiguration.java` | 신규. `McpProperties` 바인딩 |

### 이동 (Task 11)

`experiments/queue-mcp/{mcp_server.py,catalog.json,processor/}` → `mcp/queue-models/`.
`experiments/queue-mcp/`에는 `verify_live.py`와 `README.md`만 남는다.

---

## 공통 인터페이스 표면

**모든 태스크가 이 이름들을 그대로 쓴다.** 태스크를 순서 없이 읽는 구현자를 위해 여기 모아 둔다.

```java
// modules:mcp
public interface McpTransport extends AutoCloseable {
    void send(String jsonLine);
    String receive(long timeoutMs);   // null = 상대가 종료했다
    void close();
}

public final class McpClient implements AutoCloseable {
    public static McpClient handshake(McpServerConfig config, McpTransport transport,
                                      McpExchangeLog log);
    public List<String> listTools();
    public Map<String, Object> callTool(String tool, Map<String, Object> arguments,
                                        long timeoutMs);
    public void close();
}

public record McpServerConfig(String name, List<String> command, List<String> tools,
                              McpTimeouts timeouts) {}
public record McpTimeouts(long defaultMs, long provisionMs, long runMs) {}
public record McpExchange(String server, String direction, String method,
                          String toolName, String payload) {}
public interface McpExchangeLog {
    void record(McpExchange exchange);
    static McpExchangeLog noop() { return e -> { }; }
}

public class McpClientFactory {                       // Task 7
    public McpClientFactory(Map<String, McpServerConfig> servers, McpExchangeLog log,
                            int maxConcurrent);
    public McpServerConfig config(String serverName);
    public McpClient open(String serverName);         // close() 가 세마포어를 반납한다
}

// modules:design — 계약
public record PortContract(String name, String dataType, String unit) {}
public record ParamContract(String name, VarType type, String unit, boolean required,
                            Range range, String question, Object defaultValue) {}
public record ModelContract(String modelId, String origin, String timeUnit,
                            List<PortContract> in, List<PortContract> out,
                            List<ParamContract> params) {
    public static ModelContract fromModelBase(ModelBaseEntry entry);
    public static ModelContract fromMcp(Map<String, Object> catalog);
}

// modules:template (org.hanbat.ses.template.topology) — 토폴로지
public record RoleSpec(String role, String modelRef, String capability, boolean external) {}
public record TopologyContract(String contractId, int nodeCount, List<RoleSpec> roles,
                               List<List<String>> requiredConnections, boolean allowFeedback,
                               List<String> reservedNodeIds, String rootEntityName,
                               String aspectName, String timeUnit, double horizon,
                               List<String> unsupported) {
    public RoleSpec role(String name);
    public Set<String> roleNames();
}

// modules:design — 초안
public record DraftNode(String id, String name, String role) {}
public record DraftConnection(String from, String to) {}
public record StructureDraft(String title, boolean supported, String reason,
                             List<DraftNode> nodes, List<DraftConnection> connections,
                             List<String> assumptions) {}

// modules:design — 산출물
public record CompiledDesign(SesDefinition ses, SubtaskTemplate template,
                             List<RoleContract> contracts) {                 // RoleContract(role, contract)
    public ModelContract contractFor(String role);
}
public record DesignResult(CompiledDesign design, StructureDraft draft,
                           String contractId, String llmModel, double temperature,
                           Long seed, boolean schemaConstrained) {}

// modules:scenario — 실행
public record ArrivalEvent(String id, double at) {}
public record DepartureEvent(String id, double arrivedAt, double startedAt,
                             double departedAt, double wait) {}
public record SimulatorManifest(String format, UUID scenarioId, String execution,
                                String termination, String timeUnit, double maxSimulationTime,
                                Map<String, Object> parameters, List<PesNode> nodes,
                                List<CouplingSpec> connections,
                                Map<String, Object> externalModel, String contractId) {}
```

---

### Task 1: `modules:mcp` — MCP stdio 클라이언트

**Files:**
- Modify: `settings.gradle.kts` (`"modules:mcp"` 추가)
- Create: `modules/mcp/build.gradle.kts`
- Create: `modules/mcp/src/main/java/org/hanbat/ses/mcp/McpTransport.java`
- Create: `modules/mcp/src/main/java/org/hanbat/ses/mcp/ProcessMcpTransport.java`
- Create: `modules/mcp/src/main/java/org/hanbat/ses/mcp/McpClient.java`
- Create: `modules/mcp/src/main/java/org/hanbat/ses/mcp/McpServerConfig.java`
- Create: `modules/mcp/src/main/java/org/hanbat/ses/mcp/McpTimeouts.java`
- Create: `modules/mcp/src/main/java/org/hanbat/ses/mcp/McpExchange.java`
- Create: `modules/mcp/src/main/java/org/hanbat/ses/mcp/McpExchangeLog.java`
- Create: `modules/mcp/src/main/java/org/hanbat/ses/mcp/McpToolException.java`
- Create: `modules/mcp/src/main/java/org/hanbat/ses/mcp/McpUnavailableException.java`
- Test: `modules/mcp/src/test/java/org/hanbat/ses/mcp/McpClientTest.java`
- Test: `modules/mcp/src/test/java/org/hanbat/ses/mcp/ScriptedTransport.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `McpClient.handshake(McpServerConfig, McpTransport, McpExchangeLog) -> McpClient`,
  `McpClient.listTools() -> List<String>`,
  `McpClient.callTool(String tool, Map<String,Object> arguments, long timeoutMs) -> Map<String,Object>`,
  `McpClient.PROTOCOL_VERSION = "2025-11-25"`,
  `McpServerConfig(String name, List<String> command, List<String> tools, McpTimeouts timeouts)` + `allows(String)`,
  `McpTimeouts(long defaultMs, long provisionMs, long runMs)` + `defaults()` + `forTool(String)`,
  `McpExchange(String server, String direction, String method, String toolName, String payload)`,
  `McpExchangeLog.record(McpExchange)` + `McpExchangeLog.noop()`,
  `McpToolException(String tool, String message)`, `McpUnavailableException(String)`

**왜 `McpTransport`를 분리하는가.** 클라이언트 테스트가 Python 프로세스를 띄우면 CI가 Python
유무에 좌우되고, 타임아웃·조기 종료·id 불일치를 재현할 방법이 없다. 줄 단위 송수신만 경계로
빼면 그 전부를 인메모리로 재현할 수 있다.

- [ ] **Step 1: 모듈을 빌드에 등록한다**

`settings.gradle.kts`의 `include(...)` 목록에서 `"modules:core-ses",` 다음 줄에 추가:

```kotlin
    "modules:mcp",
```

`modules/mcp/build.gradle.kts` 생성:

```kotlin
plugins { `java-library` }

dependencies {
    api(project(":modules:core-ses"))
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

- [ ] **Step 2: 값 타입과 예외를 만든다**

`McpTimeouts.java`:

```java
package org.hanbat.ses.mcp;

/** 도구별 타임아웃. provision 은 Docker 빌드, run 은 컨테이너 실행이라 자릿수가 다르다. */
public record McpTimeouts(long defaultMs, long provisionMs, long runMs) {

    public McpTimeouts {
        if (defaultMs <= 0) {
            defaultMs = 30_000L;
        }
        if (provisionMs <= 0) {
            provisionMs = 600_000L;
        }
        if (runMs <= 0) {
            runMs = 60_000L;
        }
    }

    public static McpTimeouts defaults() {
        return new McpTimeouts(30_000L, 600_000L, 60_000L);
    }

    public long forTool(String tool) {
        return switch (tool) {
            case "provision_model" -> provisionMs;
            case "run_processor" -> runMs;
            default -> defaultMs;
        };
    }
}
```

`McpServerConfig.java`:

```java
package org.hanbat.ses.mcp;

import java.util.List;

/**
 * MCP 서버 하나의 설정.
 *
 * @param tools 호출을 허용하는 도구 이름. 여기 없는 도구는 클라이언트가 거부한다 —
 *              서버가 무엇을 노출하든 우리가 부를 것은 설정이 정한다.
 */
public record McpServerConfig(String name, List<String> command, List<String> tools,
                              McpTimeouts timeouts) {

    public McpServerConfig {
        command = command == null ? List.of() : List.copyOf(command);
        tools = tools == null ? List.of() : List.copyOf(tools);
        timeouts = timeouts == null ? McpTimeouts.defaults() : timeouts;
    }

    public boolean allows(String tool) {
        return tools.contains(tool);
    }
}
```

`McpExchange.java`:

```java
package org.hanbat.ses.mcp;

/**
 * 교환 기록 한 건. 실험의 mcp.jsonl 한 줄에 대응한다.
 *
 * @param direction "request" 또는 "response"
 */
public record McpExchange(String server, String direction, String method,
                          String toolName, String payload) {
}
```

`McpExchangeLog.java`:

```java
package org.hanbat.ses.mcp;

/**
 * MCP 교환 기록 경계.
 *
 * <p>기록 실패가 본 흐름을 막아서는 안 된다 — 구현체는 예외를 던지지 않는다.
 */
public interface McpExchangeLog {

    void record(McpExchange exchange);

    static McpExchangeLog noop() {
        return exchange -> { };
    }
}
```

`McpToolException.java`:

```java
package org.hanbat.ses.mcp;

/** 도구가 isError: true 를 돌려준 경우. 프로토콜은 정상이고 호출만 실패했다. */
public class McpToolException extends RuntimeException {

    private final String tool;

    public McpToolException(String tool, String message) {
        super("MCP 도구 " + tool + " 실패: " + message);
        this.tool = tool;
    }

    public String tool() {
        return tool;
    }
}
```

`McpUnavailableException.java`:

```java
package org.hanbat.ses.mcp;

/** 프로세스 실패, 프로토콜 버전 불일치, 타임아웃, 미선언 도구 호출. */
public class McpUnavailableException extends RuntimeException {

    public McpUnavailableException(String message) {
        super(message);
    }

    public McpUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: 전송 경계를 만든다**

`McpTransport.java`:

```java
package org.hanbat.ses.mcp;

/**
 * MCP 줄 단위 송수신 경계.
 *
 * <p>이 인터페이스가 있는 이유는 교체가 아니라 <b>테스트</b>다. 프로세스를 직접 띄우면
 * 타임아웃과 조기 종료를 재현할 수 없고, CI 가 Python 유무에 좌우된다.
 */
public interface McpTransport extends AutoCloseable {

    void send(String jsonLine);

    /** @return 한 줄. 상대가 종료했으면 null. */
    String receive(long timeoutMs);

    @Override
    void close();
}
```

- [ ] **Step 4: 실패하는 테스트를 쓴다**

`ScriptedTransport.java` (테스트 픽스처):

```java
package org.hanbat.ses.mcp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 미리 정한 응답을 순서대로 돌려주는 전송. 보낸 줄은 모두 기록한다. */
final class ScriptedTransport implements McpTransport {

    /** null 을 큐에 담을 수 없어 EOF 를 표식으로 쓴다. */
    private static final String EOF_MARKER = "<<eof>>";

    private final Deque<String> replies = new ArrayDeque<>();
    private final List<String> sent = new ArrayList<>();
    private boolean closed;

    ScriptedTransport reply(String jsonLine) {
        replies.add(jsonLine);
        return this;
    }

    /** 상대가 응답 없이 죽은 상황을 만든다. */
    ScriptedTransport replyEof() {
        replies.add(EOF_MARKER);
        return this;
    }

    List<String> sent() {
        return List.copyOf(sent);
    }

    boolean closed() {
        return closed;
    }

    @Override
    public void send(String jsonLine) {
        sent.add(jsonLine);
    }

    @Override
    public String receive(long timeoutMs) {
        if (replies.isEmpty()) {
            throw new McpUnavailableException("MCP 응답이 시간 안에 오지 않았습니다.");
        }
        String line = replies.poll();
        return EOF_MARKER.equals(line) ? null : line;
    }

    @Override
    public void close() {
        closed = true;
    }
}
```

`McpClientTest.java`:

```java
package org.hanbat.ses.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class McpClientTest {

    private static final McpServerConfig CONFIG = new McpServerConfig(
            "queue-models", List.of("python", "server.py"),
            List.of("search_models", "describe_model", "provision_model", "run_processor"),
            McpTimeouts.defaults());

    private static String initOk() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-11-25\","
                + "\"capabilities\":{\"tools\":{}},"
                + "\"serverInfo\":{\"name\":\"queue-experiment-models\",\"version\":\"1.0.0\"}}}";
    }

    private static String toolsOk() {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":["
                + "{\"name\":\"search_models\"},{\"name\":\"describe_model\"},"
                + "{\"name\":\"provision_model\"},{\"name\":\"run_processor\"}]}}";
    }

    @Test
    @DisplayName("핸드셰이크가 initialize, initialized 알림, tools/list 순서로 진행된다")
    void handshakeOrder() {
        ScriptedTransport t = new ScriptedTransport().reply(initOk()).reply(toolsOk());

        try (McpClient client = McpClient.handshake(CONFIG, t, McpExchangeLog.noop())) {
            assertThat(client.listTools()).containsExactly(
                    "search_models", "describe_model", "provision_model", "run_processor");
        }

        assertThat(t.sent()).hasSize(3);
        assertThat(t.sent().get(0)).contains("\"method\":\"initialize\"").contains("2025-11-25");
        assertThat(t.sent().get(1)).contains("notifications/initialized")
                .doesNotContain("\"id\"");
        assertThat(t.sent().get(2)).contains("\"method\":\"tools/list\"");
        assertThat(t.closed()).isTrue();
    }

    @Test
    @DisplayName("프로토콜 버전이 다르면 실패한다")
    void rejectsOtherProtocolVersion() {
        ScriptedTransport t = new ScriptedTransport().reply(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\"}}");

        assertThatThrownBy(() -> McpClient.handshake(CONFIG, t, McpExchangeLog.noop()))
                .isInstanceOf(McpUnavailableException.class)
                .hasMessageContaining("2025-11-25");
        assertThat(t.closed()).isTrue();
    }

    @Test
    @DisplayName("structuredContent 를 그대로 돌려준다")
    void returnsStructuredContent() {
        ScriptedTransport t = new ScriptedTransport().reply(initOk()).reply(toolsOk())
                .reply("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"isError\":false,"
                        + "\"structuredContent\":{\"modelId\":\"external-simpy-fifo\","
                        + "\"timeUnit\":\"분\"}}}");

        try (McpClient client = McpClient.handshake(CONFIG, t, McpExchangeLog.noop())) {
            Map<String, Object> result = client.callTool("describe_model",
                    Map.of("modelId", "external-simpy-fifo"), 1000L);

            assertThat(result).containsEntry("modelId", "external-simpy-fifo")
                    .containsEntry("timeUnit", "분");
        }
    }

    @Test
    @DisplayName("isError 응답은 McpToolException 이 된다")
    void toolErrorBecomesException() {
        ScriptedTransport t = new ScriptedTransport().reply(initOk()).reply(toolsOk())
                .reply("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"isError\":true,"
                        + "\"content\":[{\"type\":\"text\",\"text\":"
                        + "\"Model is not in the execution allowlist\"}]}}");

        try (McpClient client = McpClient.handshake(CONFIG, t, McpExchangeLog.noop())) {
            assertThatThrownBy(() -> client.callTool("run_processor", Map.of(), 1000L))
                    .isInstanceOf(McpToolException.class)
                    .hasMessageContaining("allowlist");
        }
    }

    @Test
    @DisplayName("설정에 없는 도구는 전송 전에 거부한다")
    void rejectsUndeclaredTool() {
        ScriptedTransport t = new ScriptedTransport().reply(initOk()).reply(toolsOk());

        try (McpClient client = McpClient.handshake(CONFIG, t, McpExchangeLog.noop())) {
            assertThatThrownBy(() -> client.callTool("delete_everything", Map.of(), 1000L))
                    .isInstanceOf(McpUnavailableException.class)
                    .hasMessageContaining("delete_everything");
        }
        assertThat(t.sent()).hasSize(3);
    }

    @Test
    @DisplayName("상대가 응답 없이 죽으면 McpUnavailableException")
    void eofFails() {
        ScriptedTransport t = new ScriptedTransport().replyEof();

        assertThatThrownBy(() -> McpClient.handshake(CONFIG, t, McpExchangeLog.noop()))
                .isInstanceOf(McpUnavailableException.class)
                .hasMessageContaining("종료");
    }

    @Test
    @DisplayName("응답 id 가 요청 id 와 다르면 실패한다")
    void mismatchedIdFails() {
        ScriptedTransport t = new ScriptedTransport().reply(
                "{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{\"protocolVersion\":\"2025-11-25\"}}");

        assertThatThrownBy(() -> McpClient.handshake(CONFIG, t, McpExchangeLog.noop()))
                .isInstanceOf(McpUnavailableException.class)
                .hasMessageContaining("id");
    }

    @Test
    @DisplayName("요청과 응답이 모두 기록된다")
    void logsBothDirections() {
        ScriptedTransport t = new ScriptedTransport().reply(initOk()).reply(toolsOk());
        List<McpExchange> log = new ArrayList<>();

        try (McpClient client = McpClient.handshake(CONFIG, t, log::add)) {
            assertThat(client.listTools()).isNotEmpty();
        }

        assertThat(log).extracting(McpExchange::direction)
                .containsExactly("request", "response", "request", "request", "response");
        assertThat(log).allMatch(e -> "queue-models".equals(e.server()));
    }
}
```

- [ ] **Step 5: 테스트가 실패하는 것을 확인한다**

Run: `./gradlew :modules:mcp:test`
Expected: 컴파일 실패 — `McpClient` 심볼을 찾을 수 없음

- [ ] **Step 6: `McpClient` 를 구현한다**

```java
package org.hanbat.ses.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP stdio 클라이언트 (2025-11-25).
 *
 * <p>요청 id 를 1부터 올리고 응답 id 가 다르면 실패한다. stdio 는 단일 스트림이라
 * 순서가 어긋난 응답을 받았다는 것은 곧 서버가 우리가 모르는 상태에 있다는 뜻이다.
 *
 * <p>도구 허용목록은 설정이 정한다. 서버의 tools/list 결과가 더 많아도 설정에 없는 것은
 * 부르지 않는다 — 서버가 늘린 기능이 조용히 실행 경로로 들어오지 않게 한다.
 */
public final class McpClient implements AutoCloseable {

    public static final String PROTOCOL_VERSION = "2025-11-25";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpServerConfig config;
    private final McpTransport transport;
    private final McpExchangeLog log;
    private final List<String> serverTools;
    private int counter;

    private McpClient(McpServerConfig config, McpTransport transport, McpExchangeLog log,
                      List<String> serverTools) {
        this.config = config;
        this.transport = transport;
        this.log = log;
        this.serverTools = serverTools;
    }

    public static McpClient handshake(McpServerConfig config, McpTransport transport,
                                      McpExchangeLog log) {
        McpClient client = new McpClient(config, transport, log, new ArrayList<>());
        try {
            JsonNode init = client.request("initialize", Map.of(
                            "protocolVersion", PROTOCOL_VERSION,
                            "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "ses-scenario-server",
                                    "version", "1.0.0")),
                    config.timeouts().defaultMs());

            String version = init.path("protocolVersion").asText("");
            if (!PROTOCOL_VERSION.equals(version)) {
                throw new McpUnavailableException("지원하지 않는 MCP 프로토콜 버전입니다: "
                        + version + " (필요: " + PROTOCOL_VERSION + ")");
            }

            client.notifyServer("notifications/initialized");

            JsonNode tools = client.request("tools/list", Map.of(),
                    config.timeouts().defaultMs());
            tools.path("tools").forEach(t -> client.serverTools.add(t.path("name").asText()));
            return client;
        } catch (RuntimeException e) {
            transport.close();
            throw e;
        }
    }

    /** 서버가 노출한 도구 중 설정이 허용한 것. */
    public List<String> listTools() {
        return serverTools.stream().filter(config::allows).toList();
    }

    public Map<String, Object> callTool(String tool, Map<String, Object> arguments,
                                        long timeoutMs) {
        if (!config.allows(tool)) {
            throw new McpUnavailableException("설정에 선언되지 않은 MCP 도구입니다: " + tool
                    + " (허용: " + config.tools() + ")");
        }
        JsonNode result = request("tools/call",
                Map.of("name", tool, "arguments", arguments), timeoutMs);

        if (result.path("isError").asBoolean(false)) {
            throw new McpToolException(tool, textContent(result));
        }
        JsonNode structured = result.path("structuredContent");
        if (structured.isMissingNode() || structured.isNull()) {
            throw new McpUnavailableException(
                    "MCP 도구 " + tool + " 가 structuredContent 를 돌려주지 않았습니다.");
        }
        return MAPPER.convertValue(structured,
                new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    @Override
    public void close() {
        transport.close();
    }

    // ------------------------------------------------------------ 내부

    private JsonNode request(String method, Map<String, Object> params, long timeoutMs) {
        int id = ++counter;
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("method", method);
        envelope.put("params", params);

        String line = write(envelope);
        log.record(new McpExchange(config.name(), "request", method, toolNameOf(params), line));
        transport.send(line);

        String reply = transport.receive(timeoutMs);
        if (reply == null) {
            throw new McpUnavailableException("MCP 서버가 응답 전에 종료되었습니다.");
        }
        log.record(new McpExchange(config.name(), "response", method, toolNameOf(params), reply));

        JsonNode node = read(reply);
        if (node.path("id").asInt(-1) != id) {
            throw new McpUnavailableException("MCP 응답 id 가 요청과 다릅니다: 기대 " + id
                    + ", 수신 " + node.path("id"));
        }
        if (node.has("error")) {
            throw new McpUnavailableException(
                    "MCP 오류 응답: " + node.path("error").path("message").asText());
        }
        return node.path("result");
    }

    private void notifyServer(String method) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("method", method);

        String line = write(envelope);
        log.record(new McpExchange(config.name(), "request", method, null, line));
        transport.send(line);
    }

    private static String toolNameOf(Map<String, Object> params) {
        Object name = params.get("name");
        return name instanceof String s ? s : null;
    }

    private static String textContent(JsonNode result) {
        JsonNode content = result.path("content");
        if (content.isArray() && !content.isEmpty()) {
            return content.get(0).path("text").asText("(사유 없음)");
        }
        return "(사유 없음)";
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new McpUnavailableException("MCP 요청을 직렬화하지 못했습니다.", e);
        }
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new McpUnavailableException("MCP 응답을 해석하지 못했습니다: " + json, e);
        }
    }
}
```

- [ ] **Step 7: 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :modules:mcp:test`
Expected: 8개 테스트 PASS

- [ ] **Step 8: `ProcessMcpTransport` 를 구현한다**

```java
package org.hanbat.ses.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 자식 프로세스의 stdin/stdout 을 줄 단위로 잇는다.
 *
 * <p>stdout 을 별도 스레드가 읽어 큐에 넣는다. 같은 스레드에서 읽으면 타임아웃을 걸 수
 * 없고, 서버가 응답하지 않을 때 요청 스레드가 영구히 잠긴다.
 *
 * <p>stderr 도 읽어서 버린다. 읽지 않으면 파이프 버퍼가 차서 자식이 쓰기에서 멈춘다 —
 * 로그를 남기는 서버에서 재현되는, 원인을 짚기 어려운 정지다.
 */
public final class ProcessMcpTransport implements McpTransport {

    private static final String EOF_MARKER = "<<eof>>";

    private final Process process;
    private final Writer stdin;
    private final BlockingQueue<String> lines = new ArrayBlockingQueue<>(256);

    public ProcessMcpTransport(List<String> command) {
        try {
            this.process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new McpUnavailableException(
                    "MCP 서버 프로세스를 시작하지 못했습니다: " + command, e);
        }
        this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        pump(process.getInputStream(), true);
        pump(process.getErrorStream(), false);
    }

    private void pump(InputStream stream, boolean collect) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (collect && !lines.offer(line)) {
                        return;
                    }
                }
            } catch (IOException ignored) {
                // 프로세스 종료 시 정상적으로 발생한다.
            } finally {
                if (collect) {
                    lines.offer(EOF_MARKER);
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void send(String jsonLine) {
        try {
            stdin.write(jsonLine);
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            throw new McpUnavailableException("MCP 서버에 쓰지 못했습니다.", e);
        }
    }

    @Override
    public String receive(long timeoutMs) {
        try {
            String line = lines.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (line == null) {
                throw new McpUnavailableException(
                        "MCP 응답이 " + timeoutMs + "ms 안에 오지 않았습니다.");
            }
            return EOF_MARKER.equals(line) ? null : line;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpUnavailableException("MCP 응답 대기가 중단되었습니다.", e);
        }
    }

    /** 실험의 종료 절차와 같다 — stdin 을 닫고 3초 기다린 뒤 강제 종료. */
    @Override
    public void close() {
        try {
            stdin.close();
        } catch (IOException ignored) {
            // 이미 닫혔거나 프로세스가 죽었다.
        }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 9: 기존 모듈이 깨지지 않는지 확인한다**

Run: `./gradlew :modules:mcp:test :modules:core-ses:test`
Expected: 모두 PASS

- [ ] **Step 10: 커밋**

```bash
git add settings.gradle.kts modules/mcp
git commit -m "modules:mcp 추가 - MCP stdio 클라이언트

MCP 2025-11-25 stdio JSON-RPC 클라이언트. 핸드셰이크, tools/list,
tools/call, 도구 허용목록, 교환 기록.

McpTransport 로 송수신을 분리해 타임아웃/조기 종료/id 불일치를
프로세스 없이 테스트한다. ProcessMcpTransport 는 stderr 도 읽어 버려
파이프 버퍼가 차서 자식이 멈추는 것을 막는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: 토폴로지 계약을 `modules:template` 에 추가

**Files:**
- Create: `modules/template/src/main/java/org/hanbat/ses/template/topology/RoleSpec.java`
- Create: `modules/template/src/main/java/org/hanbat/ses/template/topology/TopologyContract.java`
- Create: `modules/template/src/main/java/org/hanbat/ses/template/topology/TopologyContractRegistry.java`
- Create: `modules/template/src/main/java/org/hanbat/ses/template/topology/TopologyContractNotFoundException.java`
- Create: `app/src/main/resources/seed/queue.topology.json`
- Test: `modules/template/src/test/java/org/hanbat/ses/template/topology/TopologyContractTest.java`
- Test: `modules/template/src/test/resources/queue.topology.json` (본 파일과 동일 내용)

**왜 `modules:template` 인가.** `modules:design`(Task 4·5·7)과 `modules:scenario`(Task 8)가
둘 다 이 계약을 읽는다. `design` 에 두면 `scenario` 가 `design` 을 의존해야 하고, 그러면
`scenario -> design -> llm` 으로 "scenario 는 llm 을 모른다"는 기존 성질이 깨진다.
두 모듈은 이미 `template` 을 `api` 로 의존하므로 새 의존이 생기지 않는다.

`modules:template` 은 이미 `jackson-databind` 를 `implementation` 으로 갖고 있어
빌드 파일 변경이 필요 없다.

**Interfaces:**
- Consumes: 없음
- Produces: `RoleSpec(String role, String modelRef, String capability, boolean external)`,
  `TopologyContract(String contractId, int nodeCount, List<RoleSpec> roles, List<List<String>> requiredConnections, boolean allowFeedback, List<String> reservedNodeIds, String rootEntityName, String aspectName, String timeUnit, double horizon, List<String> unsupported)`
  + `role(String) -> RoleSpec`, `roleNames() -> Set<String>`, `connectionPairs() -> Set<String>`, `externalRole() -> RoleSpec`,
  `TopologyContractRegistry.load(InputStream) -> TopologyContract`,
  `TopologyContractRegistry.find(String contractId) -> Optional<TopologyContract>`,
  `TopologyContractRegistry.require(String contractId) -> TopologyContract`

**왜 계약을 데이터로 두는가.** 현재 같은 규칙이 세 곳에 흩어져 있다 —
`experiment.py`의 LLM 프롬프트 문자열, `validate_draft`의 하드코딩, `validate_manifest`의
상수 비교. Task 5(`DraftValidator`), Task 4(`SesCompiler`), Task 8(manifest 검증)이 **같은
이 파일 하나**를 읽게 만드는 것이 목적이다. 일반화가 아니라 중복 제거다. 계약은 지금 하나뿐이고
다중 계약 선택은 만들지 않는다.

- [ ] **Step 1: 계약 데이터 파일을 만든다**

`app/src/main/resources/seed/queue.topology.json`:

```json
{
  "contractId": "fifo-single-server",
  "nodeCount": 3,
  "roles": [
    { "role": "SOURCE",    "modelRef": "queue-local-source", "external": false },
    { "role": "PROCESSOR", "capability": "fifo-single-server", "external": true },
    { "role": "SINK",      "modelRef": "queue-local-sink",   "external": false }
  ],
  "requiredConnections": [ ["SOURCE", "PROCESSOR"], ["PROCESSOR", "SINK"] ],
  "allowFeedback": false,
  "reservedNodeIds": ["queue-root", "queue-components"],
  "rootEntityName": "대기행렬",
  "aspectName": "구성",
  "timeUnit": "분",
  "horizon": 2000,
  "unsupported": ["다중 서버", "우선순위", "확률분포", "피드백", "유한 대기실"]
}
```

같은 내용을 `modules/template/src/test/resources/queue.topology.json` 에도 복사한다.
테스트가 `app` 모듈 리소스에 의존하면 `modules:template`이 `app`을 거꾸로 의존하게 된다.

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`TopologyContractTest.java`:

```java
package org.hanbat.ses.template.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TopologyContractTest {

    private TopologyContract contract;

    @BeforeEach
    void setUp() {
        try (InputStream in = getClass().getResourceAsStream("/queue.topology.json")) {
            contract = TopologyContractRegistry.load(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("계약 JSON 을 읽어 값을 모두 채운다")
    void loadsAllFields() {
        assertThat(contract.contractId()).isEqualTo("fifo-single-server");
        assertThat(contract.nodeCount()).isEqualTo(3);
        assertThat(contract.rootEntityName()).isEqualTo("대기행렬");
        assertThat(contract.aspectName()).isEqualTo("구성");
        assertThat(contract.timeUnit()).isEqualTo("분");
        assertThat(contract.horizon()).isEqualTo(2000.0);
        assertThat(contract.allowFeedback()).isFalse();
        assertThat(contract.reservedNodeIds())
                .containsExactly("queue-root", "queue-components");
        assertThat(contract.unsupported()).contains("다중 서버", "피드백");
    }

    @Test
    @DisplayName("역할 이름 집합과 역할 조회")
    void rolesAreAddressable() {
        assertThat(contract.roleNames()).containsExactlyInAnyOrder(
                "SOURCE", "PROCESSOR", "SINK");
        assertThat(contract.role("SOURCE").modelRef()).isEqualTo("queue-local-source");
        assertThat(contract.role("SOURCE").external()).isFalse();
        assertThat(contract.role("PROCESSOR").capability()).isEqualTo("fifo-single-server");
        assertThat(contract.role("PROCESSOR").external()).isTrue();
        assertThat(contract.role("PROCESSOR").modelRef()).isNull();
    }

    @Test
    @DisplayName("외부 모델을 담당하는 역할이 하나로 정해진다")
    void externalRoleIsSingular() {
        assertThat(contract.externalRole().role()).isEqualTo("PROCESSOR");
    }

    @Test
    @DisplayName("없는 역할을 물으면 실패한다")
    void unknownRoleFails() {
        assertThatThrownBy(() -> contract.role("GATEWAY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GATEWAY");
    }

    @Test
    @DisplayName("배선 쌍을 비교 가능한 문자열 집합으로 낸다")
    void connectionPairsAreComparable() {
        assertThat(contract.connectionPairs())
                .containsExactlyInAnyOrder("SOURCE->PROCESSOR", "PROCESSOR->SINK");
    }

    @Test
    @DisplayName("레지스트리가 contractId 로 찾고, 없으면 예외를 던진다")
    void registryLookup() {
        TopologyContractRegistry registry = new TopologyContractRegistry(
                java.util.List.of(contract));

        assertThat(registry.find("fifo-single-server")).contains(contract);
        assertThat(registry.find("없는계약")).isEmpty();
        assertThatThrownBy(() -> registry.require("없는계약"))
                .isInstanceOf(TopologyContractNotFoundException.class);
    }
}
```

- [ ] **Step 3: 테스트가 실패하는 것을 확인한다**

Run: `./gradlew :modules:template:test`
Expected: 컴파일 실패 — `TopologyContract` 심볼을 찾을 수 없음

- [ ] **Step 4: 계약 타입을 구현한다**

`RoleSpec.java`:

```java
package org.hanbat.ses.template.topology;

/**
 * 토폴로지 계약의 역할 하나.
 *
 * @param modelRef   내부 모델일 때 model_base 의 modelId. 외부 역할이면 null.
 * @param capability 외부 모델일 때 MCP search_models 에 넘길 능력 이름. 내부면 null.
 * @param external   MCP 로 조회할 역할인가.
 */
public record RoleSpec(String role, String modelRef, String capability, boolean external) {
}
```

`TopologyContract.java`:

```java
package org.hanbat.ses.template.topology;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 허용 토폴로지 계약.
 *
 * <p>초안 프롬프트 생성(DraftPromptBuilder), 초안 검증(DraftValidator), SES 컴파일
 * (SesCompiler), 실행 명세 검증(ManifestAssembler)이 모두 이 하나를 읽는다.
 * 같은 규칙을 네 곳에 두면 그중 하나는 반드시 어긋난다.
 *
 * @param requiredConnections `[["SOURCE","PROCESSOR"], ["PROCESSOR","SINK"]]` 형태.
 *                            역할 이름으로 쓰며 노드 id 는 초안이 정한다.
 * @param reservedNodeIds     컴파일러가 쓰는 id. 초안이 이걸 제안하면 트리가 충돌한다.
 * @param unsupported         프롬프트에 실어 보내는 미지원 목록. 요청이 이 중 하나를
 *                            명시하면 LLM 이 supported=false 로 답해야 한다.
 */
public record TopologyContract(
        String contractId,
        int nodeCount,
        List<RoleSpec> roles,
        List<List<String>> requiredConnections,
        boolean allowFeedback,
        List<String> reservedNodeIds,
        String rootEntityName,
        String aspectName,
        String timeUnit,
        double horizon,
        List<String> unsupported
) {

    public TopologyContract {
        roles = roles == null ? List.of() : List.copyOf(roles);
        requiredConnections = requiredConnections == null ? List.of()
                : requiredConnections.stream().map(List::copyOf).toList();
        reservedNodeIds = reservedNodeIds == null ? List.of() : List.copyOf(reservedNodeIds);
        unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
    }

    public RoleSpec role(String name) {
        return roles.stream().filter(r -> r.role().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "계약 " + contractId + " 에 없는 역할입니다: " + name));
    }

    public Set<String> roleNames() {
        return roles.stream().map(RoleSpec::role)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 외부 모델을 담당하는 역할.
     *
     * <p>계약당 하나로 못 박는다. 둘 이상이면 MCP 조회를 몇 번 해야 하는지, 조회 실패
     * 시 어느 역할이 막힌 것인지가 코드마다 갈린다. 지금 계약은 하나뿐이므로 이 제약이
     * 실제로 좁히는 것은 없고, 두 번째 계약을 만들 때 정면으로 마주하게 된다.
     */
    public RoleSpec externalRole() {
        List<RoleSpec> external = roles.stream().filter(RoleSpec::external).toList();
        if (external.size() != 1) {
            throw new IllegalStateException("계약 " + contractId
                    + " 의 외부 역할이 " + external.size() + "개입니다. 정확히 1개여야 합니다.");
        }
        return external.get(0);
    }

    /** `SOURCE->PROCESSOR` 형태로 비교 가능하게 만든다. */
    public Set<String> connectionPairs() {
        return requiredConnections.stream()
                .map(pair -> pair.get(0) + "->" + pair.get(1))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
```

`TopologyContractNotFoundException.java`:

```java
package org.hanbat.ses.template.topology;

public class TopologyContractNotFoundException extends RuntimeException {

    public TopologyContractNotFoundException(String contractId) {
        super("등록되지 않은 토폴로지 계약입니다: " + contractId);
    }
}
```

`TopologyContractRegistry.java`:

```java
package org.hanbat.ses.template.topology;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 토폴로지 계약 조회. 계약은 시드 리소스에서 읽어 부팅 시 한 번 채운다. */
public class TopologyContractRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, TopologyContract> byId = new LinkedHashMap<>();

    public TopologyContractRegistry(List<TopologyContract> contracts) {
        contracts.forEach(c -> byId.put(c.contractId(), c));
    }

    public static TopologyContract load(InputStream in) throws IOException {
        if (in == null) {
            throw new IOException("토폴로지 계약 리소스를 찾을 수 없습니다.");
        }
        return MAPPER.readValue(in, TopologyContract.class);
    }

    public Optional<TopologyContract> find(String contractId) {
        return Optional.ofNullable(byId.get(contractId));
    }

    public TopologyContract require(String contractId) {
        return find(contractId)
                .orElseThrow(() -> new TopologyContractNotFoundException(contractId));
    }

    public List<TopologyContract> findAll() {
        return List.copyOf(byId.values());
    }
}
```

- [ ] **Step 5: 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :modules:template:test`
Expected: 6개 테스트 PASS

`external` 필드가 Jackson 으로 안 채워지면 record 컴포넌트 이름과 JSON 키가 어긋난 것이다.
루트 `build.gradle.kts`에 `-parameters` 컴파일러 인자가 이미 있으므로 record 이름 기반
바인딩이 동작한다. 실패하면 `@JsonProperty`를 붙이지 말고 JSON 키 이름을 먼저 확인한다.

- [ ] **Step 6: 커밋**

```bash
git add modules/template app/src/main/resources/seed/queue.topology.json
git commit -m "토폴로지 계약을 데이터로 외부화

허용 토폴로지(역할, 필수 배선, 예약 id, 미지원 목록, 루트 이름,
시간 단위, horizon)를 queue.topology.json 으로 뺀다.

지금까지 같은 규칙이 experiment.py 의 프롬프트 문자열, validate_draft,
validate_manifest 세 곳에 흩어져 있었다. 일반화가 아니라 중복 제거이며,
계약은 하나뿐이고 다중 계약 선택은 만들지 않는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: 모델 계약 통일과 MCP 계약 검증

**Files:**
- Modify: `settings.gradle.kts` (`"modules:design"` 추가)
- Create: `modules/design/build.gradle.kts`
- Create: `modules/design/src/main/java/org/hanbat/ses/design/contract/PortContract.java`
- Create: `modules/design/src/main/java/org/hanbat/ses/design/contract/ParamContract.java`
- Create: `modules/design/src/main/java/org/hanbat/ses/design/contract/ModelContract.java`
- Create: `modules/design/src/main/java/org/hanbat/ses/design/contract/McpModelContractValidator.java`
- Create: `app/src/main/resources/seed/queue.models.json`
- Modify: `modules/template/src/main/java/org/hanbat/ses/template/registry/ModelBaseEntry.java` (javadoc만)
- Test: `modules/design/src/test/java/org/hanbat/ses/design/contract/ModelContractTest.java`
- Test: `modules/design/src/test/java/org/hanbat/ses/design/contract/McpModelContractValidatorTest.java`

**Interfaces:**
- Consumes: Task 2의 `TopologyContract`(검증 시 `timeUnit` 대조), `VarType`·`Range`(core-ses),
  `ModelBaseEntry`(template)
- Produces: `PortContract(String name, String dataType, String unit)`,
  `ParamContract(String name, VarType type, String unit, boolean required, Range range, String question, Object defaultValue)` + `opensSlot()`,
  `ModelContract(String modelId, String origin, String timeUnit, List<PortContract> in, List<PortContract> out, List<ParamContract> params)`
  + `ModelContract.fromModelBase(ModelBaseEntry, String timeUnit)`, `ModelContract.fromMcp(Map<String,Object>)`,
  `McpModelContractValidator.validate(Map<String,Object> catalog, String expectedCapability, TopologyContract) -> List<ValidationIssue>`

**왜 `ModelContract`가 필요한가.** 스펙 §7.1의 핵심이다. 로컬 모델은 `ModelBaseEntry.params`
(`Map<String, Object>`), 외부 모델은 MCP 카탈로그(`parameters` 배열)로 형태가 다르다. Task 4의
`SesCompiler`가 이 둘을 각각 다루면 슬롯 생성 규칙이 두 벌이 되고, 범위·단위·질문의 단일
출처라는 목표가 무너진다. 컴파일러는 `ModelContract`만 본다.

**`ModelBaseEntry.params`에 두 키를 추가한다.** `range`와 `question`. 가산적이므로 기존 시드
11건(resort 7 + evcharge 4)은 영향받지 않는다 — 두 키가 없으면 `Range.none()`과 `null`이 된다.
`ModelBaseEntry.java` 자체는 코드 변경이 없고, `@param params` javadoc 에 두 키를 적는다.

- [ ] **Step 1: `modules:design` 을 빌드에 등록한다**

`settings.gradle.kts`의 `include(...)` 목록에서 `"modules:mcp",` 다음 줄에 추가:

```kotlin
    "modules:design",
```

`modules/design/build.gradle.kts` 생성:

```kotlin
plugins { `java-library` }

dependencies {
    api(project(":modules:core-ses"))
    api(project(":modules:template"))
    implementation(project(":modules:llm"))
    implementation(project(":modules:mcp"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

`modules:template` 을 `api` 로 두는 이유는 `SubtaskTemplate`·`SesDefinition`·`TopologyContract`가
`CompiledDesign`(Task 4)의 공개 타입이라서다. `llm`·`mcp`는 내부 구현이므로 `implementation`.

Task 2의 `queue.topology.json` 을 `modules/design/src/test/resources/` 에도 복사한다 —
Task 3~7의 테스트가 이 리소스를 읽는다.

- [ ] **Step 2: 로컬 모델 시드를 만든다**

`app/src/main/resources/seed/queue.models.json`:

```json
[
  {
    "modelId": "queue-local-source",
    "kind": "ATOMIC",
    "displayName": "작업 생성기",
    "ports": { "in": [], "out": ["out"] },
    "stateVars": { "emitted": "int" },
    "params": {
      "arrivalInterval": {
        "type": "DOUBLE", "unit": "분", "required": true,
        "range": { "min": 0.01, "max": 10 },
        "question": "작업이 몇 분 간격으로 도착하나요?"
      },
      "totalJobs": {
        "type": "INT", "unit": "건", "required": true,
        "range": { "min": 1, "max": 100 },
        "question": "총 몇 건의 작업을 생성할까요?"
      }
    }
  },
  {
    "modelId": "queue-local-sink",
    "kind": "ATOMIC",
    "displayName": "작업 집계기",
    "ports": { "in": ["in"], "out": [] },
    "stateVars": { "arrived": "int", "completed": "int", "waitSum": "double" },
    "params": {}
  }
]
```

`AssetController.registerModel`은 대응하는 `AtomicModelFactory`가 없으면 **경고와 함께**
등록한다. 이 두 모델은 내부 DEVS 엔진이 아니라 Task 8의 MCP executor 가 해석하므로 팩토리가
없는 것이 정상이고, 경고가 나오는 것이 기대 동작이다. 등록을 막지는 않는다.

`app/.../seed` 로더는 Task 10에서 함께 배선한다. 이 태스크에서는 파일만 만든다.

- [ ] **Step 3: `ModelContract` 테스트를 쓴다**

`ModelContractTest.java`:

```java
package org.hanbat.ses.design.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.model.VarType;
import org.hanbat.ses.template.registry.ModelBaseEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelContractTest {

    @Test
    @DisplayName("ModelBaseEntry 의 params 맵을 ParamContract 로 옮긴다")
    void fromModelBase() {
        ModelBaseEntry entry = new ModelBaseEntry(
                "queue-local-source", "ATOMIC", "작업 생성기",
                Map.of("in", List.of(), "out", List.of("out")),
                Map.of("emitted", "int"),
                Map.of("arrivalInterval", Map.of(
                        "type", "DOUBLE", "unit", "분", "required", true,
                        "range", Map.of("min", 0.01, "max", 10),
                        "question", "작업이 몇 분 간격으로 도착하나요?")));

        ModelContract contract = ModelContract.fromModelBase(entry, "분");

        assertThat(contract.modelId()).isEqualTo("queue-local-source");
        assertThat(contract.origin()).isEqualTo("model-base");
        assertThat(contract.timeUnit()).isEqualTo("분");
        assertThat(contract.in()).isEmpty();
        assertThat(contract.out()).extracting(PortContract::name).containsExactly("out");

        ParamContract p = contract.params().get(0);
        assertThat(p.name()).isEqualTo("arrivalInterval");
        assertThat(p.type()).isEqualTo(VarType.DOUBLE);
        assertThat(p.unit()).isEqualTo("분");
        assertThat(p.required()).isTrue();
        assertThat(p.range().min()).isEqualTo(0.01);
        assertThat(p.range().max()).isEqualTo(10.0);
        assertThat(p.question()).isEqualTo("작업이 몇 분 간격으로 도착하나요?");
        assertThat(p.defaultValue()).isNull();
        assertThat(p.opensSlot()).isTrue();
    }

    @Test
    @DisplayName("기본값이 있는 파라미터는 슬롯을 열지 않는다")
    void defaultedParamDoesNotOpenSlot() {
        ModelBaseEntry entry = new ModelBaseEntry(
                "transducer", "ATOMIC", "집계기",
                Map.of("in", List.of("arrive"), "out", List.of()),
                Map.of(),
                Map.of("관측시간", Map.of("type", "DOUBLE", "unit", "분", "default", 1440.0)));

        ParamContract p = ModelContract.fromModelBase(entry, "분").params().get(0);

        assertThat(p.required()).isFalse();
        assertThat(p.defaultValue()).isEqualTo(1440.0);
        assertThat(p.opensSlot()).isFalse();
        assertThat(p.range().isEmpty()).isTrue();
        assertThat(p.question()).isNull();
    }

    @Test
    @DisplayName("range 와 question 이 없는 기존 시드도 그대로 읽힌다")
    void legacyEntryWithoutRangeOrQuestion() {
        ModelBaseEntry entry = new ModelBaseEntry(
                "cable-car", "ATOMIC", "케이블카",
                Map.of("in", List.of("in"), "out", List.of("out")),
                Map.of(),
                Map.of("정원", Map.of("type", "INT", "unit", "명", "required", true)));

        ParamContract p = ModelContract.fromModelBase(entry, "분").params().get(0);

        assertThat(p.range().isEmpty()).isTrue();
        assertThat(p.question()).isNull();
        assertThat(p.opensSlot()).isTrue();
    }

    @Test
    @DisplayName("MCP 카탈로그를 ParamContract 로 옮기고 질문 문구를 가져온다")
    void fromMcp() {
        Map<String, Object> catalog = Map.of(
                "modelId", "external-simpy-fifo",
                "version", "1.0.0",
                "capability", "fifo-single-server",
                "timeUnit", "분",
                "ports", Map.of(
                        "in", Map.of("name", "in", "dataType", "job", "unit", "건"),
                        "out", Map.of("name", "out", "dataType", "job", "unit", "건")),
                "parameters", List.of(Map.of(
                        "name", "serviceTime", "type", "DOUBLE", "unit", "분",
                        "required", true,
                        "range", Map.of("min", 0.01, "max", 10, "allowed", List.of()),
                        "question", "작업 한 건의 처리시간은 몇 분인가요?")),
                "limits", Map.of("maxJobs", 100, "servers", 1,
                        "discipline", "FIFO", "termination", "drain-all-jobs"),
                "runtime", Map.of("kind", "docker", "image", "ses-queue-processor:1.0.0"));

        ModelContract contract = ModelContract.fromMcp(catalog);

        assertThat(contract.modelId()).isEqualTo("external-simpy-fifo");
        assertThat(contract.origin()).isEqualTo("mcp");
        assertThat(contract.timeUnit()).isEqualTo("분");
        assertThat(contract.in()).containsExactly(new PortContract("in", "job", "건"));
        assertThat(contract.out()).containsExactly(new PortContract("out", "job", "건"));

        ParamContract p = contract.params().get(0);
        assertThat(p.name()).isEqualTo("serviceTime");
        assertThat(p.type()).isEqualTo(VarType.DOUBLE);
        assertThat(p.question()).isEqualTo("작업 한 건의 처리시간은 몇 분인가요?");
        assertThat(p.range().containsNumber(5.0)).isTrue();
        assertThat(p.range().containsNumber(20.0)).isFalse();
        assertThat(p.opensSlot()).isTrue();
    }

    @Test
    @DisplayName("params 순서를 선언 순서로 유지한다")
    void keepsDeclarationOrder() {
        ModelBaseEntry entry = new ModelBaseEntry(
                "queue-local-source", "ATOMIC", "작업 생성기",
                Map.of("in", List.of(), "out", List.of("out")), Map.of(),
                new java.util.LinkedHashMap<>(Map.of()) {{
                    put("arrivalInterval", Map.of("type", "DOUBLE", "required", true));
                    put("totalJobs", Map.of("type", "INT", "required", true));
                }});

        assertThat(ModelContract.fromModelBase(entry, "분").params())
                .extracting(ParamContract::name)
                .containsExactly("arrivalInterval", "totalJobs");
    }
}
```

- [ ] **Step 4: 테스트가 실패하는 것을 확인한다**

Run: `./gradlew :modules:design:test --tests '*ModelContractTest'`
Expected: 컴파일 실패 — `ModelContract` 심볼을 찾을 수 없음

- [ ] **Step 5: 계약 타입을 구현한다**

`PortContract.java`:

```java
package org.hanbat.ses.design.contract;

/**
 * 포트 하나의 계약.
 *
 * <p>unit 을 그대로 보존하는 것이 중요하다. Task 4 가 이것을 PortDef 로 옮기고, 그러면
 * 기존 SesStructureChecker 의 PORT_UNIT_MISMATCH 검사가 외부 모델 배선에도 걸린다.
 */
public record PortContract(String name, String dataType, String unit) {

    public PortContract {
        dataType = dataType == null || dataType.isBlank() ? "any" : dataType;
    }
}
```

`ParamContract.java`:

```java
package org.hanbat.ses.design.contract;

import org.hanbat.ses.core.model.Range;
import org.hanbat.ses.core.model.VarType;

/**
 * 파라미터 하나의 계약 — 로컬 모델과 외부 모델이 공유하는 표현.
 *
 * @param question 이 파라미터를 사용자에게 물을 때의 문구. 모델이 자기 파라미터를
 *                 어떻게 물어야 하는지 알고 있다는 것이 이 시스템의 핵심 전제다.
 *                 없으면 Task 4 가 이름과 단위로 기계 생성한다.
 */
public record ParamContract(String name, VarType type, String unit, boolean required,
                            Range range, String question, Object defaultValue) {

    public ParamContract {
        type = type == null ? VarType.DOUBLE : type;
        range = range == null ? Range.none() : range;
    }

    /**
     * 이 파라미터가 질문을 만드는가.
     *
     * <p>기본값이 있으면 VarDef.isOpen() 이 false 가 되어 SlotResolver 가 질문 대상에서
     * 제외한다. 그러므로 슬롯을 만들 필요도 없다 — 만들면 TemplateConsistencyChecker 가
     * 통과시키긴 하지만 영원히 열리지 않는 슬롯이 템플릿에 남는다.
     */
    public boolean opensSlot() {
        return required && defaultValue == null;
    }
}
```

`ModelContract.java`:

```java
package org.hanbat.ses.design.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.model.Range;
import org.hanbat.ses.core.model.VarType;
import org.hanbat.ses.template.registry.ModelBaseEntry;

/**
 * 모델 하나의 계약 — 로컬(model_base)과 외부(MCP 카탈로그)를 같은 모양으로 만든다.
 *
 * <p>SesCompiler 가 이 타입만 보게 하는 것이 목적이다. 두 출처를 각각 다루면 슬롯 생성
 * 규칙이 두 벌이 되고, "범위·단위·질문의 단일 출처"가 무너진다.
 *
 * @param origin "model-base" 또는 "mcp"
 */
public record ModelContract(String modelId, String origin, String timeUnit,
                            List<PortContract> in, List<PortContract> out,
                            List<ParamContract> params) {

    public ModelContract {
        in = in == null ? List.of() : List.copyOf(in);
        out = out == null ? List.of() : List.copyOf(out);
        params = params == null ? List.of() : List.copyOf(params);
    }

    // ------------------------------------------------------------ 로컬

    @SuppressWarnings("unchecked")
    public static ModelContract fromModelBase(ModelBaseEntry entry, String timeUnit) {
        List<PortContract> in = portsOf(entry, "in");
        List<PortContract> out = portsOf(entry, "out");

        List<ParamContract> params = new ArrayList<>();
        for (Map.Entry<String, Object> e : entry.params().entrySet()) {
            Map<String, Object> spec = e.getValue() instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            params.add(new ParamContract(
                    e.getKey(),
                    varType(spec.get("type")),
                    str(spec.get("unit")),
                    Boolean.TRUE.equals(spec.get("required")),
                    range(spec.get("range")),
                    str(spec.get("question")),
                    spec.get("default")));
        }
        return new ModelContract(entry.modelId(), "model-base", timeUnit, in, out, params);
    }

    @SuppressWarnings("unchecked")
    private static List<PortContract> portsOf(ModelBaseEntry entry, String direction) {
        Object raw = entry.ports().get(direction);
        if (!(raw instanceof List<?> names)) {
            return List.of();
        }
        // model_base 의 포트는 이름 문자열 목록이다. 데이터타입·단위를 말하지 않으므로
        // 추측하지 않고 비운다 — SesStructureChecker 는 단위가 없는 포트를 대조에서 뺀다.
        return ((List<Object>) names).stream()
                .map(n -> new PortContract(String.valueOf(n), "any", null))
                .toList();
    }

    // ------------------------------------------------------------ 외부 (MCP)

    @SuppressWarnings("unchecked")
    public static ModelContract fromMcp(Map<String, Object> catalog) {
        Map<String, Object> ports = catalog.get("ports") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        List<ParamContract> params = new ArrayList<>();
        Object rawParams = catalog.get("parameters");
        if (rawParams instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> spec = (Map<String, Object>) m;
                params.add(new ParamContract(
                        str(spec.get("name")),
                        varType(spec.get("type")),
                        str(spec.get("unit")),
                        Boolean.TRUE.equals(spec.get("required")),
                        range(spec.get("range")),
                        str(spec.get("question")),
                        spec.get("default")));
            }
        }
        return new ModelContract(
                str(catalog.get("modelId")), "mcp", str(catalog.get("timeUnit")),
                mcpPort(ports.get("in")), mcpPort(ports.get("out")), params);
    }

    @SuppressWarnings("unchecked")
    private static List<PortContract> mcpPort(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return List.of();
        }
        Map<String, Object> port = (Map<String, Object>) m;
        return List.of(new PortContract(str(port.get("name")), str(port.get("dataType")),
                str(port.get("unit"))));
    }

    // ------------------------------------------------------------ 공용

    public List<ParamContract> openParams() {
        return params.stream().filter(ParamContract::opensSlot).toList();
    }

    public List<ParamContract> defaultedParams() {
        return params.stream().filter(p -> !p.opensSlot()).toList();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static VarType varType(Object value) {
        if (value == null) {
            return VarType.DOUBLE;
        }
        try {
            return VarType.valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return VarType.DOUBLE;
        }
    }

    @SuppressWarnings("unchecked")
    private static Range range(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return Range.none();
        }
        Map<String, Object> spec = (Map<String, Object>) m;
        Double min = number(spec.get("min"));
        Double max = number(spec.get("max"));
        List<String> allowed = spec.get("allowed") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        if (min == null && max == null && allowed.isEmpty()) {
            return Range.none();
        }
        return new Range(min, max, allowed);
    }

    private static Double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 파라미터 이름으로 조회. Task 4·8 이 값 바인딩에 쓴다. */
    public ParamContract param(String name) {
        return params.stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        modelId + " 에 없는 파라미터입니다: " + name));
    }

    /** 이름 -> 계약. LinkedHashMap 으로 선언 순서를 지킨다. */
    public Map<String, ParamContract> paramsByName() {
        Map<String, ParamContract> map = new LinkedHashMap<>();
        params.forEach(p -> map.put(p.name(), p));
        return map;
    }
}
```

- [ ] **Step 6: `ModelContract` 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :modules:design:test --tests '*ModelContractTest'`
Expected: 5개 테스트 PASS

- [ ] **Step 7: `McpModelContractValidator` 테스트를 쓴다**

`McpModelContractValidatorTest.java`:

```java
package org.hanbat.ses.design.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.template.topology.TopologyContract;
import org.hanbat.ses.template.topology.TopologyContractRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class McpModelContractValidatorTest {

    private final McpModelContractValidator validator = new McpModelContractValidator();
    private TopologyContract topology;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/queue.topology.json")) {
            topology = TopologyContractRegistry.load(in);
        }
    }

    private Map<String, Object> valid() {
        Map<String, Object> catalog = new HashMap<>();
        catalog.put("modelId", "external-simpy-fifo");
        catalog.put("capability", "fifo-single-server");
        catalog.put("timeUnit", "분");
        catalog.put("ports", Map.of(
                "in", Map.of("name", "in", "dataType", "job", "unit", "건"),
                "out", Map.of("name", "out", "dataType", "job", "unit", "건")));
        catalog.put("parameters", List.of(Map.of(
                "name", "serviceTime", "type", "DOUBLE", "unit", "분", "required", true,
                "range", Map.of("min", 0.01, "max", 10))));
        catalog.put("limits", Map.of("maxJobs", 100, "servers", 1,
                "discipline", "FIFO", "termination", "drain-all-jobs"));
        catalog.put("runtime", Map.of("kind", "docker", "image", "ses-queue-processor:1.0.0"));
        return catalog;
    }

    private List<String> codes(Map<String, Object> catalog) {
        return validator.validate(catalog, "fifo-single-server", topology).stream()
                .filter(ValidationIssue::isError)
                .map(ValidationIssue::code)
                .toList();
    }

    @Test
    @DisplayName("정상 카탈로그는 오류가 없다")
    void validCatalogPasses() {
        assertThat(codes(valid())).isEmpty();
    }

    @Test
    @DisplayName("필수 키가 빠지면 CONTRACT_INCOMPLETE")
    void missingKey() {
        Map<String, Object> c = valid();
        c.remove("runtime");
        assertThat(codes(c)).contains("CONTRACT_INCOMPLETE");
    }

    @Test
    @DisplayName("capability 가 다르면 CAPABILITY_MISMATCH")
    void capabilityMismatch() {
        Map<String, Object> c = valid();
        c.put("capability", "multi-server");
        assertThat(codes(c)).contains("CAPABILITY_MISMATCH");
    }

    @Test
    @DisplayName("시간 단위가 계약과 호환되지 않으면 TIME_UNIT_UNKNOWN")
    void timeUnitUnknown() {
        Map<String, Object> c = valid();
        c.put("timeUnit", "패럴롱");
        assertThat(codes(c)).contains("TIME_UNIT_UNKNOWN");
    }

    @Test
    @DisplayName("min 을 분 별칭으로 쓴 시간 단위는 통과한다")
    void timeUnitAliasPasses() {
        Map<String, Object> c = valid();
        c.put("timeUnit", "min");
        assertThat(codes(c)).doesNotContain("TIME_UNIT_UNKNOWN");
    }

    @Test
    @DisplayName("포트에 dataType 이나 unit 이 없으면 PORT_CONTRACT_INCOMPLETE")
    void portContractIncomplete() {
        Map<String, Object> c = valid();
        c.put("ports", Map.of("in", Map.of("name", "in"),
                "out", Map.of("name", "out", "dataType", "job", "unit", "건")));
        assertThat(codes(c)).contains("PORT_CONTRACT_INCOMPLETE");
    }

    @Test
    @DisplayName("range 의 min 이 max 보다 크면 PARAM_RANGE_INVALID")
    void paramRangeInvalid() {
        Map<String, Object> c = valid();
        c.put("parameters", List.of(Map.of("name", "serviceTime", "type", "DOUBLE",
                "unit", "분", "required", true,
                "range", Map.of("min", 10, "max", 0.01))));
        assertThat(codes(c)).contains("PARAM_RANGE_INVALID");
    }

    @Test
    @DisplayName("알 수 없는 파라미터 타입은 PARAM_TYPE_UNKNOWN")
    void paramTypeUnknown() {
        Map<String, Object> c = valid();
        c.put("parameters", List.of(Map.of("name", "serviceTime", "type", "DECIMAL",
                "unit", "분", "required", true)));
        assertThat(codes(c)).contains("PARAM_TYPE_UNKNOWN");
    }

    @Test
    @DisplayName("허용하지 않는 종료 조건은 TERMINATION_UNSUPPORTED")
    void terminationUnsupported() {
        Map<String, Object> c = valid();
        c.put("limits", Map.of("maxJobs", 100, "servers", 1,
                "discipline", "FIFO", "termination", "run-forever"));
        assertThat(codes(c)).contains("TERMINATION_UNSUPPORTED");
    }
}
```

- [ ] **Step 8: 테스트가 실패하는 것을 확인한다**

Run: `./gradlew :modules:design:test --tests '*McpModelContractValidatorTest'`
Expected: 컴파일 실패 — `McpModelContractValidator` 심볼을 찾을 수 없음

- [ ] **Step 9: `McpModelContractValidator` 를 구현한다**

```java
package org.hanbat.ses.design.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hanbat.ses.core.model.VarType;
import org.hanbat.ses.core.validate.UnitTable;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.template.topology.TopologyContract;

/**
 * MCP 카탈로그의 <b>구조</b> 검증.
 *
 * <p>experiment.py 의 validate_model 은 필드별 상수 동일성을 비교했다 —
 * {@code ports == {"in": {"name":"in","dataType":"job","unit":"건"}, ...}} 형태로.
 * 그 방식은 카탈로그 1건에 강결합되어 모델이 하나 늘면 검증기를 고쳐야 한다.
 * 상수 동일성 확인은 골든 통합 테스트로 옮겼고(스펙 §7.3), 여기 남은 것은
 * "이 계약으로 슬롯과 포트를 만들 수 있는가"뿐이다.
 */
public class McpModelContractValidator {

    private static final List<String> REQUIRED_KEYS = List.of(
            "modelId", "capability", "timeUnit", "ports", "parameters", "limits", "runtime");

    private static final Set<String> ALLOWED_TERMINATION = Set.of(
            "drain-all-jobs", "horizon");

    public List<ValidationIssue> validate(Map<String, Object> catalog,
                                          String expectedCapability,
                                          TopologyContract topology) {
        List<ValidationIssue> issues = new ArrayList<>();
        String id = String.valueOf(catalog.get("modelId"));

        for (String key : REQUIRED_KEYS) {
            if (!catalog.containsKey(key) || catalog.get(key) == null) {
                issues.add(ValidationIssue.error(id, "CONTRACT_INCOMPLETE",
                        "모델 계약에 " + key + " 가 없습니다."));
            }
        }
        if (!issues.isEmpty()) {
            // 키가 없으면 아래 검사는 전부 같은 원인으로 실패한다.
            return List.copyOf(issues);
        }

        if (!expectedCapability.equals(catalog.get("capability"))) {
            issues.add(ValidationIssue.error(id, "CAPABILITY_MISMATCH",
                    "요청한 능력은 " + expectedCapability
                            + " 인데 모델이 말하는 능력은 " + catalog.get("capability") + " 입니다."));
        }

        String timeUnit = String.valueOf(catalog.get("timeUnit"));
        if (!UnitTable.compatible(timeUnit, topology.timeUnit(), Map.of("min", "분", "hr", "시간"))) {
            issues.add(ValidationIssue.error(id, "TIME_UNIT_UNKNOWN",
                    "모델의 시간 단위 " + timeUnit + " 를 계약의 "
                            + topology.timeUnit() + " 와 맞출 수 없습니다."));
        }

        checkPorts(catalog.get("ports"), id, issues);
        checkParameters(catalog.get("parameters"), id, issues);

        Object termination = catalog.get("limits") instanceof Map<?, ?> m
                ? m.get("termination") : null;
        if (!ALLOWED_TERMINATION.contains(String.valueOf(termination))) {
            issues.add(ValidationIssue.error(id, "TERMINATION_UNSUPPORTED",
                    "지원하지 않는 종료 조건입니다: " + termination
                            + " (허용: " + ALLOWED_TERMINATION + ")"));
        }
        return List.copyOf(issues);
    }

    private void checkPorts(Object raw, String id, List<ValidationIssue> issues) {
        if (!(raw instanceof Map<?, ?> ports)) {
            issues.add(ValidationIssue.error(id, "PORT_CONTRACT_INCOMPLETE",
                    "ports 가 객체가 아닙니다."));
            return;
        }
        for (Map.Entry<?, ?> entry : ports.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> port)) {
                issues.add(ValidationIssue.error(id, "PORT_CONTRACT_INCOMPLETE",
                        entry.getKey() + " 포트가 객체가 아닙니다."));
                continue;
            }
            for (String key : List.of("name", "dataType", "unit")) {
                Object value = port.get(key);
                if (value == null || String.valueOf(value).isBlank()) {
                    issues.add(ValidationIssue.error(id, "PORT_CONTRACT_INCOMPLETE",
                            entry.getKey() + " 포트에 " + key + " 가 없습니다. "
                                    + "단위를 밝히지 않은 포트는 배선 단위 대조에서 빠집니다."));
                }
            }
        }
    }

    private void checkParameters(Object raw, String id, List<ValidationIssue> issues) {
        if (!(raw instanceof List<?> params)) {
            issues.add(ValidationIssue.error(id, "CONTRACT_INCOMPLETE",
                    "parameters 가 배열이 아닙니다."));
            return;
        }
        for (Object item : params) {
            if (!(item instanceof Map<?, ?> spec)) {
                issues.add(ValidationIssue.error(id, "CONTRACT_INCOMPLETE",
                        "parameters 항목이 객체가 아닙니다."));
                continue;
            }
            String name = String.valueOf(spec.get("name"));

            Object type = spec.get("type");
            if (type != null && !isKnownVarType(String.valueOf(type))) {
                issues.add(ValidationIssue.error(id, "PARAM_TYPE_UNKNOWN",
                        name + " 의 타입 " + type + " 을 VarType 으로 해석할 수 없습니다."));
            }

            if (spec.get("range") instanceof Map<?, ?> range) {
                Double min = asDouble(range.get("min"));
                Double max = asDouble(range.get("max"));
                if (min != null && max != null && min > max) {
                    issues.add(ValidationIssue.error(id, "PARAM_RANGE_INVALID",
                            name + " 의 범위가 뒤집혀 있습니다: min " + min + " > max " + max));
                }
            }
        }
    }

    private static boolean isKnownVarType(String raw) {
        for (VarType t : VarType.values()) {
            if (t.name().equalsIgnoreCase(raw.trim())) {
                return true;
            }
        }
        return false;
    }

    private static Double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
```

`UnitTable.compatible(String, String, Map<String,String>)` 은 이미
`modules:core-ses`의 `org.hanbat.ses.core.validate.UnitTable` 에 있고
`SesStructureChecker` 가 쓰는 것과 같은 메서드다. 없으면 시그니처를 먼저 확인한다.

- [ ] **Step 10: 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :modules:design:test`
Expected: `ModelContractTest` 5개 + `McpModelContractValidatorTest` 9개 PASS

- [ ] **Step 11: `ModelBaseEntry` javadoc 에 두 키를 적는다**

`modules/template/.../registry/ModelBaseEntry.java` 의 `@param params` 줄을 교체:

```java
 * @param params    파라미터 이름 -> {type, unit, required, default, range, question}.
 *                  range 는 {min, max, allowed}, question 은 사용자에게 물을 문구다.
 *                  둘은 선택 항목이며 없으면 Range.none() 과 null 로 해석된다 —
 *                  기존 도메인 시드(resort 7건, evcharge 4건)는 갖고 있지 않다.
```

- [ ] **Step 12: 커밋**

```bash
git add settings.gradle.kts modules/design modules/template app/src/main/resources/seed/queue.models.json
git commit -m "로컬/외부 모델 계약을 ModelContract 로 통일하고 MCP 계약 검증 추가

model_base 의 params 맵과 MCP 카탈로그의 parameters 배열을 같은
ParamContract 로 옮긴다. SesCompiler 가 두 출처를 따로 다루지 않게 해
범위/단위/질문의 단일 출처를 만든다.

queue-local-source, queue-local-sink 를 정식 model_base 시드로 등록해
experiment.py 의 PARAMS 상수와 validate_parameters 의 범위 이중 정의를
없앤다. 두 모델은 MCP executor 가 해석하므로 AtomicModelFactory 가 없고,
등록 시 경고가 나오는 것이 기대 동작이다.

McpModelContractValidator 는 구조만 본다. validate_model 의 필드별 상수
비교는 골든 통합 테스트로 옮긴다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: `SesCompiler` — 계약에서 SES와 템플릿을 만든다

**Files:**
- Create: `modules/design/src/main/java/org/hanbat/ses/design/draft/DraftNode.java`
- Create: `modules/design/src/main/java/org/hanbat/ses/design/draft/DraftConnection.java`
- Create: `modules/design/src/main/java/org/hanbat/ses/design/draft/StructureDraft.java`
- Create: `modules/design/src/main/java/org/hanbat/ses/design/compile/CompiledDesign.java`
- Create: `modules/design/src/main/java/org/hanbat/ses/design/compile/SesCompiler.java`
- Create: `modules/design/src/main/java/org/hanbat/ses/design/compile/CompileException.java`
- Test: `modules/design/src/test/java/org/hanbat/ses/design/compile/SesCompilerTest.java`

**Interfaces:**
- Consumes: Task 2 `TopologyContract`·`RoleSpec`, Task 3 `ModelContract`·`ParamContract`·`PortContract`
- Produces: `DraftNode(String id, String name, String role)`,
  `DraftConnection(String from, String to)`,
  `StructureDraft(String title, boolean supported, String reason, List<DraftNode> nodes, List<DraftConnection> connections, List<String> assumptions)` + `node(String role)`,
  `CompiledDesign(SesDefinition ses, SubtaskTemplate template, List<ModelContract> contracts)`,
  `SesCompiler.compile(StructureDraft, TopologyContract, Map<String,ModelContract> byRole, String designId) -> CompiledDesign`,
  `SesCompiler.newDesignId() -> String`,
  `CompileException`

**이 태스크가 스펙 §7.5의 규칙 하나를 구현한다.**

> 각 역할 노드에 대해 그 모델 계약의 params 를 순회한다. `required && default == null` 이면
> `VarDef` 를 값 없이 만들고 `ValueSlot` 을 생성한다 — type·unit·range·question 은 계약에서
> 복사하고 `anchor = SesAnchor.variable(entityPath, nodeId, varName)`, `inferable = false`.
> `default` 가 있으면 `VarDef.defaultValue` 에 넣는다.

**이 설계의 이득이 여기서 나온다.** 산출물을 기존 `SesRegistry`/`TemplateRegistry`에 등록하면
`SesAxiomValidator`(12코드)·`SesStructureChecker`(4코드)·`TemplateConsistencyChecker`(12코드)가
LLM 산출물에 그대로 걸린다. 새 검증기를 만들지 않는다. 그래서 이 태스크의 테스트는
**컴파일 결과가 그 세 검증기를 통과하는지**를 직접 확인한다.

**슬롯 이름은 파라미터 이름을 그대로 쓴다.** 현재 계약에서 세 역할의 파라미터 이름
(`arrivalInterval`, `totalJobs`, `serviceTime`)이 서로 겹치지 않고, Task 11의 골든 테스트가
이 이름으로 답변을 제출한다. 겹치는 경우는 지금 계약에 없으므로 일반화하지 않고
`CompileException` 으로 즉시 실패시킨다 — 조용히 뒤 슬롯이 앞 슬롯을 덮는 것보다 낫다.

- [ ] **Step 1: 초안 데이터 타입을 만든다**

`DraftNode.java`:

```java
package org.hanbat.ses.design.draft;

/**
 * LLM 이 제안한 구성요소 하나.
 *
 * <p>LLM 이 정하는 것은 이 셋뿐이다 — 식별자, 사람이 읽는 이름, 계약상의 역할.
 * 모델 참조·포트·파라미터·범위는 계약이 정한다.
 */
public record DraftNode(String id, String name, String role) {
}
```

`DraftConnection.java`:

```java
package org.hanbat.ses.design.draft;

/** LLM 이 제안한 배선 하나. 노드 id 로 표기한다. */
public record DraftConnection(String from, String to) {
}
```

`StructureDraft.java`:

```java
package org.hanbat.ses.design.draft;

import java.util.List;

/**
 * LLM 구조 초안.
 *
 * <p>supported=false 는 실패가 아니라 정상 응답이다 — 요청이 계약 범위를 벗어났다는
 * 판단이고, reason 에 이유가 담긴다. 이 경우 폴백 초안을 만들지 않는다.
 */
public record StructureDraft(String title, boolean supported, String reason,
                             List<DraftNode> nodes, List<DraftConnection> connections,
                             List<String> assumptions) {

    public StructureDraft {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        connections = connections == null ? List.of() : List.copyOf(connections);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    }

    /** 역할로 노드를 찾는다. 역할이 유일한 것은 DraftValidator 가 보장한다. */
    public DraftNode node(String role) {
        return nodes.stream().filter(n -> role.equals(n.role())).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "초안에 " + role + " 역할 노드가 없습니다."));
    }
}
```

- [ ] **Step 2: 산출물 타입과 예외를 만든다**

`CompiledDesign.java`:

```java
package org.hanbat.ses.design.compile;

import java.util.List;

import org.hanbat.ses.design.contract.ModelContract;
import org.hanbat.ses.template.model.SubtaskTemplate;
import org.hanbat.ses.template.registry.SesDefinition;

/**
 * 컴파일 산출물.
 *
 * <p>contracts 를 함께 들고 다니는 이유는 Task 8 의 executor 가 파라미터를 외부 모델에
 * 넘길 때 어느 값이 어느 모델 소속인지 알아야 하고, Task 10 의 응답이 모델 출처를
 * 보여줘야 하기 때문이다. SES 는 modelRef 만 갖고 있어 origin 을 되찾을 수 없다.
 */
public record CompiledDesign(SesDefinition ses, SubtaskTemplate template,
                             List<RoleContract> contracts) {

    /**
     * 역할과 그 역할이 쓴 모델 계약. 순서는 초안 노드 순서다.
     *
     * <p>역할을 함께 담는 이유는 Task 10 의 응답이 "어느 역할이 어느 모델을 썼는가"를
     * 보여줘야 하기 때문이다. 계약 목록과 초안 노드 목록의 인덱스가 대응한다는 암묵적
     * 규칙에 기대면, 컴파일러가 순회 순서를 바꾸는 순간 응답이 조용히 틀린다.
     */
    public record RoleContract(String role, ModelContract contract) {
    }

    public CompiledDesign {
        contracts = contracts == null ? List.of() : List.copyOf(contracts);
    }

    public ModelContract contractFor(String role) {
        return contracts.stream().filter(c -> c.role().equals(role)).findFirst()
                .map(RoleContract::contract)
                .orElseThrow(() -> new IllegalStateException(
                        role + " 역할의 계약이 없습니다."));
    }
}
```

`CompileException.java`:

```java
package org.hanbat.ses.design.compile;

/** 컴파일러가 만들 수 없는 입력. 검증기를 통과한 초안에서 나오면 컴파일러 결함이다. */
public class CompileException extends RuntimeException {

    public CompileException(String message) {
        super(message);
    }
}
```

- [ ] **Step 3: 실패하는 테스트를 쓴다**

`SesCompilerTest.java`:

```java
package org.hanbat.ses.design.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.axiom.SesAxiomValidator;
import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.CouplingKind;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.PortDirection;
import org.hanbat.ses.core.model.Range;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.model.VarType;
import org.hanbat.ses.core.validate.SesStructureChecker;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.design.contract.ModelContract;
import org.hanbat.ses.design.contract.ParamContract;
import org.hanbat.ses.design.contract.PortContract;
import org.hanbat.ses.design.draft.DraftConnection;
import org.hanbat.ses.design.draft.DraftNode;
import org.hanbat.ses.design.draft.StructureDraft;
import org.hanbat.ses.template.topology.TopologyContract;
import org.hanbat.ses.template.topology.TopologyContractRegistry;
import org.hanbat.ses.template.model.SlotSpec;
import org.hanbat.ses.template.model.ValueSlot;
import org.hanbat.ses.template.validate.TemplateConsistencyChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SesCompilerTest {

    private final SesCompiler compiler = new SesCompiler();
    private TopologyContract topology;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/queue.topology.json")) {
            topology = TopologyContractRegistry.load(in);
        }
    }

    private static StructureDraft draft() {
        return new StructureDraft("단일 FIFO 대기행렬", true, null,
                List.of(new DraftNode("gen-1", "작업생성", "SOURCE"),
                        new DraftNode("proc-1", "처리기", "PROCESSOR"),
                        new DraftNode("agg-1", "집계", "SINK")),
                List.of(new DraftConnection("gen-1", "proc-1"),
                        new DraftConnection("proc-1", "agg-1")),
                List.of("단일 서버 / FIFO / 무한 대기실"));
    }

    private static Map<String, ModelContract> contracts() {
        Map<String, ModelContract> byRole = new LinkedHashMap<>();
        byRole.put("SOURCE", new ModelContract("queue-local-source", "model-base", "분",
                List.of(), List.of(new PortContract("out", "job", "건")),
                List.of(new ParamContract("arrivalInterval", VarType.DOUBLE, "분", true,
                                Range.between(0.01, 10), "작업이 몇 분 간격으로 도착하나요?", null),
                        new ParamContract("totalJobs", VarType.INT, "건", true,
                                Range.between(1, 100), "총 몇 건의 작업을 생성할까요?", null))));
        byRole.put("PROCESSOR", new ModelContract("external-simpy-fifo", "mcp", "분",
                List.of(new PortContract("in", "job", "건")),
                List.of(new PortContract("out", "job", "건")),
                List.of(new ParamContract("serviceTime", VarType.DOUBLE, "분", true,
                        Range.between(0.01, 10), "작업 한 건의 처리시간은 몇 분인가요?", null))));
        byRole.put("SINK", new ModelContract("queue-local-sink", "model-base", "분",
                List.of(new PortContract("in", "job", "건")), List.of(), List.of()));
        return byRole;
    }

    private CompiledDesign compile() {
        return compiler.compile(draft(), topology, contracts(), "queue-abc123def456");
    }

    @Test
    @DisplayName("루트와 aspect 는 계약이 정한 예약 id 와 이름을 쓴다")
    void rootAndAspectFromContract() {
        EntityNode root = (EntityNode) compile().ses().tree();

        assertThat(root.id()).isEqualTo("queue-root");
        assertThat(root.name()).isEqualTo("대기행렬");
        assertThat(root.axes()).hasSize(1);

        AspectNode aspect = (AspectNode) root.axes().get(0);
        assertThat(aspect.id()).isEqualTo("queue-components");
        assertThat(aspect.name()).isEqualTo("구성");
        assertThat(aspect.components()).extracting(EntityNode::id)
                .containsExactly("gen-1", "proc-1", "agg-1");
    }

    @Test
    @DisplayName("역할별 modelRef 를 계약에서 가져온다")
    void modelRefsFromContract() {
        AspectNode aspect = (AspectNode) ((EntityNode) compile().ses().tree()).axes().get(0);

        assertThat(aspect.components()).extracting(EntityNode::modelRef)
                .containsExactly("queue-local-source", "external-simpy-fifo", "queue-local-sink");
    }

    @Test
    @DisplayName("포트의 dataType 과 unit 을 계약에서 그대로 옮긴다")
    void portsCarryUnits() {
        AspectNode aspect = (AspectNode) ((EntityNode) compile().ses().tree()).axes().get(0);
        EntityNode processor = aspect.components().get(1);

        assertThat(processor.ports()).hasSize(2);
        assertThat(processor.hasPort("in", PortDirection.IN)).isTrue();
        assertThat(processor.hasPort("out", PortDirection.OUT)).isTrue();
        assertThat(processor.ports()).allMatch(p -> "건".equals(p.unit()));
        assertThat(processor.ports()).allMatch(p -> "job".equals(p.dataType()));
    }

    @Test
    @DisplayName("배선은 초안의 연결을 IC out->in 으로 옮긴다")
    void couplingsFromDraft() {
        AspectNode aspect = (AspectNode) ((EntityNode) compile().ses().tree()).axes().get(0);

        assertThat(aspect.couplings()).hasSize(2);
        assertThat(aspect.couplings()).allMatch(c -> c.kind() == CouplingKind.IC);
        assertThat(aspect.couplings()).allMatch(
                c -> "out".equals(c.fromPort()) && "in".equals(c.toPort()));
        assertThat(aspect.couplings()).extracting(c -> c.fromEntity() + "->" + c.toEntity())
                .containsExactly("gen-1->proc-1", "proc-1->agg-1");
    }

    @Test
    @DisplayName("필수이고 기본값 없는 파라미터만 변수와 슬롯이 된다")
    void openParamsBecomeVarsAndSlots() {
        CompiledDesign design = compile();
        AspectNode aspect = (AspectNode) ((EntityNode) design.ses().tree()).axes().get(0);

        EntityNode source = aspect.components().get(0);
        assertThat(source.vars()).extracting(VarDef::name)
                .containsExactly("arrivalInterval", "totalJobs");
        assertThat(source.vars()).allMatch(VarDef::isOpen);

        assertThat(aspect.components().get(2).vars()).isEmpty();

        assertThat(design.template().slots()).extracting(SlotSpec::name)
                .containsExactly("arrivalInterval", "totalJobs", "serviceTime");
    }

    @Test
    @DisplayName("기본값이 있는 파라미터는 변수에 기본값이 들어가고 슬롯을 만들지 않는다")
    void defaultedParamGetsNoSlot() {
        Map<String, ModelContract> byRole = contracts();
        byRole.put("SINK", new ModelContract("queue-local-sink", "model-base", "분",
                List.of(new PortContract("in", "job", "건")), List.of(),
                List.of(new ParamContract("관측시간", VarType.DOUBLE, "분", false,
                        Range.none(), null, 2000.0))));

        CompiledDesign design = compiler.compile(draft(), topology, byRole, "queue-x");
        AspectNode aspect = (AspectNode) ((EntityNode) design.ses().tree()).axes().get(0);
        VarDef observed = aspect.components().get(2).var("관측시간");

        assertThat(observed).isNotNull();
        assertThat(observed.isOpen()).isFalse();
        assertThat(observed.effectiveValue()).isEqualTo(2000.0);
        assertThat(design.template().slots()).extracting(SlotSpec::name)
                .doesNotContain("관측시간");
    }

    @Test
    @DisplayName("슬롯이 계약의 타입/단위/범위/질문 문구를 그대로 복사한다")
    void slotCopiesContract() {
        ValueSlot slot = (ValueSlot) compile().template().slotByName("serviceTime").orElseThrow();

        assertThat(slot.type()).isEqualTo(VarType.DOUBLE);
        assertThat(slot.unit()).isEqualTo("분");
        assertThat(slot.range().min()).isEqualTo(0.01);
        assertThat(slot.range().max()).isEqualTo(10.0);
        assertThat(slot.question().text()).isEqualTo("작업 한 건의 처리시간은 몇 분인가요?");
        assertThat(slot.question().reaskOrText()).isEqualTo("작업 한 건의 처리시간은 몇 분인가요?");
        assertThat(slot.inferable()).isFalse();
        assertThat(slot.defaultValue()).isNull();
        assertThat(slot.dependsOn()).isEmpty();
    }

    @Test
    @DisplayName("질문 문구가 없는 계약은 이름과 단위로 문구를 만든다")
    void generatesQuestionWhenContractIsSilent() {
        Map<String, ModelContract> byRole = contracts();
        byRole.put("PROCESSOR", new ModelContract("external-simpy-fifo", "mcp", "분",
                List.of(new PortContract("in", "job", "건")),
                List.of(new PortContract("out", "job", "건")),
                List.of(new ParamContract("serviceTime", VarType.DOUBLE, "분", true,
                        Range.between(0.01, 10), null, null))));

        ValueSlot slot = (ValueSlot) compiler.compile(draft(), topology, byRole, "queue-y")
                .template().slotByName("serviceTime").orElseThrow();

        assertThat(slot.question().text()).contains("serviceTime").contains("분");
    }

    @Test
    @DisplayName("앵커가 소유 엔티티와 변수를 가리킨다")
    void anchorPointsAtOwningEntity() {
        ValueSlot slot = (ValueSlot) compile().template()
                .slotByName("arrivalInterval").orElseThrow();

        assertThat(slot.anchor().axis()).isEqualTo(org.hanbat.ses.core.model.AxisType.VARIABLE);
        assertThat(slot.anchor().targetNodeId()).isEqualTo("gen-1");
        assertThat(slot.anchor().varName()).isEqualTo("arrivalInterval");
        assertThat(slot.anchor().entityPath()).isEqualTo("대기행렬/작업생성");
    }

    @Test
    @DisplayName("템플릿의 실행 설정이 계약과 스펙 고정값을 따른다")
    void templateExecutionSettings() {
        var template = compile().template();

        assertThat(template.id()).isEqualTo("queue-abc123def456");
        assertThat(template.name()).isEqualTo("단일 FIFO 대기행렬");
        assertThat(template.binding().sesDefinitionId()).isEqualTo("queue-abc123def456");
        assertThat(template.binding().rootEntity()).isEqualTo("대기행렬");
        assertThat(template.execution().simulator().engine()).isEqualTo("queue-mcp-batch");
        assertThat(template.execution().simulator().horizon()).isEqualTo(2000.0);
        assertThat(template.execution().simulator().seed()).isEqualTo(42L);
        assertThat(template.execution().simulator().timeResolution()).isEqualTo(0.01);
        assertThat(template.execution().simulator().mode())
                .isEqualTo(org.hanbat.ses.template.model.RunMode.SINGLE);
        assertThat(template.execution().mcpTools()).containsExactly(
                "search_models", "describe_model", "provision_model", "run_processor");
        assertThat(template.dialogue().unfilledPolicy())
                .isEqualTo(org.hanbat.ses.template.model.UnfilledPolicy.ASK_AGAIN);
        assertThat(template.validation().unitAliases()).containsEntry("min", "분");
        assertThat(template.meta().description()).isEqualTo("단일 FIFO 대기행렬");
        assertThat(template.output().sections()).containsExactly(
                org.hanbat.ses.template.model.OutputSection.SUMMARY,
                org.hanbat.ses.template.model.OutputSection.TABLE,
                org.hanbat.ses.template.model.OutputSection.RAW);
    }

    @Test
    @DisplayName("산출물이 기존 SES 공리 검사를 통과한다")
    void passesAxioms() {
        List<ValidationIssue> issues = new SesAxiomValidator().validate(compile().ses().tree());

        assertThat(issues).filteredOn(ValidationIssue::isError).isEmpty();
    }

    @Test
    @DisplayName("산출물이 기존 구조/단위 검사를 통과한다")
    void passesStructureCheck() {
        List<ValidationIssue> issues = new SesStructureChecker(Map.of("min", "분"))
                .check(compile().ses().tree());

        assertThat(issues).filteredOn(ValidationIssue::isError).isEmpty();
    }

    @Test
    @DisplayName("산출물이 기존 템플릿-SES 정합성 검사를 통과한다")
    void passesConsistencyCheck() {
        CompiledDesign design = compile();
        List<ValidationIssue> issues = new TemplateConsistencyChecker()
                .check(design.template(), design.ses().tree());

        assertThat(issues).filteredOn(ValidationIssue::isError).isEmpty();
    }

    @Test
    @DisplayName("역할에 대응하는 계약이 없으면 실패한다")
    void missingContractFails() {
        Map<String, ModelContract> byRole = contracts();
        byRole.remove("PROCESSOR");

        assertThatThrownBy(() -> compiler.compile(draft(), topology, byRole, "queue-z"))
                .isInstanceOf(CompileException.class)
                .hasMessageContaining("PROCESSOR");
    }

    @Test
    @DisplayName("두 역할이 같은 파라미터 이름을 쓰면 실패한다")
    void duplicateParamNameFails() {
        Map<String, ModelContract> byRole = contracts();
        byRole.put("SINK", new ModelContract("queue-local-sink", "model-base", "분",
                List.of(new PortContract("in", "job", "건")), List.of(),
                List.of(new ParamContract("serviceTime", VarType.DOUBLE, "분", true,
                        Range.between(0.01, 10), "중복 이름", null))));

        assertThatThrownBy(() -> compiler.compile(draft(), topology, byRole, "queue-w"))
                .isInstanceOf(CompileException.class)
                .hasMessageContaining("serviceTime");
    }

    @Test
    @DisplayName("newDesignId 는 queue- 접두사와 12자리 16진수를 낸다")
    void designIdShape() {
        assertThat(SesCompiler.newDesignId()).matches("queue-[0-9a-f]{12}");
        assertThat(SesCompiler.newDesignId()).isNotEqualTo(SesCompiler.newDesignId());
    }
}
```

- [ ] **Step 4: 테스트가 실패하는 것을 확인한다**

Run: `./gradlew :modules:design:test --tests '*SesCompilerTest'`
Expected: 컴파일 실패 — `SesCompiler` 심볼을 찾을 수 없음

- [ ] **Step 5: `SesCompiler` 를 구현한다**

```java
package org.hanbat.ses.design.compile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.CouplingSpec;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.PortDef;
import org.hanbat.ses.core.model.PortDirection;
import org.hanbat.ses.core.model.SesAnchor;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.design.contract.ModelContract;
import org.hanbat.ses.design.contract.ParamContract;
import org.hanbat.ses.design.contract.PortContract;
import org.hanbat.ses.design.draft.DraftConnection;
import org.hanbat.ses.design.draft.DraftNode;
import org.hanbat.ses.design.draft.StructureDraft;
import org.hanbat.ses.template.topology.RoleSpec;
import org.hanbat.ses.template.topology.TopologyContract;
import org.hanbat.ses.template.model.DialogueControl;
import org.hanbat.ses.template.model.ExecutionSpec;
import org.hanbat.ses.template.model.FailurePolicy;
import org.hanbat.ses.template.model.LlmConfig;
import org.hanbat.ses.template.model.OutputFormat;
import org.hanbat.ses.template.model.OutputSection;
import org.hanbat.ses.template.model.OutputSpec;
import org.hanbat.ses.template.model.QuestionSpec;
import org.hanbat.ses.template.model.Routing;
import org.hanbat.ses.template.model.RunMode;
import org.hanbat.ses.template.model.SimulatorConfig;
import org.hanbat.ses.template.model.SlotSpec;
import org.hanbat.ses.template.model.StructureBinding;
import org.hanbat.ses.template.model.SubtaskTemplate;
import org.hanbat.ses.template.model.TemplateMeta;
import org.hanbat.ses.template.model.UnfilledPolicy;
import org.hanbat.ses.template.model.ValidationSpec;
import org.hanbat.ses.template.model.ValueSlot;
import org.hanbat.ses.template.registry.SesDefinition;
import org.springframework.stereotype.Component;

/**
 * 초안 + 모델 계약 -> SES 정의 + 서브태스크 템플릿.
 *
 * <p>LLM 이 정한 것(노드 id·이름·배선)과 계약이 정한 것(모델 참조·포트·파라미터·범위·
 * 질문 문구·시간 단위·horizon)을 여기서 합친다. 숫자와 형식은 전부 계약 쪽에서 온다.
 *
 * <p>산출물은 기존 레지스트리에 등록되어 SesAxiomValidator, SesStructureChecker,
 * TemplateConsistencyChecker 를 통과해야 한다. 그 셋이 이 컴파일러의 진짜 테스트다.
 */
@Component
public class SesCompiler {

    private static final String MCP_TOOL_SEARCH = "search_models";
    private static final String MCP_TOOL_DESCRIBE = "describe_model";
    private static final String MCP_TOOL_PROVISION = "provision_model";
    private static final String MCP_TOOL_RUN = "run_processor";

    /** 실험의 uuid4().hex[:12] 와 같은 모양. */
    public static String newDesignId() {
        return "queue-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public CompiledDesign compile(StructureDraft draft, TopologyContract topology,
                                  Map<String, ModelContract> byRole, String designId) {
        List<EntityNode> components = new ArrayList<>();
        List<SlotSpec> slots = new ArrayList<>();
        List<CompiledDesign.RoleContract> used = new ArrayList<>();
        Set<String> slotNames = new LinkedHashSet<>();

        for (DraftNode node : draft.nodes()) {
            RoleSpec role = topology.role(node.role());
            ModelContract contract = byRole.get(node.role());
            if (contract == null) {
                throw new CompileException(
                        node.role() + " 역할의 모델 계약이 없습니다. 조회가 누락되었습니다.");
            }
            used.add(new CompiledDesign.RoleContract(node.role(), contract));

            String entityPath = topology.rootEntityName() + "/" + node.name();
            List<VarDef> vars = new ArrayList<>();

            for (ParamContract param : contract.params()) {
                vars.add(new VarDef(param.name(), param.type(), param.unit(),
                        null, param.defaultValue(), false, param.range()));

                if (!param.opensSlot()) {
                    continue;
                }
                if (!slotNames.add(param.name())) {
                    throw new CompileException("두 모델이 같은 파라미터 이름을 씁니다: "
                            + param.name() + ". 슬롯 이름이 겹쳐 답변을 구분할 수 없습니다.");
                }
                slots.add(new ValueSlot(
                        param.name(),
                        SesAnchor.variable(entityPath, node.id(), param.name()),
                        param.type(),
                        param.unit(),
                        param.range(),
                        null,
                        false,
                        questionOf(param),
                        List.of()));
            }

            components.add(new EntityNode(node.id(), node.name(), vars, List.of(),
                    modelRefOf(role, contract), portsOf(contract), List.of(), List.of()));
        }

        List<CouplingSpec> couplings = new ArrayList<>();
        for (DraftConnection c : draft.connections()) {
            couplings.add(CouplingSpec.ic(c.from(), "out", c.to(), "in"));
        }

        SesNode aspect = new AspectNode(topology.reservedNodeIds().get(1), topology.aspectName(),
                components, couplings);
        EntityNode root = new EntityNode(topology.reservedNodeIds().get(0),
                topology.rootEntityName(), List.of(), List.of(aspect),
                null, List.of(), List.of(), List.of());

        SesDefinition ses = new SesDefinition(designId, "queue-experiment", "1.0.0", root);
        return new CompiledDesign(ses, template(draft, topology, designId, slots), used);
    }

    // ------------------------------------------------------------ 부분

    /**
     * 외부 역할은 계약이 말하는 modelId 를, 내부 역할은 토폴로지가 말하는 modelRef 를 쓴다.
     *
     * <p>둘이 어긋나면 실패시킨다. 내부 역할의 modelRef 는 토폴로지가 못 박은 값이고,
     * 계약을 조회할 때 그 값으로 조회했으므로 다를 수 없다 — 다르면 조회 로직이 틀렸다.
     */
    private String modelRefOf(RoleSpec role, ModelContract contract) {
        if (role.external()) {
            return contract.modelId();
        }
        if (!role.modelRef().equals(contract.modelId())) {
            throw new CompileException(role.role() + " 역할의 모델이 계약과 다릅니다: 기대 "
                    + role.modelRef() + ", 조회 " + contract.modelId());
        }
        return role.modelRef();
    }

    private List<PortDef> portsOf(ModelContract contract) {
        List<PortDef> ports = new ArrayList<>();
        for (PortContract p : contract.in()) {
            ports.add(new PortDef(p.name(), PortDirection.IN, p.dataType(), p.unit()));
        }
        for (PortContract p : contract.out()) {
            ports.add(new PortDef(p.name(), PortDirection.OUT, p.dataType(), p.unit()));
        }
        return ports;
    }

    /**
     * 계약이 질문 문구를 갖고 있으면 그것을 쓴다.
     *
     * <p>모델이 자기 파라미터를 어떻게 물어야 하는지 알고 있다는 것이 이 시스템의 전제다.
     * 없을 때만 이름과 단위로 만든다 — 어색하지만, 문구가 없다고 질문 자체를 못 내면
     * 계약을 갖춘 모델만 쓸 수 있게 된다.
     */
    private QuestionSpec questionOf(ParamContract param) {
        if (param.question() != null && !param.question().isBlank()) {
            return new QuestionSpec(param.question(), param.question(),
                    "기본값 없이 답변으로 확정합니다.");
        }
        String unit = param.unit() == null || param.unit().isBlank() ? "" : " (" + param.unit() + ")";
        String text = param.name() + unit + " 값을 알려주세요.";
        return new QuestionSpec(text, text, "기본값 없이 답변으로 확정합니다.");
    }

    private SubtaskTemplate template(StructureDraft draft, TopologyContract topology,
                                     String designId, List<SlotSpec> slots) {
        return new SubtaskTemplate(
                designId,
                "1.0.0",
                draft.title(),
                new Routing(List.of(topology.rootEntityName()),
                        "계약 " + topology.contractId() + " 로 컴파일된 런타임 설계", 1, List.of()),
                slots,
                // 슬롯 3개가 전부 필수이고 기본값이 없다. USE_DEFAULT 로 두면
                // 사용자가 답하지 않은 수치로 시뮬레이션이 돈다.
                new DialogueControl(10, 2, UnfilledPolicy.ASK_AGAIN),
                new StructureBinding(designId, topology.rootEntityName(), Map.of(), List.of()),
                new ValidationSpec(List.of(), Map.of("min", "분", "hr", "시간")),
                new ExecutionSpec(
                        LlmConfig.defaults(),
                        List.of(MCP_TOOL_SEARCH, MCP_TOOL_DESCRIBE,
                                MCP_TOOL_PROVISION, MCP_TOOL_RUN),
                        new SimulatorConfig("queue-mcp-batch", 0.01, topology.horizon(),
                                42L, RunMode.SINGLE, 1, 60_000L)),
                // RAW 를 담는 이유는 출처 증거(imageId, libraryVersion, manifestSha256)가
                // ResultFormatter 의 RAW 섹션으로만 응답에 나오기 때문이다. 그것이 없으면
                // 결과를 재현할 수 없고, 재현할 수 없는 결과는 연구에 쓸 수 없다.
                new OutputSpec(OutputFormat.COMPOSITE,
                        List.of(OutputSection.SUMMARY, OutputSection.TABLE,
                                OutputSection.RAW),
                        FailurePolicy.PARTIAL_RESULT),
                new TemplateMeta("design-api", LocalDate.now(), draft.title()));
    }
}
```

- [ ] **Step 6: 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :modules:design:test`
Expected: `SesCompilerTest` 16개 + Task 2·3의 20개 PASS

`passesAxioms`가 `ALTERNATING_MODE` 경고를 내면 무시한다 — 경고는 `filteredOn(isError)`에서
빠진다. `SPEC_TRIVIAL`은 SPEC 노드가 없으므로 나오지 않는다.

`passesStructureCheck`가 `PORT_MISSING`으로 실패하면 커플링이 `AspectNode`가 아니라
`EntityNode`에 붙었는지 확인한다. `SesStructureChecker`는 소유 엔티티의 축을 훑어
`AspectNode.couplings()`를 검사하므로, 컴포넌트 간 IC 는 aspect 쪽에 있어야 한다.

- [ ] **Step 7: 커밋**

```bash
git add modules/design
git commit -m "SesCompiler - 모델 계약에서 SES와 템플릿을 생성

각 역할 노드의 모델 계약 params 를 순회해 required 이고 기본값 없는
것만 VarDef 와 ValueSlot 으로 만든다. type/unit/range/question 은 전부
계약에서 복사한다. 기본값이 있는 파라미터는 VarDef.defaultValue 로 들어가
기존 isOpen() 규칙에 따라 질문이 생기지 않는다.

산출물이 SesAxiomValidator, SesStructureChecker,
TemplateConsistencyChecker 를 통과하는지 테스트가 직접 확인한다.
새 검증기를 만들지 않고 기존 28개 제약을 LLM 산출물에 적용하는 것이
이 설계의 핵심 이득이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: `DraftValidator` 와 `DraftPromptBuilder`

**Files:**
- Create: `modules/design/src/main/java/org/hanbat/ses/design/draft/DraftValidator.java`
- Create: `modules/design/src/main/java/org/hanbat/ses/design/draft/DraftPromptBuilder.java`
- Test: `modules/design/src/test/java/org/hanbat/ses/design/draft/DraftValidatorTest.java`
- Test: `modules/design/src/test/java/org/hanbat/ses/design/draft/DraftPromptBuilderTest.java`

**Interfaces:**
- Consumes: Task 2 `TopologyContract`, Task 4 `StructureDraft`·`DraftNode`·`DraftConnection`
- Produces: `DraftValidator.validate(StructureDraft, TopologyContract) -> List<ValidationIssue>`,
  `DraftPromptBuilder.systemPrompt(TopologyContract) -> String`

**무엇을 검사하고 무엇을 검사하지 않는가.** 노드 id 유일성·이름 중복·배선 무결성은 Task 4의
산출물이 등록될 때 `SesAxiomValidator`·`SesStructureChecker`가 이미 잡는다. 하지만 그 검사는
**컴파일 후**에 일어나므로, 잘못된 식별자로 트리를 만든 뒤 거부하게 된다. 컴파일러가 잘못된
입력으로 돌지 않게 하려면 앞에서 한 번 더 봐야 한다 — 중복을 감수하고 유지하는 검사다.
반대로 등록 검증이 모르는 것(역할 집합, 계약 배선 준수, 예약 id 충돌)은 여기서만 잡힌다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DraftValidatorTest.java`:

```java
package org.hanbat.ses.design.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.template.topology.TopologyContract;
import org.hanbat.ses.template.topology.TopologyContractRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DraftValidatorTest {

    private final DraftValidator validator = new DraftValidator();
    private TopologyContract topology;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/queue.topology.json")) {
            topology = TopologyContractRegistry.load(in);
        }
    }

    private static List<DraftNode> nodes() {
        List<DraftNode> list = new ArrayList<>();
        list.add(new DraftNode("gen-1", "작업생성", "SOURCE"));
        list.add(new DraftNode("proc-1", "처리기", "PROCESSOR"));
        list.add(new DraftNode("agg-1", "집계", "SINK"));
        return list;
    }

    private static List<DraftConnection> wiring() {
        return List.of(new DraftConnection("gen-1", "proc-1"),
                new DraftConnection("proc-1", "agg-1"));
    }

    private static StructureDraft draft(List<DraftNode> nodes, List<DraftConnection> links) {
        return new StructureDraft("대기행렬", true, null, nodes, links, List.of("FIFO"));
    }

    private List<String> codes(StructureDraft draft) {
        return validator.validate(draft, topology).stream()
                .filter(ValidationIssue::isError)
                .map(ValidationIssue::code)
                .toList();
    }

    @Test
    @DisplayName("계약을 만족하는 초안은 오류가 없다")
    void validDraftPasses() {
        assertThat(codes(draft(nodes(), wiring()))).isEmpty();
    }

    @Test
    @DisplayName("supported=false 는 DRAFT_UNSUPPORTED 이고 reason 이 메시지에 담긴다")
    void unsupportedDraft() {
        StructureDraft d = new StructureDraft(null, false, "다중 서버를 요청했습니다",
                List.of(), List.of(), List.of());

        List<ValidationIssue> issues = validator.validate(d, topology);

        assertThat(issues).extracting(ValidationIssue::code).contains("DRAFT_UNSUPPORTED");
        assertThat(issues).extracting(ValidationIssue::message)
                .anyMatch(m -> m.contains("다중 서버를 요청했습니다"));
    }

    @Test
    @DisplayName("노드 수가 계약과 다르면 DRAFT_NODE_COUNT")
    void nodeCountMismatch() {
        List<DraftNode> four = nodes();
        four.add(new DraftNode("buf-1", "버퍼", "SOURCE"));

        assertThat(codes(draft(four, wiring()))).contains("DRAFT_NODE_COUNT");
    }

    @Test
    @DisplayName("역할 집합이 계약과 다르면 DRAFT_ROLE_SET")
    void roleSetMismatch() {
        List<DraftNode> wrong = List.of(
                new DraftNode("gen-1", "작업생성", "SOURCE"),
                new DraftNode("proc-1", "처리기", "PROCESSOR"),
                new DraftNode("proc-2", "처리기2", "PROCESSOR"));

        assertThat(codes(draft(wrong, wiring()))).contains("DRAFT_ROLE_SET");
    }

    @Test
    @DisplayName("같은 역할이 두 번 나오면 DRAFT_ROLE_SET")
    void duplicateRole() {
        List<DraftNode> dup = List.of(
                new DraftNode("a", "가", "SOURCE"),
                new DraftNode("b", "나", "SOURCE"),
                new DraftNode("c", "다", "SINK"));

        assertThat(codes(draft(dup, wiring()))).contains("DRAFT_ROLE_SET");
    }

    @Test
    @DisplayName("id 에 허용되지 않는 문자가 있으면 DRAFT_NODE_ID_INVALID")
    void invalidNodeId() {
        List<DraftNode> bad = nodes();
        bad.set(0, new DraftNode("작업_생성!", "작업생성", "SOURCE"));

        assertThat(codes(draft(bad, List.of(
                new DraftConnection("작업_생성!", "proc-1"),
                new DraftConnection("proc-1", "agg-1")))))
                .contains("DRAFT_NODE_ID_INVALID");
    }

    @Test
    @DisplayName("id 가 중복되면 DRAFT_NODE_ID_INVALID")
    void duplicateNodeId() {
        List<DraftNode> bad = nodes();
        bad.set(1, new DraftNode("gen-1", "처리기", "PROCESSOR"));

        assertThat(codes(draft(bad, wiring()))).contains("DRAFT_NODE_ID_INVALID");
    }

    @Test
    @DisplayName("예약 id 를 쓰면 DRAFT_NODE_ID_RESERVED")
    void reservedNodeId() {
        List<DraftNode> bad = nodes();
        bad.set(0, new DraftNode("queue-root", "작업생성", "SOURCE"));

        assertThat(codes(draft(bad, List.of(
                new DraftConnection("queue-root", "proc-1"),
                new DraftConnection("proc-1", "agg-1")))))
                .contains("DRAFT_NODE_ID_RESERVED");
    }

    @Test
    @DisplayName("이름이 중복되거나 경로 문자를 포함하거나 60자를 넘으면 DRAFT_NODE_NAME_INVALID")
    void invalidNames() {
        List<DraftNode> duplicate = nodes();
        duplicate.set(1, new DraftNode("proc-1", "작업생성", "PROCESSOR"));
        assertThat(codes(draft(duplicate, wiring()))).contains("DRAFT_NODE_NAME_INVALID");

        List<DraftNode> pathChar = nodes();
        pathChar.set(1, new DraftNode("proc-1", "처리기/1", "PROCESSOR"));
        assertThat(codes(draft(pathChar, wiring()))).contains("DRAFT_NODE_NAME_INVALID");

        List<DraftNode> tooLong = nodes();
        tooLong.set(1, new DraftNode("proc-1", "가".repeat(61), "PROCESSOR"));
        assertThat(codes(draft(tooLong, wiring()))).contains("DRAFT_NODE_NAME_INVALID");

        List<DraftNode> blank = nodes();
        blank.set(1, new DraftNode("proc-1", "   ", "PROCESSOR"));
        assertThat(codes(draft(blank, wiring()))).contains("DRAFT_NODE_NAME_INVALID");
    }

    @Test
    @DisplayName("배선이 계약과 다르면 DRAFT_WIRING")
    void wiringMismatch() {
        assertThat(codes(draft(nodes(), List.of(
                new DraftConnection("gen-1", "agg-1"),
                new DraftConnection("proc-1", "agg-1")))))
                .contains("DRAFT_WIRING");
    }

    @Test
    @DisplayName("사이클은 DRAFT_WIRING 으로 거부한다")
    void cycleRejected() {
        assertThat(codes(draft(nodes(), List.of(
                new DraftConnection("gen-1", "proc-1"),
                new DraftConnection("proc-1", "agg-1"),
                new DraftConnection("agg-1", "gen-1")))))
                .contains("DRAFT_WIRING");
    }

    @Test
    @DisplayName("배선 수가 계약과 같아도 방향이 뒤집히면 DRAFT_WIRING")
    void reversedWiring() {
        assertThat(codes(draft(nodes(), List.of(
                new DraftConnection("proc-1", "gen-1"),
                new DraftConnection("agg-1", "proc-1")))))
                .contains("DRAFT_WIRING");
    }

    @Test
    @DisplayName("초안에 없는 노드를 가리키는 배선은 DRAFT_WIRING")
    void danglingWiring() {
        assertThat(codes(draft(nodes(), List.of(
                new DraftConnection("gen-1", "없는노드"),
                new DraftConnection("proc-1", "agg-1")))))
                .contains("DRAFT_WIRING");
    }
}
```

`DraftPromptBuilderTest.java`:

```java
package org.hanbat.ses.design.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;

import org.hanbat.ses.template.topology.TopologyContract;
import org.hanbat.ses.template.topology.TopologyContractRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DraftPromptBuilderTest {

    private final DraftPromptBuilder builder = new DraftPromptBuilder();
    private TopologyContract topology;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/queue.topology.json")) {
            topology = TopologyContractRegistry.load(in);
        }
    }

    @Test
    @DisplayName("프롬프트가 계약에서 역할, 배선, 노드 수를 가져온다")
    void promptCarriesContract() {
        String prompt = builder.systemPrompt(topology);

        assertThat(prompt)
                .contains("SOURCE").contains("PROCESSOR").contains("SINK")
                .contains("3")
                .contains("SOURCE -> PROCESSOR")
                .contains("PROCESSOR -> SINK");
    }

    @Test
    @DisplayName("미지원 목록이 계약에서 온다 - 프롬프트에 상수로 박혀 있지 않다")
    void unsupportedListFromContract() {
        String prompt = builder.systemPrompt(topology);

        assertThat(prompt).contains("다중 서버").contains("우선순위")
                .contains("확률분포").contains("피드백").contains("유한 대기실")
                .contains("supported");
    }

    @Test
    @DisplayName("예약 id 를 쓰지 말라고 명시한다")
    void warnsAboutReservedIds() {
        assertThat(builder.systemPrompt(topology))
                .contains("queue-root").contains("queue-components");
    }

    @Test
    @DisplayName("수치와 실행 코드를 만들지 말라고 명시한다")
    void forbidsNumbersAndCode() {
        assertThat(builder.systemPrompt(topology))
                .contains("수치").contains("URL").contains("Docker");
    }
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `./gradlew :modules:design:test --tests '*Draft*Test'`
Expected: 컴파일 실패 — `DraftValidator` 심볼을 찾을 수 없음

- [ ] **Step 3: `DraftValidator` 를 구현한다**

```java
package org.hanbat.ses.design.draft;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.template.topology.TopologyContract;
import org.springframework.stereotype.Component;

/**
 * 결정론적 초안 검증.
 *
 * <p>LLM 이 계약 안에 머물렀는지만 본다. <b>자연어 의도와 초안이 맞는지는 증명하지
 * 않는다</b> — 계약을 만족하지만 사용자가 원한 것과 다른 구조일 수 있고, 그건 응답의
 * assumptions 와 sesSnapshot 으로 사용자가 확인한다.
 *
 * <p>노드 id 유일성과 이름 중복은 등록 시점의 SesAxiomValidator 가 다시 잡는다. 그래도
 * 여기서 보는 이유는 컴파일러가 잘못된 식별자로 트리를 만들지 않게 하려는 것이다 —
 * 만든 뒤 거부하면 실패 지점이 원인에서 멀어진다.
 */
@Component
public class DraftValidator {

    private static final int MAX_NAME_LENGTH = 60;
    private static final String FORBIDDEN_NAME_CHARS = "/[]#";

    public List<ValidationIssue> validate(StructureDraft draft, TopologyContract topology) {
        List<ValidationIssue> issues = new ArrayList<>();

        if (!draft.supported()) {
            issues.add(ValidationIssue.error("<draft>", "DRAFT_UNSUPPORTED",
                    "요청이 지원 범위를 벗어납니다: "
                            + (draft.reason() == null ? "(사유 없음)" : draft.reason())));
            // 지원하지 않는 초안은 노드가 비어 있는 것이 정상이다. 아래 검사는 무의미하다.
            return List.copyOf(issues);
        }

        if (draft.nodes().size() != topology.nodeCount()) {
            issues.add(ValidationIssue.error("<draft>", "DRAFT_NODE_COUNT",
                    "구성요소가 " + topology.nodeCount() + "개여야 하는데 "
                            + draft.nodes().size() + "개입니다."));
        }

        Set<String> roles = new LinkedHashSet<>();
        boolean roleDuplicated = false;
        for (DraftNode node : draft.nodes()) {
            if (!roles.add(node.role())) {
                roleDuplicated = true;
            }
        }
        if (roleDuplicated || !roles.equals(topology.roleNames())) {
            issues.add(ValidationIssue.error("<draft>", "DRAFT_ROLE_SET",
                    "역할 구성이 계약과 다릅니다: 기대 " + topology.roleNames()
                            + ", 수신 " + draft.nodes().stream().map(DraftNode::role).toList()));
        }

        checkIds(draft, topology, issues);
        checkNames(draft, issues);
        checkWiring(draft, topology, issues);

        return List.copyOf(issues);
    }

    private void checkIds(StructureDraft draft, TopologyContract topology,
                          List<ValidationIssue> issues) {
        Set<String> seen = new LinkedHashSet<>();
        for (DraftNode node : draft.nodes()) {
            String id = node.id();
            if (id == null || id.isBlank() || !id.chars().allMatch(
                    c -> c == '-' || Character.isLetterOrDigit(c) && c < 128)) {
                issues.add(ValidationIssue.error(String.valueOf(id), "DRAFT_NODE_ID_INVALID",
                        "노드 id 는 영문/숫자/하이픈만 쓸 수 있습니다: " + id));
                continue;
            }
            if (!seen.add(id)) {
                issues.add(ValidationIssue.error(id, "DRAFT_NODE_ID_INVALID",
                        "노드 id 가 중복됩니다: " + id));
            }
            if (topology.reservedNodeIds().contains(id)) {
                issues.add(ValidationIssue.error(id, "DRAFT_NODE_ID_RESERVED",
                        "컴파일러가 쓰는 예약 id 입니다: " + id
                                + " (예약: " + topology.reservedNodeIds() + ")"));
            }
        }
    }

    private void checkNames(StructureDraft draft, List<ValidationIssue> issues) {
        Set<String> seen = new LinkedHashSet<>();
        for (DraftNode node : draft.nodes()) {
            String name = node.name();
            if (name == null || name.isBlank()) {
                issues.add(ValidationIssue.error(String.valueOf(node.id()),
                        "DRAFT_NODE_NAME_INVALID", "이름이 비어 있습니다."));
                continue;
            }
            if (name.length() > MAX_NAME_LENGTH) {
                issues.add(ValidationIssue.error(node.id(), "DRAFT_NODE_NAME_INVALID",
                        "이름이 " + MAX_NAME_LENGTH + "자를 넘습니다: " + name.length() + "자"));
            }
            // 이름은 SesAnchor.entityPath 에 "루트/이름" 으로 들어간다.
            // 경로 구분자와 인스턴스 접미사가 섞이면 앵커 매칭이 깨진다.
            for (char c : FORBIDDEN_NAME_CHARS.toCharArray()) {
                if (name.indexOf(c) >= 0) {
                    issues.add(ValidationIssue.error(node.id(), "DRAFT_NODE_NAME_INVALID",
                            "이름에 쓸 수 없는 문자가 있습니다(" + c + "): " + name));
                    break;
                }
            }
            if (!seen.add(name.trim())) {
                issues.add(ValidationIssue.error(node.id(), "DRAFT_NODE_NAME_INVALID",
                        "이름이 중복됩니다: " + name));
            }
        }
    }

    private void checkWiring(StructureDraft draft, TopologyContract topology,
                             List<ValidationIssue> issues) {
        // 노드 id -> 역할. 배선을 역할 쌍으로 바꿔 계약과 대조한다.
        java.util.Map<String, String> roleOf = new java.util.LinkedHashMap<>();
        draft.nodes().forEach(n -> roleOf.put(n.id(), n.role()));

        Set<String> actual = new LinkedHashSet<>();
        for (DraftConnection c : draft.connections()) {
            String from = roleOf.get(c.from());
            String to = roleOf.get(c.to());
            if (from == null || to == null) {
                issues.add(ValidationIssue.error("<draft>", "DRAFT_WIRING",
                        "초안에 없는 노드를 가리키는 배선입니다: " + c.from() + " -> " + c.to()));
                return;
            }
            actual.add(from + "->" + to);
        }

        Set<String> expected = topology.connectionPairs();
        if (draft.connections().size() != expected.size() || !actual.equals(expected)) {
            issues.add(ValidationIssue.error("<draft>", "DRAFT_WIRING",
                    "배선이 계약과 다릅니다: 기대 " + expected + ", 수신 " + actual
                            + (topology.allowFeedback() ? "" : " (사이클과 우회는 허용하지 않습니다)")));
        }
    }
}
```

역할 쌍으로 대조하기 때문에 사이클(`SINK->SOURCE`)과 우회(`SOURCE->SINK`)가 자동으로
걸린다 — 계약에 없는 쌍이 집합에 들어오거나 배선 수가 달라진다. 방향이 뒤집힌 경우도
`PROCESSOR->SOURCE`가 기대 집합에 없어 걸린다.

- [ ] **Step 4: `DraftPromptBuilder` 를 구현한다**

```java
package org.hanbat.ses.design.draft;

import java.util.stream.Collectors;

import org.hanbat.ses.template.topology.RoleSpec;
import org.hanbat.ses.template.topology.TopologyContract;
import org.springframework.stereotype.Component;

/**
 * 계약에서 시스템 프롬프트를 만든다.
 *
 * <p>지원 범위를 프롬프트 문자열로 갖고 있으면 계약과 프롬프트가 어긋난다 —
 * experiment.py 에서 실제로 그 상태였다. 계약을 고치면 프롬프트가 따라 바뀌어야 한다.
 */
@Component
public class DraftPromptBuilder {

    public String systemPrompt(TopologyContract contract) {
        String roles = contract.roles().stream().map(RoleSpec::role)
                .collect(Collectors.joining(", "));
        String wiring = contract.requiredConnections().stream()
                .map(pair -> pair.get(0) + " -> " + pair.get(1))
                .collect(Collectors.joining(", "));
        String unsupported = String.join(", ", contract.unsupported());
        String reserved = String.join(", ", contract.reservedNodeIds());

        return """
                사용자 요청으로 시뮬레이션 구조 초안을 구성하세요.

                지원 범위는 %s 역할을 가진 정확히 %d개 구성요소와 %s 배선입니다.
                요청이 %s 중 하나를 명시하면 supported=false 로 표시하고 reason 에 이유를 쓰세요.

                지원하는 요청이면 각 역할에 하나씩 노드를 만들고 배선 %d개를 제안하세요.
                노드 id 는 영문/숫자/하이픈만 쓰고 서로 다르게 정하세요.
                %s 는 예약된 id 이므로 쓰지 마세요.
                노드 name 은 서로 다른 짧은 한국어로 쓰고, /[]# 문자와 60자 초과를 피하세요.
                title 은 짧은 한국어 제목입니다.

                수치, 실행 코드, URL, Docker 명령을 만들지 마세요 — 부족한 수치는 서버가
                사용자에게 질문합니다. 파라미터의 범위와 단위는 모델 계약이 정합니다.

                assumptions 에 이 구조가 전제하는 것과 종료 조건을 명시하세요.
                """.formatted(roles, contract.nodeCount(), wiring, unsupported,
                        contract.requiredConnections().size(), reserved);
    }
}
```

- [ ] **Step 5: 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :modules:design:test`
Expected: `DraftValidatorTest` 13개 + `DraftPromptBuilderTest` 4개 + 앞선 36개 PASS

- [ ] **Step 6: 커밋**

```bash
git add modules/design
git commit -m "DraftValidator, DraftPromptBuilder - 초안 검증과 프롬프트를 계약에서 파생

역할 집합, 노드 수, id 형식/중복/예약, 이름 형식/중복, 배선 준수를
검사한다. 배선은 노드 id 를 역할 쌍으로 바꿔 계약과 대조하므로 사이클,
우회, 역방향이 별도 코드 없이 함께 걸린다.

프롬프트도 같은 계약에서 만든다. experiment.py 는 지원 범위를 프롬프트
문자열과 validate_draft 두 곳에 갖고 있어 어긋날 수 있었다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: LLM 게이트웨이 확장 — 시드와 스키마 제약 (Task 7보다 먼저 해도 되고 나중이어도 된다)

**Files:**
- Modify: `modules/llm/src/main/java/org/hanbat/ses/llm/gateway/Purpose.java`
- Modify: `modules/llm/src/main/java/org/hanbat/ses/llm/gateway/LlmProperties.java`
- Modify: `modules/llm/src/main/java/org/hanbat/ses/llm/gateway/HttpLlmGateway.java`
- Modify: `modules/llm/src/main/java/org/hanbat/ses/llm/gateway/OpenAiCompatibleLlmGateway.java`
- Modify: `modules/llm/src/main/java/org/hanbat/ses/llm/gateway/AnthropicLlmGateway.java`
- Modify: `modules/llm/src/test/java/org/hanbat/ses/llm/gateway/OpenAiCompatibleLlmGatewayTest.java`
- Modify: `modules/llm/src/test/java/org/hanbat/ses/llm/gateway/OllamaLiveTest.java`
- Modify: `app/src/main/java/org/hanbat/ses/app/config/LlmConfiguration.java`
- Modify: `app/src/main/resources/application.yml`
- Modify: `app/src/main/resources/application-ollama.yml`

**Interfaces:**
- Consumes: 없음 (기존 `modules:llm` 내부 변경)
- Produces: `Purpose.STRUCTURE_DRAFT`,
  `LlmProperties.seed() -> Long`, `LlmProperties.temperatureFor(Purpose) -> double`,
  `LlmProperties.schemaConstrained(Purpose) -> boolean`,
  변경된 `HttpLlmGateway.requestBody(String system, String user, String model, boolean jsonOnly, Purpose purpose, String jsonSchema)`

**실측 근거 (스펙 §6.1, 2026-09-09 로컬 Ollama 0.33.3).** OpenAI 호환 엔드포인트
`/v1/chat/completions`가 `response_format: {"type":"json_schema", "json_schema":{...,"strict":true}}`
를 200으로 받고 스키마를 정확히 지켰다. `seed: 42` + `temperature: 0` 으로 두 번 호출했을 때
출력이 완전히 동일했다. 반면 현재 코드가 쓰는 `json_object` 는 같은 프롬프트에서 스키마를
어겼다 — `nodes` 배열 대신 `source`/`processor`/`sink` 키 3개짜리 객체가 왔다.
**그래서 네이티브 게이트웨이를 새로 만들지 않는다.**

**`LlmProperties` 생성자 호출 지점이 7곳이다.** 위치 기반 record 생성자라 필드를 추가하면
전부 깨진다. 이 태스크는 그 7곳을 모두 고친다.

| 파일 | 줄 |
|---|---|
| `LlmProperties.disabled()` | 68 |
| `LlmProperties.anthropic(...)` | 74 |
| `LlmProperties.openAiCompatible(...)` | 80 |
| `OllamaLiveTest` | 58 |
| `OpenAiCompatibleLlmGatewayTest` | 78, 125 |
| `LlmConfiguration.llmProperties(...)` | 61 |

- [ ] **Step 1: `Purpose` 에 항목을 추가하고 javadoc 을 고친다**

`Purpose.java` 전체를 교체:

```java
package org.hanbat.ses.llm.gateway;

/**
 * LLM 을 부르는 지점.
 *
 * <p>LLM 은 선언된 토폴로지 계약 안에서 구조를 <b>제안</b>할 수 있다. 확정은 결정론적
 * 검증기와 컴파일러가 한다 — pruning, 검증, 완료 판정은 여전히 전부 결정론적 Java 코드다.
 * 재현성이 필요한 시뮬레이션 시스템에서 LLM 이 구조를 <em>확정</em>하게 두면 같은 입력에
 * 다른 모델이 나온다.
 */
public enum Purpose {
    /** 요청문 -> 템플릿 분류. 실패하면 키워드 매칭으로 떨어진다. */
    ROUTING,
    /** 요청문 -> 슬롯 값 일괄 추출. 실패하면 전부 질문한다. */
    EXTRACTION,
    /** 기계 생성 질문/결과 -> 자연스러운 한국어. 실패하면 원문 그대로 쓴다. */
    PHRASING,
    /** 결과 요약. 실패하면 통계 표만 보여준다. */
    SUMMARY,
    /**
     * 요청문 -> SES 구조 초안. LLM 은 노드 이름·역할·연결만 제안한다.
     *
     * <p>형식·모델 계약·수치는 DraftValidator 와 SesCompiler 가 결정론적으로 부여하고,
     * 선언된 TopologyContract 를 벗어난 초안은 거부한다. 다른 목적과 달리
     * <b>폴백이 없다</b> — 실패하면 요청 전체가 실패한다. 가짜 초안으로 진행하면
     * 사용자가 요청하지 않은 구조로 시뮬레이션이 돈다.
     */
    STRUCTURE_DRAFT
}
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`OpenAiCompatibleLlmGatewayTest.java` 에 테스트 4개를 추가한다. 기존 `setUp()`의
`LlmProperties.openAiCompatible(...)` 호출은 그대로 두고(팩토리가 새 필드를 채운다),
새 테스트만 별도 프로퍼티를 만든다.

```java
    @Test
    @DisplayName("seed 가 설정되어 있으면 요청 본문에 실린다")
    void sendsSeed() {
        LlmProperties props = new LlmProperties(LlmProvider.OPENAI_COMPATIBLE, null,
                "http://localhost:" + server.port(), "qwen2.5:7b", java.util.Map.of(),
                0.2, 512, 5000L, 0, true, 1,
                42L, java.util.Map.of(), java.util.Set.of());
        OpenAiCompatibleLlmGateway g = new OpenAiCompatibleLlmGateway(
                WebClient.builder().baseUrl(props.baseUrl()).build(),
                new ObjectMapper(), recorded::add, props);
        stubContent("{\"templateId\":\"t\",\"confidence\":1.0,\"reason\":\"r\"}");

        g.complete("sys", "user", Answer.class, Purpose.ROUTING);

        assertThat(server.getAllServeEvents().get(0).getRequest().getBodyAsString())
                .contains("\"seed\":42");
    }

    @Test
    @DisplayName("seed 가 없으면 요청 본문에 보내지 않는다 - 기존 동작")
    void omitsSeedWhenUnset() {
        stubContent("{\"templateId\":\"t\",\"confidence\":1.0,\"reason\":\"r\"}");

        gateway.complete("sys", "user", Answer.class, Purpose.ROUTING);

        assertThat(server.getAllServeEvents().get(0).getRequest().getBodyAsString())
                .doesNotContain("seed");
    }

    @Test
    @DisplayName("목적별 온도가 전역 온도를 덮는다")
    void perPurposeTemperature() {
        LlmProperties props = new LlmProperties(LlmProvider.OPENAI_COMPATIBLE, null,
                "http://localhost:" + server.port(), "qwen2.5:7b", java.util.Map.of(),
                0.7, 512, 5000L, 0, true, 1,
                null, java.util.Map.of("STRUCTURE_DRAFT", 0.0), java.util.Set.of());
        OpenAiCompatibleLlmGateway g = new OpenAiCompatibleLlmGateway(
                WebClient.builder().baseUrl(props.baseUrl()).build(),
                new ObjectMapper(), recorded::add, props);

        stubContent("{\"templateId\":\"t\",\"confidence\":1.0,\"reason\":\"r\"}");
        g.complete("sys", "user", Answer.class, Purpose.STRUCTURE_DRAFT);
        assertThat(server.getAllServeEvents().get(0).getRequest().getBodyAsString())
                .contains("\"temperature\":0.0");

        server.resetAll();
        stubContent("{\"templateId\":\"t\",\"confidence\":1.0,\"reason\":\"r\"}");
        g.complete("sys", "user", Answer.class, Purpose.ROUTING);
        assertThat(server.getAllServeEvents().get(0).getRequest().getBodyAsString())
                .contains("\"temperature\":0.7");
    }

    @Test
    @DisplayName("스키마 제약 목적은 response_format 을 json_schema 로 보낸다")
    void sendsJsonSchema() {
        LlmProperties props = new LlmProperties(LlmProvider.OPENAI_COMPATIBLE, null,
                "http://localhost:" + server.port(), "qwen2.5:7b", java.util.Map.of(),
                0.0, 512, 5000L, 0, true, 1,
                42L, java.util.Map.of(), java.util.Set.of("STRUCTURE_DRAFT"));
        OpenAiCompatibleLlmGateway g = new OpenAiCompatibleLlmGateway(
                WebClient.builder().baseUrl(props.baseUrl()).build(),
                new ObjectMapper(), recorded::add, props);
        stubContent("{\"templateId\":\"t\",\"confidence\":1.0,\"reason\":\"r\"}");

        g.complete("sys", "user", Answer.class, Purpose.STRUCTURE_DRAFT);

        String body = server.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(body).contains("\"type\":\"json_schema\"")
                .contains("\"strict\":true")
                .contains("\"additionalProperties\":false")
                .contains("templateId");
    }

    @Test
    @DisplayName("스키마 제약이 아닌 목적은 json_object 를 유지한다")
    void keepsJsonObjectForOtherPurposes() {
        LlmProperties props = new LlmProperties(LlmProvider.OPENAI_COMPATIBLE, null,
                "http://localhost:" + server.port(), "qwen2.5:7b", java.util.Map.of(),
                0.0, 512, 5000L, 0, true, 1,
                42L, java.util.Map.of(), java.util.Set.of("STRUCTURE_DRAFT"));
        OpenAiCompatibleLlmGateway g = new OpenAiCompatibleLlmGateway(
                WebClient.builder().baseUrl(props.baseUrl()).build(),
                new ObjectMapper(), recorded::add, props);
        stubContent("{\"templateId\":\"t\",\"confidence\":1.0,\"reason\":\"r\"}");

        g.complete("sys", "user", Answer.class, Purpose.ROUTING);

        assertThat(server.getAllServeEvents().get(0).getRequest().getBodyAsString())
                .contains("\"type\":\"json_object\"")
                .doesNotContain("json_schema");
    }
```

`stubContent(...)` 는 기존 테스트에 이미 있는 헬퍼다. `server.resetAll()` 은 WireMock 의
기록을 비운다 — 한 테스트에서 두 번 호출할 때 필요하다.

- [ ] **Step 3: 테스트가 실패하는 것을 확인한다**

Run: `./gradlew :modules:llm:test`
Expected: 컴파일 실패 — `LlmProperties` 생성자 인자 개수 불일치

- [ ] **Step 4: `LlmProperties` 를 확장한다**

record 헤더와 정규 생성자를 다음으로 교체한다. 기존 필드 11개는 순서를 바꾸지 않는다.

```java
public record LlmProperties(
        LlmProvider provider,
        String apiKey,
        String baseUrl,
        String defaultModel,
        Map<String, String> modelByPurpose,
        double temperature,
        int maxTokens,
        long timeoutMs,
        int maxRetries,
        boolean jsonMode,
        int maxParseAttempts,
        /** 난수 시드. null 이면 보내지 않는다 — 기존 동작. */
        Long seed,
        /** 목적별 온도 오버라이드. 비어 있으면 전역 temperature. */
        Map<String, Double> temperatureByPurpose,
        /**
         * 스키마 제약 디코딩을 쓸 목적.
         *
         * <p>비어 있으면 json_object 를 그대로 쓴다. Ollama 0.33.3 의 OpenAI 호환
         * 경로가 json_schema 를 지원하는 것을 확인했지만, 다른 공급자나 구버전은
         * 거부할 수 있어 기본값은 끈 상태로 둔다.
         */
        Set<String> schemaConstrainedPurposes
) {

    public LlmProperties {
        provider = provider == null ? LlmProvider.AUTO : provider;
        boolean anthropicPath = provider == LlmProvider.ANTHROPIC || provider == LlmProvider.AUTO;
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = anthropicPath ? "https://api.anthropic.com" : null;
        }
        if (defaultModel == null || defaultModel.isBlank()) {
            defaultModel = anthropicPath ? "claude-sonnet-5" : null;
        }
        modelByPurpose = modelByPurpose == null ? Map.of() : Map.copyOf(modelByPurpose);
        if (maxTokens <= 0) {
            maxTokens = 4096;
        }
        if (timeoutMs <= 0) {
            timeoutMs = 60_000L;
        }
        if (maxRetries < 0) {
            maxRetries = 3;
        }
        if (maxParseAttempts <= 0) {
            maxParseAttempts = 2;
        }
        temperatureByPurpose = temperatureByPurpose == null ? Map.of()
                : Map.copyOf(temperatureByPurpose);
        schemaConstrainedPurposes = schemaConstrainedPurposes == null ? Set.of()
                : Set.copyOf(schemaConstrainedPurposes);
    }
```

조회 메서드 3개를 추가한다:

```java
    public double temperatureFor(Purpose purpose) {
        return temperatureByPurpose.getOrDefault(purpose.name(), temperature);
    }

    public boolean schemaConstrained(Purpose purpose) {
        return schemaConstrainedPurposes.contains(purpose.name());
    }

    public boolean hasSeed() {
        return seed != null;
    }
```

세 팩토리의 인자 목록 끝에 `, null, Map.of(), Set.of()` 를 붙인다:

```java
    public static LlmProperties disabled() {
        return new LlmProperties(LlmProvider.DISABLED, null, null, null, Map.of(),
                0.2, 4096, 60_000L, 3, false, 2,
                null, Map.of(), Set.of());
    }

    public static LlmProperties anthropic(String apiKey, String baseUrl, String model) {
        return new LlmProperties(LlmProvider.ANTHROPIC, apiKey, baseUrl, model, Map.of(),
                0.2, 1024, 5_000L, 2, false, 2,
                null, Map.of(), Set.of());
    }

    public static LlmProperties openAiCompatible(String baseUrl, String model) {
        return new LlmProperties(LlmProvider.OPENAI_COMPATIBLE, null, baseUrl, model, Map.of(),
                0.2, 1024, 30_000L, 2, true, 3,
                null, Map.of(), Set.of());
    }
```

`import java.util.Set;` 를 추가한다.

- [ ] **Step 5: `HttpLlmGateway` 가 목적과 스키마를 전달하게 한다**

추상 메서드 시그니처를 교체:

```java
    /**
     * 요청 본문. system 이 별도 필드인 공급자와 messages 에 섞는 공급자가 갈린다.
     *
     * @param jsonSchema 스키마 제약 디코딩에 쓸 JSON 스키마 문자열. 이 목적이 스키마
     *                   제약 대상이 아니거나 자유 텍스트 호출이면 null.
     */
    protected abstract Map<String, Object> requestBody(String system, String user,
                                                       String model, boolean jsonOnly,
                                                       Purpose purpose, String jsonSchema);
```

`text(...)` 와 `complete(...)`, `call(...)` 을 다음으로 교체:

```java
    @Override
    public String text(String system, String user, Purpose purpose) {
        return call(system, user, purpose, false, null).text();
    }

    @Override
    public <T> T complete(String system, String user, Class<T> type, Purpose purpose) {
        String schema = JsonSchemaGenerator.of(type);

        // 스키마 제약을 쓰더라도 프롬프트 주입을 유지한다. json_schema 가 거부되어
        // json_object 로 내려앉았을 때 이 안내가 유일한 형식 지시가 된다.
        String prompt = user
                + "\n\n아래 JSON 스키마를 만족하는 JSON 객체 하나만 출력하세요."
                + "\n설명, 코드펜스, 스키마 자체를 출력하지 마세요."
                + "\n\n스키마:\n" + schema
                + "\n\n정확히 이 형태로, 값만 채워 답하세요:\n" + JsonSchemaGenerator.exampleOf(type);

        String constrainedSchema = props.schemaConstrained(purpose) ? schema : null;

        String raw = null;
        int attempts = Math.max(1, props.maxParseAttempts());
        for (int attempt = 0; attempt < attempts; attempt++) {
            raw = call(system, prompt, purpose, true, constrainedSchema).text();
            try {
                return mapper.readValue(unwrapSchemaEcho(stripFence(raw)), type);
            } catch (JsonProcessingException e) {
                if (attempt == attempts - 1) {
                    throw new LlmParseException(raw, e);
                }
                prompt = prompt + "\n\n이전 응답이 파싱에 실패했습니다: " + e.getOriginalMessage()
                        + "\n스키마를 다시 확인하고 JSON 객체만 출력하세요.";
            }
        }
        throw new LlmParseException(raw, new IllegalStateException("unreachable"));
    }

    protected Completion call(String system, String user, Purpose purpose, boolean jsonOnly,
                              String jsonSchema) {
        if (!available()) {
            throw new LlmUnavailableException(unavailableReason());
        }
        String model = props.modelFor(purpose);
        Map<String, Object> body = requestBody(system, user, model, jsonOnly, purpose, jsonSchema);

        long startNs = System.nanoTime();
        try {
            return send(body, purpose, model, startNs);
        } catch (WebClientResponseException e) {
            // json_schema 를 거부하는 공급자·구버전을 위한 안전장치.
            // 4xx 만 대상으로 한다 — 5xx 는 스키마와 무관하고 재시도가 이미 처리한다.
            if (jsonSchema != null && e.getStatusCode().is4xxClientError()) {
                audit(purpose, model, body, null, startNs,
                        "json_schema 거부(" + e.getStatusCode().value() + "), json_object 로 재시도");
                Map<String, Object> fallback =
                        requestBody(system, user, model, jsonOnly, purpose, null);
                return send(fallback, purpose, model, System.nanoTime());
            }
            audit(purpose, model, body, null, startNs, e.toString());
            throw new LlmUnavailableException("LLM 호출에 실패했습니다: " + e.getMessage(), e);
        }
    }

    private Completion send(Map<String, Object> body, Purpose purpose, String model,
                            long startNs) {
        try {
            String rawJson = client.post().uri(endpoint())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(props.timeoutMs()))
                    .retryWhen(Retry.backoff(props.maxRetries(), Duration.ofSeconds(1))
                            .filter(HttpLlmGateway::isRetryable))
                    .block();

            Completion completion = parseResponse(rawJson == null ? "" : rawJson);
            audit(purpose, model, body, completion, startNs, null);
            return completion;
        } catch (JsonProcessingException e) {
            audit(purpose, model, body, null, startNs, "응답 봉투를 해석하지 못했습니다: " + e);
            throw new LlmUnavailableException(
                    "LLM 응답 형식이 예상과 다릅니다: " + e.getOriginalMessage(), e);
        } catch (WebClientResponseException e) {
            throw e;
        } catch (RuntimeException e) {
            audit(purpose, model, body, null, startNs, e.toString());
            if (e instanceof LlmUnavailableException) {
                throw e;
            }
            throw new LlmUnavailableException("LLM 호출에 실패했습니다: " + e.getMessage(), e);
        }
    }
```

`WebClientResponseException` 은 `Retry` 가 소진된 뒤 그대로 올라온다. `send` 가 그것만
다시 던지고 나머지는 `LlmUnavailableException` 으로 감싸므로, `call` 의 catch 가
스키마 거부와 그 외를 구분할 수 있다.

- [ ] **Step 6: `OpenAiCompatibleLlmGateway` 가 세 필드를 채우게 한다**

`requestBody` 를 교체:

```java
    @Override
    protected Map<String, Object> requestBody(String system, String user, String model,
                                              boolean jsonOnly, Purpose purpose,
                                              String jsonSchema) {
        List<Map<String, String>> messages = new ArrayList<>(2);
        if (system != null && !system.isBlank()) {
            messages.add(Map.of("role", "system", "content", system));
        }
        messages.add(Map.of("role", "user", "content", user));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", props.temperatureFor(purpose));
        body.put("max_tokens", props.maxTokens());
        // 스트리밍을 끄지 않으면 SSE 가 흘러와 봉투 파싱이 깨진다.
        body.put("stream", false);
        if (props.hasSeed()) {
            body.put("seed", props.seed());
        }
        if (jsonOnly && jsonSchema != null) {
            body.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", "structured_output",
                            "strict", true,
                            "schema", schemaObject(jsonSchema))));
        } else if (jsonOnly && props.jsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return body;
    }

    /**
     * 스키마 문자열을 객체로 되돌리고 additionalProperties: false 를 강제한다.
     *
     * <p>strict 모드는 최상위에 additionalProperties: false 를 요구한다.
     * JsonSchemaGenerator 는 그 키를 내지 않으므로 여기서 붙인다.
     */
    private Object schemaObject(String jsonSchema) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(jsonSchema);
            node.put("additionalProperties", false);
            return node;
        } catch (JsonProcessingException | ClassCastException e) {
            // 스키마를 객체로 못 만들면 제약을 포기하고 JSON 모드로 간다.
            return null;
        }
    }
```

`schemaObject` 가 null 을 돌려주면 `response_format.json_schema.schema` 가 null 이 되어
공급자가 4xx 를 낼 것이고, Step 5의 폴백이 `json_object` 로 되돌린다. 별도 분기가 필요 없다.

- [ ] **Step 7: `AnthropicLlmGateway` 시그니처를 맞춘다**

`requestBody` 의 파라미터만 늘리고 본문은 그대로 둔다:

```java
    @Override
    protected Map<String, Object> requestBody(String system, String user, String model,
                                              boolean jsonOnly, Purpose purpose,
                                              String jsonSchema) {
```

본문에서 `props.temperature()` 를 쓰고 있으면 `props.temperatureFor(purpose)` 로 바꾼다.
`jsonSchema` 는 무시한다 — Anthropic 경로는 이 승격 범위가 아니다.

- [ ] **Step 8: 기존 테스트의 생성자 호출 2곳을 고친다**

`OpenAiCompatibleLlmGatewayTest.java:78` 과 `:125`, `OllamaLiveTest.java:58` 의
`new LlmProperties(...)` 인자 끝에 `, null, java.util.Map.of(), java.util.Set.of()` 를 붙인다.

- [ ] **Step 9: 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :modules:llm:test`
Expected: 기존 테스트 전부 + 새 테스트 5개 PASS

`omitsSeedWhenUnset` 이 실패하면 `openAiCompatible(...)` 팩토리가 `seed` 를 null 로 두는지
확인한다. 기본값이 42 로 들어가면 기존 동작이 조용히 바뀐다.

- [ ] **Step 10: Spring 설정을 배선한다**

`LlmConfiguration.llmProperties(...)` 에 `@Value` 3개를 추가하고 생성자 호출을 고친다:

```java
            @Value("${llm.max-parse-attempts:2}") int maxParseAttempts,
            @Value("${llm.seed:}") String seed,
            @Value("${llm.temperature-by-purpose.STRUCTURE_DRAFT:}") String draftTemperature,
            @Value("${llm.schema-constrained-purposes:}") String schemaConstrained) {

        Map<String, String> byPurpose = new LinkedHashMap<>();
        putIfSet(byPurpose, "ROUTING", routingModel);
        putIfSet(byPurpose, "EXTRACTION", extractionModel);

        Map<String, Double> temperatures = new LinkedHashMap<>();
        if (draftTemperature != null && !draftTemperature.isBlank()) {
            temperatures.put("STRUCTURE_DRAFT", Double.parseDouble(draftTemperature.trim()));
        }

        java.util.Set<String> constrained = schemaConstrained == null
                || schemaConstrained.isBlank()
                ? java.util.Set.of()
                : java.util.Arrays.stream(schemaConstrained.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());

        return new LlmProperties(LlmProvider.parse(provider), emptyToNull(apiKey),
                emptyToNull(baseUrl), emptyToNull(model), byPurpose,
                temperature, maxTokens, timeoutMs, maxRetries, jsonMode, maxParseAttempts,
                seed == null || seed.isBlank() ? null : Long.parseLong(seed.trim()),
                temperatures, constrained);
    }
```

`seed` 를 `Long` 이 아니라 `String` 으로 받는 이유는 `@Value` 가 빈 문자열을 `Long` 으로
바꾸지 못해 부팅이 실패하기 때문이다. `llm.seed` 를 설정하지 않은 기존 배포가 그대로
떠야 한다.

`application.yml` 의 `llm:` 블록 끝에 주석과 함께 추가:

```yaml
  # 시드는 기본적으로 보내지 않는다. Anthropic 경로는 지원하지 않고,
  # 오픈 모델 경로에서만 의미가 있다 (application-ollama.yml 참고).
  seed: ${LLM_SEED:}
  schema-constrained-purposes: ${LLM_SCHEMA_PURPOSES:}
```

`application-ollama.yml` 의 `llm:` 블록에 추가:

```yaml
  # 로컬 Ollama 0.33.3 의 OpenAI 호환 경로가 seed 와 json_schema 를 모두 받는 것을
  # 2026-09-09 에 실측으로 확인했다. json_object 만으로는 같은 프롬프트에서 스키마를
  # 어겼다 — nodes 배열 대신 키 3개짜리 객체가 왔다.
  seed: 42
  temperature-by-purpose:
    STRUCTURE_DRAFT: 0.0
  schema-constrained-purposes: STRUCTURE_DRAFT
  model-by-purpose:
    STRUCTURE_DRAFT: ${OLLAMA_DRAFT_MODEL:qwen2.5:7b}
```

`model-by-purpose` 에 `STRUCTURE_DRAFT` 를 넣으려면 `LlmConfiguration` 에
`@Value("${llm.model-by-purpose.STRUCTURE_DRAFT:}") String draftModel` 을 추가하고
`putIfSet(byPurpose, "STRUCTURE_DRAFT", draftModel)` 을 호출한다. 넣지 않으면
기본 모델로 떨어지므로 필수는 아니지만, 초안 생성만 큰 모델로 바꾸고 싶을 때가 온다.

- [ ] **Step 11: 앱이 뜨는지 확인한다**

Run: `./gradlew :app:test`
Expected: 기존 앱 테스트 PASS (컨텍스트 로딩이 새 `@Value` 로 깨지지 않음)

- [ ] **Step 12: 커밋**

```bash
git add modules/llm app/src/main/java/org/hanbat/ses/app/config/LlmConfiguration.java app/src/main/resources
git commit -m "LLM 게이트웨이에 시드와 스키마 제약 디코딩 추가

Purpose.STRUCTURE_DRAFT 를 추가하고 Purpose javadoc 의 원칙 문장을 고친다.
LLM 은 계약 안에서 구조를 제안할 수 있고 확정은 결정론적 검증기가 한다.

LlmProperties 에 seed, temperatureByPurpose, schemaConstrainedPurposes 를
추가한다. 전부 가산적이고 기본값은 현재 동작을 유지한다. 생성자 호출
7곳을 함께 고쳤다.

로컬 Ollama 0.33.3 실측으로 OpenAI 호환 경로가 json_schema 와 seed 를
모두 지원하는 것을 확인했다. 네이티브 게이트웨이를 새로 만들지 않는다.
json_schema 를 거부하는 공급자를 위해 4xx 시 json_object 폴백을 둔다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: `StructureDraftGenerator` 와 `DesignService` — 7단계 오케스트레이션

**Files:**
- Create: `modules/design/src/main/java/org/hanbat/ses/design/draft/StructureDraftGenerator.java`
- Create: `modules/design/src/main/java/org/hanbat/ses/design/DesignStage.java`
- Create: `modules/design/src/main/java/org/hanbat/ses/design/DesignFailedException.java`
- Create: `modules/design/src/main/java/org/hanbat/ses/design/DesignResult.java`
- Create: `modules/mcp/src/main/java/org/hanbat/ses/mcp/McpClientFactory.java`
- Create: `modules/design/src/main/java/org/hanbat/ses/design/DesignService.java`
- Test: `modules/design/src/test/java/org/hanbat/ses/design/DesignServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `McpClient`·`McpServerConfig`·`McpToolException`·`McpUnavailableException`,
  Task 2 `TopologyContractRegistry`, Task 3 `ModelContract`·`McpModelContractValidator`,
  Task 4 `SesCompiler`·`CompiledDesign`, Task 5 `DraftValidator`·`DraftPromptBuilder`,
  Task 6 `Purpose.STRUCTURE_DRAFT`,
  기존 `LlmGateway`, `SesRegistry`, `TemplateRegistry`, `ModelBaseRegistry`,
  `SesAxiomValidator`, `SesStructureChecker`, `TemplateConsistencyChecker`
- Produces: `StructureDraftGenerator.generate(String request, TopologyContract) -> StructureDraft`,
  `DesignStage` (enum: `DRAFT`, `DRAFT_VALIDATION`, `MODEL_LOOKUP`, `MODEL_CONTRACT`, `COMPILE`, `REGISTER`),
  `DesignFailedException(DesignStage, String, List<ValidationIssue>)` + `stage()` + `issues()`,
  `DesignResult(CompiledDesign design, StructureDraft draft, String contractId, String llmModel, double temperature, Long seed, boolean schemaConstrained)`,
  `McpClientFactory.open(String serverName) -> McpClient`,
  `DesignService.design(String request, String contractId) -> DesignResult`

**동시 실행 제한이 여기 있다.** `McpClientFactory` 가 세마포어를 들고 `open()` 에서 획득하고
`McpClient.close()` 시 반납한다. 제한이 없으면 `provision_model` 이 Docker 빌드를 병렬로
여러 번 실행한다 (스펙 §4.3).

- [ ] **Step 1: 단계와 실패 타입을 만든다**

`DesignStage.java`:

```java
package org.hanbat.ses.design;

/** 설계 단계. HTTP 상태 매핑에 쓴다 (스펙 §5.1). */
public enum DesignStage {
    /** LLM 초안 생성 — 503. 폴백 초안을 만들지 않는다. */
    DRAFT,
    /** 결정론적 초안 검증 — 422. supported=false 도 여기. */
    DRAFT_VALIDATION,
    /** MCP search_models / describe_model — 502. */
    MODEL_LOOKUP,
    /** 모델 계약 구조 검증 — 422. */
    MODEL_CONTRACT,
    /** SES/템플릿 컴파일 — 500. 검증을 통과한 입력에서 나오면 컴파일러 결함이다. */
    COMPILE,
    /** 레지스트리 등록과 등록 검증 — 422, 키 충돌은 409. */
    REGISTER
}
```

`DesignFailedException.java`:

```java
package org.hanbat.ses.design;

import java.util.List;

import org.hanbat.ses.core.validate.ValidationIssue;

/** 설계 단계 실패. 어느 단계인지가 HTTP 상태를 정한다. */
public class DesignFailedException extends RuntimeException {

    private final DesignStage stage;
    private final List<ValidationIssue> issues;

    public DesignFailedException(DesignStage stage, String message,
                                 List<ValidationIssue> issues) {
        super(message);
        this.stage = stage;
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public DesignFailedException(DesignStage stage, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
        this.issues = List.of();
    }

    public DesignStage stage() {
        return stage;
    }

    public List<ValidationIssue> issues() {
        return issues;
    }
}
```

`DesignResult.java`:

```java
package org.hanbat.ses.design;

import org.hanbat.ses.design.compile.CompiledDesign;
import org.hanbat.ses.design.draft.StructureDraft;

/**
 * 설계 산출물과 그 출처.
 *
 * <p>출처를 함께 내는 이유는 연구용이라서다. 어떤 모델이 어떤 온도·시드로 어떤 구조를
 * 제안했는지 기록되지 않으면 결과를 재현할 수 없다 (스펙 §5.2).
 */
public record DesignResult(CompiledDesign design, StructureDraft draft, String contractId,
                           String llmModel, double temperature, Long seed,
                           boolean schemaConstrained) {
}
```

- [ ] **Step 2: `StructureDraftGenerator` 를 만든다**

```java
package org.hanbat.ses.design.draft;

import org.hanbat.ses.design.DesignFailedException;
import org.hanbat.ses.design.DesignStage;
import org.hanbat.ses.template.topology.TopologyContract;
import org.hanbat.ses.llm.gateway.LlmGateway;
import org.hanbat.ses.llm.gateway.LlmParseException;
import org.hanbat.ses.llm.gateway.LlmUnavailableException;
import org.hanbat.ses.llm.gateway.Purpose;
import org.springframework.stereotype.Component;

/**
 * LLM 에게 구조 초안을 받는다.
 *
 * <p><b>폴백이 없다.</b> LLM 이 없거나 실패하면 요청 전체가 실패한다. 다른 목적
 * (ROUTING, EXTRACTION)은 키워드 매칭과 전량 질문으로 떨어지지만, 여기서 가짜 초안을
 * 만들면 사용자가 요청하지 않은 구조로 시뮬레이션이 돈다. experiment.py 도 같은
 * 이유로 "로컬 LLM 실패 시 가짜 초안으로 대체하지 않고 실패한다"고 못 박았다.
 */
@Component
public class StructureDraftGenerator {

    private final LlmGateway llm;
    private final DraftPromptBuilder prompts;

    public StructureDraftGenerator(LlmGateway llm, DraftPromptBuilder prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    public StructureDraft generate(String request, TopologyContract contract) {
        if (!llm.available()) {
            throw new DesignFailedException(DesignStage.DRAFT,
                    "구조 초안을 만들려면 LLM 이 필요합니다. llm.provider 설정을 확인하세요.",
                    java.util.List.of());
        }
        try {
            return llm.complete(prompts.systemPrompt(contract), request,
                    StructureDraft.class, Purpose.STRUCTURE_DRAFT);
        } catch (LlmUnavailableException | LlmParseException e) {
            throw new DesignFailedException(DesignStage.DRAFT,
                    "구조 초안 생성에 실패했습니다: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 3: `modules:mcp` 에 `McpClientFactory` 를 만든다**

`modules:design`(Task 7)과 `modules:scenario`(Task 8)가 둘 다 이것을 쓴다. `design` 에 두면
`scenario` 가 `design` 을 의존해야 하고 `scenario` 가 `llm` 을 간접 의존하게 된다.
세마포어와 프로세스 수명은 어차피 `mcp` 의 관심사다.

```java
package org.hanbat.ses.mcp;

import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * 요청 스코프 MCP 클라이언트를 연다.
 *
 * <p>프로세스를 풀링하지 않는 이유는 run_processor 가 Docker 컨테이너를 띄우기 때문이다 —
 * 프로세스 상태를 공유해서 얻을 것이 없고, 풀은 좌초된 컨테이너 정리 책임을 서버에
 * 넘긴다 (스펙 §4.3).
 *
 * <p>동시 실행은 세마포어로 제한한다. 제한이 없으면 provision_model 이 Docker 빌드를
 * 병렬로 여러 번 실행한다.
 */
public class McpClientFactory {

    private final Map<String, McpServerConfig> servers;
    private final McpExchangeLog log;
    private final Semaphore permits;

    public McpClientFactory(Map<String, McpServerConfig> servers, McpExchangeLog log,
                            int maxConcurrent) {
        this.servers = Map.copyOf(servers);
        this.log = log;
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
    }

    public McpServerConfig config(String serverName) {
        McpServerConfig config = servers.get(serverName);
        if (config == null) {
            throw new IllegalArgumentException(
                    "설정되지 않은 MCP 서버입니다: " + serverName + " (설정된 것: " + servers.keySet() + ")");
        }
        return config;
    }

    /**
     * 클라이언트를 연다. 닫을 때 세마포어를 반납하므로 반드시 try-with-resources 로 쓴다.
     */
    public McpClient open(String serverName) {
        McpServerConfig config = config(serverName);
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpUnavailableException(
                    "MCP 실행 슬롯을 기다리는 중 중단되었습니다.", e);
        }
        // 프로세스 생성이 실패하면 ReleasingTransport 가 아직 없으므로 직접 반납한다.
        McpTransport transport;
        try {
            transport = new ReleasingTransport(
                    new ProcessMcpTransport(config.command()), permits);
        } catch (RuntimeException e) {
            permits.release();
            throw e;
        }
        // 여기서부터는 반납 책임이 transport 에 있다. handshake 가 실패하면
        // McpClient.handshake 가 transport.close() 를 부르므로 그때 반납된다.
        return McpClient.handshake(config, transport, log);
    }

    /** close() 한 번에 정확히 한 번 반납한다. */
    private static final class ReleasingTransport implements McpTransport {

        private final McpTransport delegate;
        private final Semaphore permits;
        private boolean released;

        ReleasingTransport(McpTransport delegate, Semaphore permits) {
            this.delegate = delegate;
            this.permits = permits;
        }

        @Override
        public void send(String jsonLine) {
            delegate.send(jsonLine);
        }

        @Override
        public String receive(long timeoutMs) {
            return delegate.receive(timeoutMs);
        }

        @Override
        public void close() {
            try {
                delegate.close();
            } finally {
                if (!released) {
                    released = true;
                    permits.release();
                }
            }
        }
    }
}
```

반납 책임이 `ReleasingTransport` 로 넘어가는 지점이 명확해야 한다. 프로세스 생성 전이면
`open()` 이 반납하고, 그 후에는 `close()` 가 정확히 한 번 반납한다(`released` 플래그).
반납이 어긋나면 두 번째 요청이 영구히 잠기므로 다음 테스트를 함께 넣는다.

```java
    @Test
    @DisplayName("프로세스 생성이 실패해도 세마포어를 반납한다")
    void releasesPermitOnStartFailure() {
        McpClientFactory factory = new McpClientFactory(
                java.util.Map.of("queue-models", new McpServerConfig("queue-models",
                        java.util.List.of("이런-실행파일은-없다"),
                        java.util.List.of("describe_model"), McpTimeouts.defaults())),
                McpExchangeLog.noop(), 1);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> factory.open("queue-models"))
                    .isInstanceOf(McpUnavailableException.class);
        }
        // 반납이 새면 두 번째 호출이 영구히 잠겨 이 테스트가 타임아웃된다.
    }
```

이 테스트는 `modules/mcp/src/test/java/org/hanbat/ses/mcp/McpClientFactoryTest.java` 에 둔다.

- [ ] **Step 4: 실패하는 테스트를 쓴다**

`DesignServiceTest.java`:

```java
package org.hanbat.ses.design;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.design.compile.SesCompiler;
import org.hanbat.ses.design.contract.McpModelContractValidator;
import org.hanbat.ses.design.draft.DraftConnection;
import org.hanbat.ses.design.draft.DraftNode;
import org.hanbat.ses.design.draft.DraftValidator;
import org.hanbat.ses.design.draft.StructureDraft;
import org.hanbat.ses.design.draft.StructureDraftGenerator;
import org.hanbat.ses.template.topology.TopologyContract;
import org.hanbat.ses.template.topology.TopologyContractRegistry;
import org.hanbat.ses.template.model.SlotSpec;
import org.hanbat.ses.template.model.SubtaskTemplate;
import org.hanbat.ses.template.registry.InMemoryRegistries;
import org.hanbat.ses.template.registry.ModelBaseEntry;
import org.hanbat.ses.template.registry.ModelBaseRegistry;
import org.hanbat.ses.template.registry.SesDefinition;
import org.hanbat.ses.template.registry.SesRegistry;
import org.hanbat.ses.template.registry.TemplateRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DesignServiceTest {

    private TopologyContract topology;
    private SesRegistry sesRegistry;
    private TemplateRegistry templateRegistry;
    private ModelBaseRegistry modelBases;

    /** 초안 생성만 대체한다. 나머지 단계는 실제 구현을 그대로 쓴다. */
    private static final class FixedDraftGenerator extends StructureDraftGenerator {
        private final StructureDraft draft;
        private final RuntimeException failure;

        FixedDraftGenerator(StructureDraft draft, RuntimeException failure) {
            super(null, null);
            this.draft = draft;
            this.failure = failure;
        }

        @Override
        public StructureDraft generate(String request, TopologyContract contract) {
            if (failure != null) {
                throw failure;
            }
            return draft;
        }
    }

    /** MCP 조회만 대체한다. */
    private static final class StubLookup implements ModelLookup {
        private final Map<String, Object> catalog;
        private final RuntimeException failure;

        StubLookup(Map<String, Object> catalog, RuntimeException failure) {
            this.catalog = catalog;
            this.failure = failure;
        }

        @Override
        public Map<String, Object> describe(String capability) {
            if (failure != null) {
                throw failure;
            }
            return catalog;
        }
    }

    private static Map<String, Object> catalog() {
        return Map.of(
                "modelId", "external-simpy-fifo",
                "version", "1.0.0",
                "capability", "fifo-single-server",
                "timeUnit", "분",
                "ports", Map.of(
                        "in", Map.of("name", "in", "dataType", "job", "unit", "건"),
                        "out", Map.of("name", "out", "dataType", "job", "unit", "건")),
                "parameters", List.of(Map.of(
                        "name", "serviceTime", "type", "DOUBLE", "unit", "분",
                        "required", true,
                        "range", Map.of("min", 0.01, "max", 10),
                        "question", "작업 한 건의 처리시간은 몇 분인가요?")),
                "limits", Map.of("maxJobs", 100, "servers", 1,
                        "discipline", "FIFO", "termination", "drain-all-jobs"),
                "runtime", Map.of("kind", "docker", "image", "ses-queue-processor:1.0.0"));
    }

    private static StructureDraft draft() {
        return new StructureDraft("단일 FIFO 대기행렬", true, null,
                List.of(new DraftNode("gen-1", "작업생성", "SOURCE"),
                        new DraftNode("proc-1", "처리기", "PROCESSOR"),
                        new DraftNode("agg-1", "집계", "SINK")),
                List.of(new DraftConnection("gen-1", "proc-1"),
                        new DraftConnection("proc-1", "agg-1")),
                List.of("단일 서버 / FIFO / 무한 대기실"));
    }

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/queue.topology.json")) {
            topology = TopologyContractRegistry.load(in);
        }
        // InMemoryRegistries 는 접근자가 없는 네임스페이스 클래스다 — 중첩 구현을 직접 만든다.
        sesRegistry = new InMemoryRegistries.SesDefinitions();
        templateRegistry = new InMemoryRegistries.Templates();
        modelBases = new InMemoryRegistries.ModelBases();

        modelBases.register(new ModelBaseEntry("queue-local-source", "ATOMIC", "작업 생성기",
                Map.of("in", List.of(), "out", List.of("out")),
                Map.of("emitted", "int"),
                Map.of("arrivalInterval", Map.of("type", "DOUBLE", "unit", "분",
                                "required", true, "range", Map.of("min", 0.01, "max", 10),
                                "question", "작업이 몇 분 간격으로 도착하나요?"),
                        "totalJobs", Map.of("type", "INT", "unit", "건",
                                "required", true, "range", Map.of("min", 1, "max", 100),
                                "question", "총 몇 건의 작업을 생성할까요?"))));
        modelBases.register(new ModelBaseEntry("queue-local-sink", "ATOMIC", "작업 집계기",
                Map.of("in", List.of("in"), "out", List.of()), Map.of(), Map.of()));
    }

    private DesignService service(StructureDraft draft, RuntimeException draftFailure,
                                  Map<String, Object> catalog, RuntimeException lookupFailure) {
        return new DesignService(
                new TopologyContractRegistry(List.of(topology)),
                new FixedDraftGenerator(draft, draftFailure),
                new DraftValidator(),
                new StubLookup(catalog, lookupFailure),
                new McpModelContractValidator(),
                new SesCompiler(),
                sesRegistry, templateRegistry, modelBases);
    }

    @Test
    @DisplayName("7단계를 통과해 SES 와 템플릿을 등록한다")
    void happyPath() {
        DesignResult result = service(draft(), null, catalog(), null)
                .design("대기행렬을 만들고 평균 대기시간을 보고 싶어", "fifo-single-server");

        String id = result.design().template().id();
        assertThat(sesRegistry.find(id)).isPresent();
        assertThat(templateRegistry.findActive(id)).isPresent();

        SubtaskTemplate registered = templateRegistry.findActive(id).orElseThrow();
        assertThat(registered.slots()).extracting(SlotSpec::name)
                .containsExactly("arrivalInterval", "totalJobs", "serviceTime");
        assertThat(registered.execution().simulator().engine()).isEqualTo("queue-mcp-batch");

        SesDefinition ses = sesRegistry.find(id).orElseThrow();
        assertThat(ses.tree()).isInstanceOf(SesNode.class);
        assertThat(result.draft().title()).isEqualTo("단일 FIFO 대기행렬");
        assertThat(result.design().contracts())
                .extracting(c -> c.role() + ":" + c.contract().modelId())
                .containsExactly("SOURCE:queue-local-source",
                        "PROCESSOR:external-simpy-fifo", "SINK:queue-local-sink");
    }

    @Test
    @DisplayName("LLM 실패는 DRAFT 단계 실패다")
    void draftFailure() {
        RuntimeException boom = new DesignFailedException(DesignStage.DRAFT,
                "LLM 이 응답하지 않습니다.", List.of());

        assertThatThrownBy(() -> service(null, boom, catalog(), null)
                .design("요청", "fifo-single-server"))
                .isInstanceOf(DesignFailedException.class)
                .extracting(e -> ((DesignFailedException) e).stage())
                .isEqualTo(DesignStage.DRAFT);
    }

    @Test
    @DisplayName("supported=false 는 DRAFT_VALIDATION 단계 실패이고 사유를 담는다")
    void unsupportedRequest() {
        StructureDraft unsupported = new StructureDraft(null, false,
                "다중 서버는 지원하지 않습니다", List.of(), List.of(), List.of());

        assertThatThrownBy(() -> service(unsupported, null, catalog(), null)
                .design("서버 3대로 만들어줘", "fifo-single-server"))
                .isInstanceOf(DesignFailedException.class)
                .satisfies(e -> {
                    DesignFailedException d = (DesignFailedException) e;
                    assertThat(d.stage()).isEqualTo(DesignStage.DRAFT_VALIDATION);
                    assertThat(d.issues()).extracting(i -> i.code())
                            .contains("DRAFT_UNSUPPORTED");
                });
    }

    @Test
    @DisplayName("계약을 벗어난 초안은 DRAFT_VALIDATION 단계에서 막고 MCP 를 부르지 않는다")
    void invalidDraftStopsBeforeLookup() {
        StructureDraft bad = new StructureDraft("나쁜 초안", true, null,
                List.of(new DraftNode("a", "가", "SOURCE"),
                        new DraftNode("b", "나", "SOURCE"),
                        new DraftNode("c", "다", "SINK")),
                List.of(new DraftConnection("a", "b"), new DraftConnection("b", "c")),
                List.of());
        RuntimeException lookupMustNotRun = new IllegalStateException("조회가 불렸다");

        assertThatThrownBy(() -> service(bad, null, null, lookupMustNotRun)
                .design("요청", "fifo-single-server"))
                .isInstanceOf(DesignFailedException.class)
                .extracting(e -> ((DesignFailedException) e).stage())
                .isEqualTo(DesignStage.DRAFT_VALIDATION);
    }

    @Test
    @DisplayName("MCP 조회 실패는 MODEL_LOOKUP 단계 실패다")
    void lookupFailure() {
        RuntimeException boom = new org.hanbat.ses.mcp.McpUnavailableException(
                "MCP 서버 프로세스를 시작하지 못했습니다.");

        assertThatThrownBy(() -> service(draft(), null, null, boom)
                .design("요청", "fifo-single-server"))
                .isInstanceOf(DesignFailedException.class)
                .extracting(e -> ((DesignFailedException) e).stage())
                .isEqualTo(DesignStage.MODEL_LOOKUP);
    }

    @Test
    @DisplayName("계약 구조가 깨지면 MODEL_CONTRACT 단계 실패다")
    void contractFailure() {
        Map<String, Object> broken = new java.util.HashMap<>(catalog());
        broken.remove("runtime");

        assertThatThrownBy(() -> service(draft(), null, broken, null)
                .design("요청", "fifo-single-server"))
                .isInstanceOf(DesignFailedException.class)
                .satisfies(e -> {
                    DesignFailedException d = (DesignFailedException) e;
                    assertThat(d.stage()).isEqualTo(DesignStage.MODEL_CONTRACT);
                    assertThat(d.issues()).extracting(i -> i.code())
                            .contains("CONTRACT_INCOMPLETE");
                });
    }

    @Test
    @DisplayName("로컬 모델이 model_base 에 없으면 MODEL_LOOKUP 단계 실패다")
    void missingLocalModel() {
        DesignService svc = new DesignService(
                new TopologyContractRegistry(List.of(topology)),
                new FixedDraftGenerator(draft(), null),
                new DraftValidator(),
                new StubLookup(catalog(), null),
                new McpModelContractValidator(),
                new SesCompiler(),
                new InMemoryRegistries.SesDefinitions(),
                new InMemoryRegistries.Templates(),
                new InMemoryRegistries.ModelBases());

        assertThatThrownBy(() -> svc.design("요청", "fifo-single-server"))
                .isInstanceOf(DesignFailedException.class)
                .satisfies(e -> {
                    DesignFailedException d = (DesignFailedException) e;
                    assertThat(d.stage()).isEqualTo(DesignStage.MODEL_LOOKUP);
                    assertThat(d.getMessage()).contains("queue-local-source");
                });
    }

    @Test
    @DisplayName("같은 요청을 두 번 하면 서로 다른 설계 id 로 등록된다")
    void twoRequestsTwoDesigns() {
        DesignService svc = service(draft(), null, catalog(), null);

        String first = svc.design("요청", "fifo-single-server").design().template().id();
        String second = svc.design("요청", "fifo-single-server").design().template().id();

        assertThat(first).isNotEqualTo(second);
        assertThat(sesRegistry.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("등록 검증을 통과한 SES 만 저장된다 - 구조 동형을 확인한다")
    void registeredSesIsStructurallyIdentical() {
        DesignService svc = service(draft(), null, catalog(), null);

        DesignResult a = svc.design("요청", "fifo-single-server");
        DesignResult b = svc.design("요청", "fifo-single-server");

        assertThat(fingerprint(a)).isEqualTo(fingerprint(b));
    }

    /** 노드 id 는 초안이 정하므로 같지만 설계 id 는 다르다. 구조만 비교한다. */
    private static String fingerprint(DesignResult result) {
        return result.design().template().slots().stream().map(SlotSpec::name).sorted()
                .toList()
                + "|" + result.design().contracts().stream()
                .map(c -> c.role() + ":" + c.contract().modelId()).sorted().toList();
    }
}
```

`StructureDraftGenerator` 를 상속해 `generate` 만 덮는다. `super(null, null)` 은 생성자
인자를 쓰지 않기 때문에 안전하다 — 덮은 메서드가 `llm` 과 `prompts` 를 건드리지 않는다.
`final` 클래스로 만들면 이 대체가 불가능하므로 `StructureDraftGenerator` 에 `final` 을
붙이지 않는다.

- [ ] **Step 5: 테스트가 실패하는 것을 확인한다**

Run: `./gradlew :modules:design:test --tests '*DesignServiceTest'`
Expected: 컴파일 실패 — `DesignService`, `ModelLookup` 심볼을 찾을 수 없음

- [ ] **Step 6: `ModelLookup` 과 MCP 구현을 만든다**

`modules/design/src/main/java/org/hanbat/ses/design/ModelLookup.java`:

```java
package org.hanbat.ses.design;

import java.util.Map;

/**
 * 외부 모델 계약 조회 경계.
 *
 * <p>인터페이스로 두는 이유는 테스트다. DesignService 의 단계별 실패 분기를 확인하려면
 * 조회가 성공·실패하는 상황을 만들 수 있어야 하고, 그때마다 MCP 서버를 띄울 수는 없다.
 */
public interface ModelLookup {

    /**
     * 능력으로 모델을 찾아 계약을 돌려준다.
     *
     * @throws org.hanbat.ses.mcp.McpUnavailableException 서버에 닿지 못했을 때
     * @throws org.hanbat.ses.mcp.McpToolException 서버가 거부했을 때
     */
    Map<String, Object> describe(String capability);
}
```

`modules/design/src/main/java/org/hanbat/ses/design/McpModelLookup.java`:

```java
package org.hanbat.ses.design;

import java.util.List;
import java.util.Map;

import org.hanbat.ses.mcp.McpClient;
import org.hanbat.ses.mcp.McpServerConfig;
import org.hanbat.ses.mcp.McpUnavailableException;

/** MCP search_models -> describe_model 두 번 호출로 계약을 가져온다. */
public class McpModelLookup implements ModelLookup {

    private static final String SERVER = "queue-models";

    private final McpClientFactory factory;

    public McpModelLookup(McpClientFactory factory) {
        this.factory = factory;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> describe(String capability) {
        McpServerConfig config = factory.config(SERVER);
        long timeout = config.timeouts().defaultMs();

        try (McpClient client = factory.open(SERVER)) {
            Map<String, Object> found = client.callTool("search_models",
                    Map.of("capability", capability), timeout);

            Object models = found.get("models");
            if (!(models instanceof List<?> list) || list.isEmpty()) {
                throw new McpUnavailableException(
                        "능력 " + capability + " 에 맞는 외부 모델이 없습니다.");
            }
            Object first = list.get(0);
            if (!(first instanceof Map<?, ?> m) || m.get("modelId") == null) {
                throw new McpUnavailableException("모델 검색 결과에 modelId 가 없습니다.");
            }
            String modelId = String.valueOf(((Map<String, Object>) m).get("modelId"));

            return client.callTool("describe_model", Map.of("modelId", modelId), timeout);
        }
    }
}
```

- [ ] **Step 7: `DesignService` 를 구현한다**

```java
package org.hanbat.ses.design;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.axiom.SesAxiomValidator;
import org.hanbat.ses.core.validate.SesStructureChecker;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.hanbat.ses.design.compile.CompileException;
import org.hanbat.ses.design.compile.CompiledDesign;
import org.hanbat.ses.design.compile.SesCompiler;
import org.hanbat.ses.design.contract.McpModelContractValidator;
import org.hanbat.ses.design.contract.ModelContract;
import org.hanbat.ses.design.draft.DraftValidator;
import org.hanbat.ses.design.draft.StructureDraft;
import org.hanbat.ses.design.draft.StructureDraftGenerator;
import org.hanbat.ses.template.topology.RoleSpec;
import org.hanbat.ses.template.topology.TopologyContract;
import org.hanbat.ses.template.topology.TopologyContractRegistry;
import org.hanbat.ses.mcp.McpToolException;
import org.hanbat.ses.mcp.McpUnavailableException;
import org.hanbat.ses.template.registry.ModelBaseEntry;
import org.hanbat.ses.template.registry.ModelBaseRegistry;
import org.hanbat.ses.template.registry.SesRegistry;
import org.hanbat.ses.template.registry.TemplateRegistry;
import org.hanbat.ses.template.validate.TemplateConsistencyChecker;
import org.springframework.stereotype.Service;

/**
 * 요청문 하나에서 등록된 SES/템플릿까지 — 스펙 §5.1의 1~6단계.
 *
 * <p>세션 생성(7단계)은 여기서 하지 않는다. dialogue 계층에 의존하게 되고, 그 둘을 아는
 * 유일한 곳은 api 모듈의 SessionFacade 라는 기존 규칙을 깨뜨린다.
 *
 * <p>6단계가 이 설계의 핵심이다. 컴파일 산출물을 기존 레지스트리에 등록하기 전에
 * SesAxiomValidator, SesStructureChecker, TemplateConsistencyChecker 를 돌린다.
 * 새 검증기를 만드는 대신 이미 있는 제약 28개를 LLM 산출물에 적용한다.
 */
@Service
public class DesignService {

    private final TopologyContractRegistry contracts;
    private final StructureDraftGenerator drafts;
    private final DraftValidator draftValidator;
    private final ModelLookup lookup;
    private final McpModelContractValidator contractValidator;
    private final SesCompiler compiler;
    private final SesRegistry sesRegistry;
    private final TemplateRegistry templateRegistry;
    private final ModelBaseRegistry modelBases;

    private final SesAxiomValidator axioms = new SesAxiomValidator();
    private final TemplateConsistencyChecker consistency = new TemplateConsistencyChecker();

    public DesignService(TopologyContractRegistry contracts, StructureDraftGenerator drafts,
                         DraftValidator draftValidator, ModelLookup lookup,
                         McpModelContractValidator contractValidator, SesCompiler compiler,
                         SesRegistry sesRegistry, TemplateRegistry templateRegistry,
                         ModelBaseRegistry modelBases) {
        this.contracts = contracts;
        this.drafts = drafts;
        this.draftValidator = draftValidator;
        this.lookup = lookup;
        this.contractValidator = contractValidator;
        this.compiler = compiler;
        this.sesRegistry = sesRegistry;
        this.templateRegistry = templateRegistry;
        this.modelBases = modelBases;
    }

    public DesignResult design(String request, String contractId) {
        TopologyContract topology = contracts.require(contractId);

        // 1단계 — LLM 초안. 폴백 없음.
        StructureDraft draft = drafts.generate(request, topology);

        // 2단계 — 결정론적 초안 검증. 통과하지 못하면 MCP 를 부르지 않는다.
        List<ValidationIssue> draftIssues = draftValidator.validate(draft, topology);
        failIfErrors(DesignStage.DRAFT_VALIDATION, "초안이 토폴로지 계약을 만족하지 않습니다.",
                draftIssues);

        // 3단계 — 모델 계약 조회 (외부는 MCP, 내부는 model_base).
        Map<String, ModelContract> byRole = resolveContracts(topology);

        // 4단계는 resolveContracts 안에서 외부 계약에만 적용된다.

        // 5단계 — 컴파일.
        String designId = SesCompiler.newDesignId();
        CompiledDesign compiled;
        try {
            compiled = compiler.compile(draft, topology, byRole, designId);
        } catch (CompileException e) {
            throw new DesignFailedException(DesignStage.COMPILE,
                    "설계를 컴파일하지 못했습니다: " + e.getMessage(), e);
        }

        // 6단계 — 등록 검증 후 등록.
        registerOrFail(compiled, topology);

        return new DesignResult(compiled, draft, topology.contractId(),
                null, 0.0, null, false);
    }

    // ------------------------------------------------------------ 3~4단계

    private Map<String, ModelContract> resolveContracts(TopologyContract topology) {
        Map<String, ModelContract> byRole = new LinkedHashMap<>();
        for (RoleSpec role : topology.roles()) {
            byRole.put(role.role(), role.external()
                    ? externalContract(role, topology)
                    : localContract(role, topology));
        }
        return byRole;
    }

    private ModelContract externalContract(RoleSpec role, TopologyContract topology) {
        Map<String, Object> catalog;
        try {
            catalog = lookup.describe(role.capability());
        } catch (McpUnavailableException | McpToolException e) {
            throw new DesignFailedException(DesignStage.MODEL_LOOKUP,
                    "외부 모델 조회에 실패했습니다: " + e.getMessage(), e);
        }
        List<ValidationIssue> issues =
                contractValidator.validate(catalog, role.capability(), topology);
        failIfErrors(DesignStage.MODEL_CONTRACT,
                "외부 모델 계약이 이 시스템에서 쓸 수 있는 형태가 아닙니다.", issues);

        return ModelContract.fromMcp(catalog);
    }

    private ModelContract localContract(RoleSpec role, TopologyContract topology) {
        ModelBaseEntry entry = modelBases.find(role.modelRef())
                .orElseThrow(() -> new DesignFailedException(DesignStage.MODEL_LOOKUP,
                        "모델 베이스에 " + role.modelRef() + " 가 없습니다. "
                                + "seed/queue.models.json 등록을 확인하세요.", List.of()));
        return ModelContract.fromModelBase(entry, topology.timeUnit());
    }

    // ------------------------------------------------------------ 6단계

    private void registerOrFail(CompiledDesign compiled, TopologyContract topology) {
        Map<String, String> aliases = compiled.template().validation().unitAliases();

        List<ValidationIssue> issues = new ArrayList<>(axioms.validate(compiled.ses().tree()));
        issues.addAll(new SesStructureChecker(aliases).check(compiled.ses().tree()));
        issues.addAll(consistency.check(compiled.template(), compiled.ses().tree()));

        failIfErrors(DesignStage.REGISTER,
                "컴파일한 설계가 등록 검증을 통과하지 못했습니다.", issues);

        if (sesRegistry.find(compiled.ses().id()).isPresent()) {
            throw new DesignFailedException(DesignStage.REGISTER,
                    "이미 등록된 설계 id 입니다: " + compiled.ses().id(), List.of());
        }
        sesRegistry.register(compiled.ses());
        templateRegistry.register(compiled.template());
    }

    private void failIfErrors(DesignStage stage, String message,
                              List<ValidationIssue> issues) {
        if (issues.stream().anyMatch(ValidationIssue::isError)) {
            throw new DesignFailedException(stage, message, issues);
        }
    }
}
```

`DesignResult` 의 출처 필드(`llmModel`, `temperature`, `seed`, `schemaConstrained`)를
여기서 null/0.0/false 로 두는 이유는 `DesignService` 가 `LlmProperties` 를 모르기 때문이다.
`contractId` 는 알고 있으므로 채운다.
Task 10의 `SessionFacade` 가 `LlmProperties` 를 주입받아 채운다 — `modules:design`이
설정 값을 들고 다니지 않게 한다.

- [ ] **Step 8: 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :modules:design:test`
Expected: `DesignServiceTest` 9개 + 앞선 53개 PASS

- [ ] **Step 9: 커밋**

```bash
git add modules/design
git commit -m "DesignService - 요청문에서 등록된 SES/템플릿까지 1~6단계

초안 생성, 초안 검증, 모델 계약 조회, 계약 검증, 컴파일, 등록 검증 후
등록. 각 단계 실패를 DesignStage 로 구분해 HTTP 상태 매핑에 쓴다.

등록 전에 SesAxiomValidator, SesStructureChecker,
TemplateConsistencyChecker 를 돌린다. 새 검증기를 만드는 대신 기존
제약 28개를 LLM 산출물에 적용한다.

세션 생성은 여기서 하지 않는다. dialogue 와 scenario 를 아는 유일한
곳은 api 모듈의 SessionFacade 라는 기존 규칙을 지킨다.

ModelLookup 을 인터페이스로 두어 조회 성공/실패 분기를 MCP 서버 없이
테스트한다. McpClientFactory 가 세마포어로 동시 실행을 1로 제한한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: `McpBatchScenarioExecutor` — 외부 모델 실행

**Files:**
- Modify: `modules/scenario/build.gradle.kts` (`api(project(":modules:mcp"))`)
- Create: `modules/scenario/src/main/java/org/hanbat/ses/scenario/exec/mcp/ArrivalEvent.java`
- Create: `modules/scenario/src/main/java/org/hanbat/ses/scenario/exec/mcp/DepartureEvent.java`
- Create: `modules/scenario/src/main/java/org/hanbat/ses/scenario/exec/mcp/SimulatorManifest.java`
- Create: `modules/scenario/src/main/java/org/hanbat/ses/scenario/exec/mcp/ManifestAssembler.java`
- Create: `modules/scenario/src/main/java/org/hanbat/ses/scenario/exec/mcp/QueueInvariants.java`
- Create: `modules/scenario/src/main/java/org/hanbat/ses/scenario/exec/mcp/McpBatchScenarioExecutor.java`
- Modify: `modules/scenario/src/main/java/org/hanbat/ses/scenario/output/ResultFormatter.java`
- Test: `modules/scenario/src/test/java/org/hanbat/ses/scenario/exec/mcp/ManifestAssemblerTest.java`
- Test: `modules/scenario/src/test/java/org/hanbat/ses/scenario/exec/mcp/QueueInvariantsTest.java`
- Test: `modules/scenario/src/test/java/org/hanbat/ses/scenario/exec/mcp/McpBatchScenarioExecutorTest.java`

**Interfaces:**
- Consumes: Task 1 `McpClient`, Task 2 `TopologyContract`·`TopologyContractRegistry`,
  Task 7 `McpClientFactory`, 기존 `ScenarioExecutor`·`Pes`·`PesNode`·`SimConfig`·`SimulationResult`·`RunStatus`
- Produces: `ArrivalEvent(String id, double at)`,
  `DepartureEvent(String id, double arrivedAt, double startedAt, double departedAt, double wait)`,
  `SimulatorManifest(...)` + `sha256()`,
  `ManifestAssembler.assemble(Pes, SimConfig, TopologyContract) -> SimulatorManifest`,
  `QueueInvariants.check(List<ArrivalEvent>, List<DepartureEvent>, double finishedAt, double horizon)`,
  `McpBatchScenarioExecutor` (`supports("queue-mcp-batch")`)

**`PesFlattener` 를 쓸 수 없다.** 스펙 §8.2가 "재사용을 검토한다"고 했는데, 확인 결과 불가능
하다 — `PesFlattener.collectComponents` 가 `ModelFactoryRegistry.create(spec, modelRef)` 를
부르고, `external-simpy-fifo`·`queue-local-source`·`queue-local-sink` 에는 Java
`AtomicModelFactory` 가 없어 `UnknownModelException` 이 난다. **`ManifestAssembler` 가
`Pes.leaves()` 와 루트 커플링에서 직접 조립한다.**

**어느 토폴로지 계약인가.** `execute(Pes, SimConfig)` 시그니처는 `contractId` 를 받지 않는다.
컴파일러가 SES 루트 id 를 계약의 `reservedNodeIds[0]` 으로 쓰므로 `Pes.root().entityId()` 가
계약을 지목한다. 이 대응은 Task 4가 만든 것이고 `ManifestAssembler` 가 그것을 되짚는다.

**외부 모델 계약은 실행 시점에 다시 조회한다.** 설계 시점의 계약을 `Scenario` 에 저장하려면
`Scenario` 레코드를 바꿔야 하고, 그 파일에는 미커밋 변경이 있다(스펙 §3.4). `describe_model` 을
한 번 더 부르는 것이 싸고, 그 사이 카탈로그가 바뀌었으면 실행 전에 드러난다.

- [ ] **Step 1: 이벤트와 명세 타입을 만든다**

`ArrivalEvent.java`:

```java
package org.hanbat.ses.scenario.exec.mcp;

/** 로컬 생성기가 만든 도착 이벤트. 첫 도착은 t=0 이 아니라 도착간격 시점이다. */
public record ArrivalEvent(String id, double at) {
}
```

`DepartureEvent.java`:

```java
package org.hanbat.ses.scenario.exec.mcp;

/** 외부 처리기가 돌려준 완료 이벤트. */
public record DepartureEvent(String id, double arrivedAt, double startedAt,
                             double departedAt, double wait) {
}
```

`SimulatorManifest.java`:

```java
package org.hanbat.ses.scenario.exec.mcp;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.hanbat.ses.core.model.CouplingSpec;
import org.hanbat.ses.core.pes.PesNode;

/**
 * 실행 명세 — 실험의 simulator.json 에 대응한다.
 *
 * <p>PES 를 그대로 두지 않고 명세로 한 번 옮기는 이유는 재현성이다. 같은 명세를 다시
 * 넣으면 LLM 과 대화 없이 같은 실행을 재현할 수 있어야 하고, sha256 이 그 동일성의
 * 근거가 된다.
 */
public record SimulatorManifest(
        String format,
        String contractId,
        String rootEntityId,
        String execution,
        String termination,
        String timeUnit,
        double maxSimulationTime,
        Map<String, Object> parameters,
        Map<String, String> modelRefByRole,
        Map<String, String> entityIdByRole,
        List<PesNode> nodes,
        List<CouplingSpec> connections
) {

    public static final String FORMAT = "ses-queue-simulator-v1";
    public static final String EXECUTION = "feed-forward-event-batch";
    public static final String TERMINATION = "drain-all-jobs";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public SimulatorManifest {
        parameters = parameters == null ? Map.of()
                : java.util.Collections.unmodifiableMap(
                        new java.util.LinkedHashMap<>(parameters));
        modelRefByRole = modelRefByRole == null ? Map.of() : Map.copyOf(modelRefByRole);
        entityIdByRole = entityIdByRole == null ? Map.of() : Map.copyOf(entityIdByRole);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        connections = connections == null ? List.of() : List.copyOf(connections);
    }

    public String entityId(String role) {
        String id = entityIdByRole.get(role);
        if (id == null) {
            throw new IllegalStateException("실행 명세에 " + role + " 역할이 없습니다.");
        }
        return id;
    }

    public String modelRef(String role) {
        String ref = modelRefByRole.get(role);
        if (ref == null) {
            throw new IllegalStateException("실행 명세에 " + role + " 모델이 없습니다.");
        }
        return ref;
    }

    public double requireDouble(String key) {
        Object value = parameters.get(key);
        if (!(value instanceof Number n)) {
            throw new IllegalStateException(
                    key + " 파라미터가 수치가 아닙니다: " + value);
        }
        return n.doubleValue();
    }

    public int requireInt(String key) {
        double value = requireDouble(key);
        if (value != Math.rint(value)) {
            throw new IllegalStateException(key + " 은 정수여야 합니다: " + value);
        }
        return (int) value;
    }

    /** 실행 명세의 지문. 같은 결과가 같은 명세에서 나왔는지 확인하는 근거다. */
    public String sha256() {
        try {
            byte[] json = MAPPER.writeValueAsBytes(this);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("실행 명세 지문을 계산하지 못했습니다.", e);
        }
    }
}
```

- [ ] **Step 2: `ManifestAssembler` 테스트를 쓴다**

`ManifestAssemblerTest.java`:

```java
package org.hanbat.ses.scenario.exec.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.model.CouplingSpec;
import org.hanbat.ses.core.pes.Pes;
import org.hanbat.ses.core.pes.PesNode;
import org.hanbat.ses.template.topology.TopologyContract;
import org.hanbat.ses.template.topology.TopologyContractRegistry;
import org.hanbat.ses.devs.engine.SimConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ManifestAssemblerTest {

    private final ManifestAssembler assembler = new ManifestAssembler();
    private TopologyContract topology;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/queue.topology.json")) {
            topology = TopologyContractRegistry.load(in);
        }
    }

    private static Pes pes() {
        Map<String, Object> sourceParams = new LinkedHashMap<>();
        sourceParams.put("arrivalInterval", 2.0);
        sourceParams.put("totalJobs", 5);

        PesNode source = new PesNode("gen-1", "작업생성", "queue-local-source",
                sourceParams, List.of(), List.of());
        PesNode processor = new PesNode("proc-1", "처리기", "external-simpy-fifo",
                Map.of("serviceTime", 3.0), List.of(), List.of());
        PesNode sink = new PesNode("agg-1", "집계", "queue-local-sink",
                Map.of(), List.of(), List.of());

        return new Pes(new PesNode("queue-root", "대기행렬", null, Map.of(),
                List.of(source, processor, sink),
                List.of(CouplingSpec.ic("gen-1", "out", "proc-1", "in"),
                        CouplingSpec.ic("proc-1", "out", "agg-1", "in"))));
    }

    private static SimConfig config() {
        return new SimConfig(2000.0, 0.01, 42L, 60_000L, 5_000_000L, 10_000, 5_000);
    }

    @Test
    @DisplayName("루트 엔티티 id 로 계약을 찾아 고정값을 채운다")
    void fillsFixedFields() {
        SimulatorManifest manifest = assembler.assemble(pes(), config(), topology);

        assertThat(manifest.format()).isEqualTo("ses-queue-simulator-v1");
        assertThat(manifest.execution()).isEqualTo("feed-forward-event-batch");
        assertThat(manifest.termination()).isEqualTo("drain-all-jobs");
        assertThat(manifest.contractId()).isEqualTo("fifo-single-server");
        assertThat(manifest.rootEntityId()).isEqualTo("queue-root");
        assertThat(manifest.timeUnit()).isEqualTo("분");
        assertThat(manifest.maxSimulationTime()).isEqualTo(2000.0);
    }

    @Test
    @DisplayName("modelRef 로 역할을 되짚는다 - 외부 역할은 나머지 하나다")
    void resolvesRolesByModelRef() {
        SimulatorManifest manifest = assembler.assemble(pes(), config(), topology);

        assertThat(manifest.entityId("SOURCE")).isEqualTo("gen-1");
        assertThat(manifest.entityId("PROCESSOR")).isEqualTo("proc-1");
        assertThat(manifest.entityId("SINK")).isEqualTo("agg-1");
        assertThat(manifest.modelRef("PROCESSOR")).isEqualTo("external-simpy-fifo");
    }

    @Test
    @DisplayName("모든 리프의 파라미터를 하나로 모은다")
    void collectsParameters() {
        SimulatorManifest manifest = assembler.assemble(pes(), config(), topology);

        assertThat(manifest.parameters())
                .containsEntry("arrivalInterval", 2.0)
                .containsEntry("totalJobs", 5)
                .containsEntry("serviceTime", 3.0);
        assertThat(manifest.requireInt("totalJobs")).isEqualTo(5);
        assertThat(manifest.requireDouble("serviceTime")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("배선을 그대로 옮긴다")
    void copiesConnections() {
        SimulatorManifest manifest = assembler.assemble(pes(), config(), topology);

        assertThat(manifest.connections()).hasSize(2);
        assertThat(manifest.connections()).allMatch(
                c -> "out".equals(c.fromPort()) && "in".equals(c.toPort()));
    }

    @Test
    @DisplayName("같은 PES 와 설정은 같은 지문을 낸다")
    void sha256IsStable() {
        assertThat(assembler.assemble(pes(), config(), topology).sha256())
                .isEqualTo(assembler.assemble(pes(), config(), topology).sha256())
                .hasSize(64);
    }

    @Test
    @DisplayName("파라미터가 달라지면 지문도 달라진다")
    void sha256TracksParameters() {
        Pes other = new Pes(new PesNode("queue-root", "대기행렬", null, Map.of(),
                List.of(new PesNode("gen-1", "작업생성", "queue-local-source",
                                Map.of("arrivalInterval", 4.0, "totalJobs", 5),
                                List.of(), List.of()),
                        new PesNode("proc-1", "처리기", "external-simpy-fifo",
                                Map.of("serviceTime", 3.0), List.of(), List.of()),
                        new PesNode("agg-1", "집계", "queue-local-sink",
                                Map.of(), List.of(), List.of())),
                List.of(CouplingSpec.ic("gen-1", "out", "proc-1", "in"),
                        CouplingSpec.ic("proc-1", "out", "agg-1", "in"))));

        assertThat(assembler.assemble(other, config(), topology).sha256())
                .isNotEqualTo(assembler.assemble(pes(), config(), topology).sha256());
    }

    @Test
    @DisplayName("리프 수가 계약과 다르면 실패한다")
    void wrongLeafCountFails() {
        Pes twoLeaves = new Pes(new PesNode("queue-root", "대기행렬", null, Map.of(),
                List.of(new PesNode("gen-1", "작업생성", "queue-local-source",
                                Map.of(), List.of(), List.of()),
                        new PesNode("agg-1", "집계", "queue-local-sink",
                                Map.of(), List.of(), List.of())),
                List.of()));

        assertThatThrownBy(() -> assembler.assemble(twoLeaves, config(), topology))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3");
    }

    @Test
    @DisplayName("루트 id 가 어느 계약과도 맞지 않으면 실패한다")
    void unknownRootFails() {
        Pes alien = new Pes(new PesNode("resort-root", "리조트", null, Map.of(),
                List.of(), List.of()));

        assertThatThrownBy(() -> assembler.assemble(alien, config(), topology))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resort-root");
    }

    @Test
    @DisplayName("중첩된 리프는 지원하지 않는다")
    void nestedLeafFails() {
        PesNode nested = new PesNode("proc-1", "처리기", "external-simpy-fifo",
                Map.of("serviceTime", 3.0),
                List.of(new PesNode("inner", "내부", "x", Map.of(), List.of(), List.of())),
                List.of());
        Pes withNested = new Pes(new PesNode("queue-root", "대기행렬", null, Map.of(),
                List.of(new PesNode("gen-1", "작업생성", "queue-local-source",
                                Map.of("arrivalInterval", 2.0, "totalJobs", 5),
                                List.of(), List.of()),
                        nested,
                        new PesNode("agg-1", "집계", "queue-local-sink",
                                Map.of(), List.of(), List.of())),
                List.of()));

        assertThatThrownBy(() -> assembler.assemble(withNested, config(), topology))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

`modules/scenario/src/test/resources/queue.topology.json` 을 Task 2와 같은 내용으로 복사한다.

- [ ] **Step 3: `ManifestAssembler` 를 구현한다**

```java
package org.hanbat.ses.scenario.exec.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.pes.Pes;
import org.hanbat.ses.core.pes.PesNode;
import org.hanbat.ses.template.topology.RoleSpec;
import org.hanbat.ses.template.topology.TopologyContract;
import org.hanbat.ses.devs.engine.SimConfig;
import org.springframework.stereotype.Component;

/**
 * PES -> 실행 명세.
 *
 * <p>PesFlattener 를 쓰지 않는다. 그쪽은 ModelFactoryRegistry.create 로 Java 원자 모델을
 * 만드는데, 이 경로의 모델 세 개는 모두 Java 팩토리가 없다 — 외부 모델은 Docker 안에
 * 있고 로컬 생성기/집계기는 이 executor 가 직접 계산한다.
 *
 * <p>역할은 modelRef 로 되짚는다. 계약이 내부 역할의 modelRef 를 못 박고 있으므로,
 * 그 둘을 지운 나머지 하나가 외부 역할이다. 이름이나 순서에 의존하지 않는다 —
 * 노드 이름은 LLM 이 정하고 순서는 초안이 정한다.
 */
@Component
public class ManifestAssembler {

    public SimulatorManifest assemble(Pes pes, SimConfig config, TopologyContract... contracts) {
        String rootId = pes.root().entityId();
        TopologyContract contract = null;
        for (TopologyContract candidate : contracts) {
            if (!candidate.reservedNodeIds().isEmpty()
                    && candidate.reservedNodeIds().get(0).equals(rootId)) {
                contract = candidate;
                break;
            }
        }
        if (contract == null) {
            throw new IllegalStateException(
                    "루트 엔티티 " + rootId + " 에 대응하는 토폴로지 계약이 없습니다.");
        }

        List<PesNode> leaves = pes.leaves();
        if (leaves.size() != contract.nodeCount()) {
            throw new IllegalStateException("실행 가능한 구성요소가 "
                    + contract.nodeCount() + "개여야 하는데 " + leaves.size() + "개입니다.");
        }
        for (PesNode leaf : leaves) {
            if (!leaf.children().isEmpty() || !leaf.couplings().isEmpty()) {
                throw new IllegalStateException(
                        "중첩된 구성요소는 지원하지 않습니다: " + leaf.entityId());
            }
        }

        Map<String, String> entityIdByRole = new LinkedHashMap<>();
        Map<String, String> modelRefByRole = new LinkedHashMap<>();
        RoleSpec externalRole = contract.externalRole();

        for (RoleSpec role : contract.roles()) {
            if (role.external()) {
                continue;
            }
            PesNode node = leaves.stream()
                    .filter(l -> role.modelRef().equals(l.modelRef()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            role.role() + " 역할의 모델 " + role.modelRef()
                                    + " 가 PES 에 없습니다."));
            entityIdByRole.put(role.role(), node.entityId());
            modelRefByRole.put(role.role(), role.modelRef());
        }

        List<PesNode> remaining = leaves.stream()
                .filter(l -> !entityIdByRole.containsValue(l.entityId()))
                .toList();
        if (remaining.size() != 1) {
            throw new IllegalStateException("외부 모델 구성요소가 " + remaining.size()
                    + "개입니다. 정확히 1개여야 합니다.");
        }
        entityIdByRole.put(externalRole.role(), remaining.get(0).entityId());
        modelRefByRole.put(externalRole.role(), remaining.get(0).modelRef());

        Map<String, Object> parameters = new LinkedHashMap<>();
        leaves.forEach(l -> parameters.putAll(l.params()));

        return new SimulatorManifest(
                SimulatorManifest.FORMAT, contract.contractId(), rootId,
                SimulatorManifest.EXECUTION, SimulatorManifest.TERMINATION,
                contract.timeUnit(), config.horizon(),
                parameters, modelRefByRole, entityIdByRole,
                leaves, pes.root().couplings());
    }
}
```

가변 인자로 계약을 받는 이유는 `TopologyContractRegistry` 가 `modules:design` 에 있고
테스트가 계약 하나만 넘기면 되기 때문이다. executor 는 `registry.findAll()` 을 펼쳐 넘긴다.

- [ ] **Step 4: `QueueInvariants` 테스트를 쓰고 구현한다**

`QueueInvariantsTest.java`:

```java
package org.hanbat.ses.scenario.exec.mcp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueueInvariantsTest {

    private static final List<ArrivalEvent> ARRIVALS = List.of(
            new ArrivalEvent("job-1", 2), new ArrivalEvent("job-2", 4),
            new ArrivalEvent("job-3", 6));

    private static final List<DepartureEvent> DEPARTURES = List.of(
            new DepartureEvent("job-1", 2, 2, 5, 0),
            new DepartureEvent("job-2", 4, 5, 8, 1),
            new DepartureEvent("job-3", 6, 8, 11, 2));

    @Test
    @DisplayName("정상 실행은 통과한다")
    void validRunPasses() {
        assertThatCode(() -> QueueInvariants.check(ARRIVALS, DEPARTURES, 11, 2000))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("작업이 사라지면 실패한다")
    void jobLossFails() {
        assertThatThrownBy(() -> QueueInvariants.check(
                ARRIVALS, DEPARTURES.subList(0, 2), 8, 2000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("보존");
    }

    @Test
    @DisplayName("id 순서가 어긋나면 실패한다")
    void orderViolationFails() {
        List<DepartureEvent> shuffled = List.of(
                DEPARTURES.get(1), DEPARTURES.get(0), DEPARTURES.get(2));

        assertThatThrownBy(() -> QueueInvariants.check(ARRIVALS, shuffled, 11, 2000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("순서");
    }

    @Test
    @DisplayName("도착보다 먼저 시작하면 실패한다")
    void startsBeforeArrivalFails() {
        List<DepartureEvent> impossible = List.of(
                new DepartureEvent("job-1", 2, 1, 4, -1),
                DEPARTURES.get(1), DEPARTURES.get(2));

        assertThatThrownBy(() -> QueueInvariants.check(ARRIVALS, impossible, 11, 2000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("인과");
    }

    @Test
    @DisplayName("완료가 시작보다 앞서면 실패한다")
    void departsBeforeStartFails() {
        List<DepartureEvent> impossible = List.of(
                new DepartureEvent("job-1", 2, 2, 2, 0),
                DEPARTURES.get(1), DEPARTURES.get(2));

        assertThatThrownBy(() -> QueueInvariants.check(ARRIVALS, impossible, 11, 2000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("인과");
    }

    @Test
    @DisplayName("도착 시각이 보고된 것과 다르면 실패한다")
    void arrivalMismatchFails() {
        List<DepartureEvent> wrong = List.of(
                new DepartureEvent("job-1", 99, 99, 102, 0),
                DEPARTURES.get(1), DEPARTURES.get(2));

        assertThatThrownBy(() -> QueueInvariants.check(ARRIVALS, wrong, 102, 2000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("도착");
    }

    @Test
    @DisplayName("종료 시각이 horizon 을 넘으면 실패한다")
    void beyondHorizonFails() {
        assertThatThrownBy(() -> QueueInvariants.check(ARRIVALS, DEPARTURES, 11, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("상한");
    }

    @Test
    @DisplayName("보고된 종료 시각이 마지막 완료와 다르면 실패한다")
    void finishTimeMismatchFails() {
        assertThatThrownBy(() -> QueueInvariants.check(ARRIVALS, DEPARTURES, 99, 2000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("종료 시각");
    }
}
```

`QueueInvariants.java`:

```java
package org.hanbat.ses.scenario.exec.mcp;

import java.util.List;

/**
 * 런타임 불변식 — 모델과 무관하게 성립하는 것만 본다.
 *
 * <p>실험의 collect 는 serviceTime 으로 정확한 기대값을 계산해 대조했다. 그 계산은
 * 일정 처리시간 단일 서버 FIFO 에서만 성립하므로 골든 테스트로 옮겼다(스펙 §7.3).
 * 여기 남은 것은 어떤 대기행렬 모델이든 지켜야 하는 것뿐이다.
 */
public final class QueueInvariants {

    private static final double TOLERANCE = 1e-7;

    private QueueInvariants() {
    }

    public static void check(List<ArrivalEvent> arrivals, List<DepartureEvent> departures,
                             double finishedAt, double horizon) {
        if (arrivals.size() != departures.size()) {
            throw new IllegalStateException("작업 보존이 깨졌습니다: 도착 " + arrivals.size()
                    + "건, 완료 " + departures.size() + "건");
        }
        if (departures.isEmpty()) {
            throw new IllegalStateException("완료된 작업이 없습니다.");
        }

        double previousDeparture = 0;
        for (int i = 0; i < departures.size(); i++) {
            ArrivalEvent arrival = arrivals.get(i);
            DepartureEvent departure = departures.get(i);

            if (!arrival.id().equals(departure.id())) {
                throw new IllegalStateException("작업 순서가 어긋났습니다: " + i + "번째가 "
                        + arrival.id() + " 여야 하는데 " + departure.id() + " 입니다.");
            }
            if (Math.abs(departure.arrivedAt() - arrival.at()) > TOLERANCE) {
                throw new IllegalStateException(departure.id() + " 의 도착 시각이 다릅니다: 보낸 값 "
                        + arrival.at() + ", 받은 값 " + departure.arrivedAt());
            }
            if (departure.startedAt() < departure.arrivedAt() - TOLERANCE) {
                throw new IllegalStateException("인과성 위반: " + departure.id()
                        + " 가 도착(" + departure.arrivedAt() + ") 전에 시작("
                        + departure.startedAt() + ")했습니다.");
            }
            if (departure.departedAt() <= departure.startedAt() + TOLERANCE) {
                throw new IllegalStateException("인과성 위반: " + departure.id()
                        + " 의 완료(" + departure.departedAt() + ")가 시작("
                        + departure.startedAt() + ") 이후가 아닙니다.");
            }
            if (departure.startedAt() < previousDeparture - TOLERANCE) {
                throw new IllegalStateException("단일 서버 위반: " + departure.id()
                        + " 가 앞 작업 완료(" + previousDeparture + ") 전에 시작했습니다.");
            }
            previousDeparture = departure.departedAt();
        }

        if (Math.abs(finishedAt - previousDeparture) > TOLERANCE) {
            throw new IllegalStateException("보고된 종료 시각(" + finishedAt
                    + ")이 마지막 완료(" + previousDeparture + ")와 다릅니다.");
        }
        if (finishedAt > horizon + TOLERANCE) {
            throw new IllegalStateException("실행이 시간 상한을 넘었습니다: " + finishedAt
                    + " > " + horizon);
        }
    }
}
```

- [ ] **Step 5: 두 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :modules:scenario:test --tests '*ManifestAssemblerTest' --tests '*QueueInvariantsTest'`
Expected: 9개 + 8개 PASS

먼저 `modules/scenario/build.gradle.kts` 에 의존을 추가한다:

```kotlin
    api(project(":modules:mcp"))
```

`modules:design` 을 의존하지 않는 것이 중요하다. 의존하면 `scenario -> design -> llm` 으로
`scenario` 가 `llm` 을 간접 의존하게 되어 기존 성질이 깨진다. 그래서 Task 2가
`TopologyContract` 를 `modules:template` 에, Task 7이 `McpClientFactory` 를 `modules:mcp` 에
두었다 — 이 태스크가 필요한 것은 그 둘뿐이다.

`modules/scenario/src/test/resources/queue.topology.json` 을 Task 2와 같은 내용으로 복사한다.

- [ ] **Step 6: `McpBatchScenarioExecutor` 를 구현한다**

```java
package org.hanbat.ses.scenario.exec.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.pes.Pes;
import org.hanbat.ses.devs.engine.RunStatus;
import org.hanbat.ses.devs.engine.SimConfig;
import org.hanbat.ses.devs.engine.SimulationResult;
import org.hanbat.ses.mcp.McpClient;
import org.hanbat.ses.mcp.McpClientFactory;
import org.hanbat.ses.mcp.McpServerConfig;
import org.hanbat.ses.scenario.exec.ScenarioExecutor;
import org.hanbat.ses.template.topology.TopologyContract;
import org.hanbat.ses.template.topology.TopologyContractRegistry;
import org.springframework.stereotype.Component;

/**
 * 외부 MCP 모델을 태워 대기행렬을 실행한다.
 *
 * <p>로컬 생성기가 도착 이벤트 전량을 만들어 외부 처리기에 한 번에 넘기고, 로컬 집계기가
 * 완료 이벤트를 받아 집계한다. 피드백이 없는 단일 연결 구조이므로 시간 동기화 없이
 * 이 방식이 성립한다 — 분산 DEVS 가 아니다.
 */
@Component
public class McpBatchScenarioExecutor implements ScenarioExecutor {

    public static final String ENGINE = "queue-mcp-batch";
    private static final String SERVER = "queue-models";

    private final McpClientFactory clients;
    private final ManifestAssembler assembler;
    private final TopologyContractRegistry contracts;

    public McpBatchScenarioExecutor(McpClientFactory clients, ManifestAssembler assembler,
                                    TopologyContractRegistry contracts) {
        this.clients = clients;
        this.assembler = assembler;
        this.contracts = contracts;
    }

    @Override
    public boolean supports(String engine) {
        return ENGINE.equals(engine);
    }

    @Override
    public SimulationResult execute(Pes pes, SimConfig config) {
        SimulatorManifest manifest = assembler.assemble(pes, config,
                contracts.findAll().toArray(TopologyContract[]::new));

        double interval = manifest.requireDouble("arrivalInterval");
        int total = manifest.requireInt("totalJobs");
        double serviceTime = manifest.requireDouble("serviceTime");

        // 첫 도착은 t=0 이 아니라 도착간격 시점이다.
        List<ArrivalEvent> arrivals = new ArrayList<>(total);
        for (int i = 1; i <= total; i++) {
            arrivals.add(new ArrivalEvent("job-" + i, i * interval));
        }

        String modelId = manifest.modelRef("PROCESSOR");
        McpServerConfig server = clients.config(SERVER);

        Map<String, Object> result;
        Map<String, Object> provision;
        try (McpClient client = clients.open(SERVER)) {
            Map<String, Object> catalog = client.callTool("describe_model",
                    Map.of("modelId", modelId), server.timeouts().defaultMs());
            requireSameContract(catalog, modelId, manifest.timeUnit());

            provision = client.callTool("provision_model", Map.of("modelId", modelId),
                    server.timeouts().provisionMs());

            result = client.callTool("run_processor", Map.of(
                            "modelId", modelId,
                            "payload", Map.of("serviceTime", serviceTime,
                                    "arrivals", arrivals.stream().map(a -> Map.of(
                                            "id", a.id(), "at", a.at())).toList())),
                    Math.min(server.timeouts().runMs(), config.timeoutMs()));
        }

        List<DepartureEvent> departures = departuresOf(result);
        double finishedAt = asDouble(result.get("finishedAt"));

        QueueInvariants.check(arrivals, departures, finishedAt, manifest.maxSimulationTime());

        return new SimulationResult(RunStatus.COMPLETED, finishedAt,
                (long) (arrivals.size() + departures.size()),
                statistics(manifest, departures, serviceTime, finishedAt, result, provision),
                List.of(), List.of(),
                "모든 작업이 완료되어 t=" + finishedAt + manifest.timeUnit()
                        + " 에 종료했습니다. 단일 서버 / FIFO / 무한 대기실을 전제합니다.");
    }

    // ------------------------------------------------------------ 부분

    private void requireSameContract(Map<String, Object> catalog, String modelId,
                                     String timeUnit) {
        if (!modelId.equals(catalog.get("modelId"))) {
            throw new IllegalStateException("모델 정체성이 다릅니다: 기대 " + modelId
                    + ", 조회 " + catalog.get("modelId"));
        }
        if (!timeUnit.equals(catalog.get("timeUnit"))) {
            throw new IllegalStateException("모델의 시간 단위가 달라졌습니다: 기대 " + timeUnit
                    + ", 조회 " + catalog.get("timeUnit"));
        }
    }

    @SuppressWarnings("unchecked")
    private List<DepartureEvent> departuresOf(Map<String, Object> result) {
        Object raw = result.get("departures");
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("외부 모델이 departures 를 돌려주지 않았습니다.");
        }
        List<DepartureEvent> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new IllegalStateException("departures 항목이 객체가 아닙니다.");
            }
            Map<String, Object> e = (Map<String, Object>) m;
            out.add(new DepartureEvent(String.valueOf(e.get("id")),
                    asDouble(e.get("arrivedAt")), asDouble(e.get("startedAt")),
                    asDouble(e.get("departedAt")), asDouble(e.get("wait"))));
        }
        return out;
    }

    /**
     * 통계 키에 컴포넌트 접두사를 붙인다.
     *
     * <p>지표 이름을 리조트 도메인과 겹치지 않게 둔다. .arrived 를 재사용하면
     * ResultFormatter 가 대기행렬 작업을 "도착 방문객"으로 라벨링한다.
     */
    private Map<String, Object> statistics(SimulatorManifest manifest,
                                           List<DepartureEvent> departures, double serviceTime,
                                           double finishedAt, Map<String, Object> result,
                                           Map<String, Object> provision) {
        String sink = manifest.entityId("SINK");
        double waitSum = departures.stream().mapToDouble(DepartureEvent::wait).sum();
        double maxWait = departures.stream().mapToDouble(DepartureEvent::wait).max().orElse(0);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put(sink + ".jobsArrived", (double) departures.size());
        stats.put(sink + ".jobsCompleted", (double) departures.size());
        stats.put(sink + ".jobsLost", 0.0);
        stats.put(sink + ".meanWait", waitSum / departures.size());
        stats.put(sink + ".maxWait", maxWait);
        stats.put("utilizationFromTimeZero", serviceTime * departures.size() / finishedAt);
        stats.put("imageId", result.get("imageId"));
        stats.put("libraryVersion", result.get("libraryVersion"));
        stats.put("provisionedImage", provision.get("image"));
        stats.put("manifestSha256", manifest.sha256());
        stats.put("contractId", manifest.contractId());
        return stats;
    }

    private static double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalStateException("수치가 아닌 값입니다: " + value);
    }
}
```

`McpClientFactory` 는 Task 7이 `modules:mcp` 에 만든 그것을 그대로 주입받는다.
`modules:scenario` 는 `modules:mcp` 만 의존하고 `modules:llm` 은 여전히 모른다.

- [ ] **Step 7: executor 테스트를 쓴다**

`McpBatchScenarioExecutorTest.java` — `McpClientFactory` 를 상속해 `ScriptedTransport`
기반 클라이언트를 돌려주게 만들고, `run_processor` 응답을 골든 값으로 고정한다.
`ScriptedTransport` 는 Task 1에서 `modules:mcp` 테스트 소스에 있으므로,
`modules/mcp/build.gradle.kts` 에 `java-test-fixtures` 플러그인을 추가하고
`ScriptedTransport` 를 `src/testFixtures/java` 로 옮긴다. `modules:core-ses` 가 이미
`testFixtures` 소스셋을 쓰고 있어 관례가 있다.

```java
    @Test
    @DisplayName("골든 입력으로 골든 결과를 낸다")
    void goldenRun() {
        // 도착간격 2, 처리시간 3, 작업 5건 -> 평균 대기 2, 최대 4, 종료 17
        SimulationResult result = executor().execute(goldenPes(), config());

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.endTime()).isEqualTo(17.0);
        assertThat(result.statistics())
                .containsEntry("agg-1.jobsCompleted", 5.0)
                .containsEntry("agg-1.jobsLost", 0.0)
                .containsEntry("agg-1.meanWait", 2.0)
                .containsEntry("agg-1.maxWait", 4.0);
        assertThat((double) result.statistics().get("utilizationFromTimeZero"))
                .isEqualTo(15.0 / 17.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.statistics().get("manifestSha256")).asString().hasSize(64);
    }

    @Test
    @DisplayName("supports 는 queue-mcp-batch 만 받는다")
    void supportsOnlyOwnEngine() {
        assertThat(executor().supports("queue-mcp-batch")).isTrue();
        assertThat(executor().supports("devs-internal")).isFalse();
    }

    @Test
    @DisplayName("외부 모델이 작업을 잃으면 실행이 실패한다")
    void jobLossFailsRun() {
        assertThatThrownBy(() -> executorLosing().execute(goldenPes(), config()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("보존");
    }

    @Test
    @DisplayName("실행 시점에 모델 정체성이 달라지면 실패한다")
    void identityChangeFailsRun() {
        assertThatThrownBy(() -> executorWithOtherModelId().execute(goldenPes(), config()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("정체성");
    }
```

골든 응답 JSON (`run_processor` 의 `structuredContent`):

```json
{"modelId":"external-simpy-fifo","timeUnit":"분","imageId":"sha256:abc","libraryVersion":"4.1.1",
 "finishedAt":17,
 "departures":[{"id":"job-1","arrivedAt":2,"startedAt":2,"departedAt":5,"wait":0},
               {"id":"job-2","arrivedAt":4,"startedAt":5,"departedAt":8,"wait":1},
               {"id":"job-3","arrivedAt":6,"startedAt":8,"departedAt":11,"wait":2},
               {"id":"job-4","arrivedAt":8,"startedAt":11,"departedAt":14,"wait":3},
               {"id":"job-5","arrivedAt":10,"startedAt":14,"departedAt":17,"wait":4}]}
```

- [ ] **Step 8: `ResultFormatter` 에 다섯 줄을 추가한다**

`summary(...)` 의 기존 `appendFirst(sb, stats, "평균 체류시간", ".avgTurnaround", "분");` 다음에:

```java
        // 대기행렬(queue-mcp-batch) 경로 지표. 리조트 지표와 접미사가 겹치지 않게 둔다 —
        // .arrived 를 공유하면 작업이 "도착 방문객"으로 표시된다.
        appendSum(sb, stats, "도착 작업", ".jobsArrived");
        appendSum(sb, stats, "완료 작업", ".jobsCompleted");
        appendSum(sb, stats, "손실 작업", ".jobsLost");
        appendFirst(sb, stats, "평균 대기시간", ".meanWait", "분");
        appendMax(sb, stats, "최대 대기시간", ".maxWait");
```

`appendSum`·`appendMax`·`appendFirst` 는 값이 없으면 아무것도 붙이지 않으므로, 리조트
시나리오의 요약에는 이 다섯 줄이 나타나지 않는다.

- [ ] **Step 9: 전체 테스트를 돌린다**

Run: `./gradlew :modules:mcp:test :modules:template:test :modules:design:test :modules:scenario:test`
Expected: 전부 PASS. 특히 기존 `PesFlattenerTest`, `MmcAnalyticGoldenTest`,
`TemplateConsistencyCheckerTest` 가 계속 통과해야 한다 — 통과하지 않으면 토폴로지 이동이
기존 코드를 건드린 것이다.

- [ ] **Step 10: 커밋**

```bash
git add modules
git commit -m "McpBatchScenarioExecutor - 외부 MCP 모델 실행 경로

ScenarioExecutor 구현체를 하나 더 꽂는다. engine=queue-mcp-batch 이며
기존 DevsScenarioExecutor 는 건드리지 않는다.

PesFlattener 를 재사용할 수 없음을 확인했다. 그쪽은
ModelFactoryRegistry.create 로 Java 원자 모델을 만드는데 이 경로의 모델
세 개는 모두 Java 팩토리가 없다. ManifestAssembler 가 Pes.leaves() 와
루트 커플링에서 직접 조립한다.

역할은 modelRef 로 되짚는다. 계약이 내부 역할의 modelRef 를 못 박으므로
나머지 하나가 외부 역할이다. 노드 이름과 순서에 의존하지 않는다.

해석해 대조는 골든 테스트로 옮기고 런타임에는 모델 무관 불변식만 남긴다 -
작업 보존, 인과성, 단일 서버, id 순서, horizon.

modules:scenario 는 modules:mcp 만 새로 의존한다. modules:design 을
의존하면 scenario 가 llm 을 간접 의존하게 되어 기존 성질이 깨진다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: MCP 교환 감사로그 영속화

**Files:**
- Create: `app/src/main/resources/db/migration/V5__mcp_exchange.sql`
- Create: `modules/persistence/src/main/java/org/hanbat/ses/persistence/entity/McpExchangeEntity.java`
- Create: `modules/persistence/src/main/java/org/hanbat/ses/persistence/repository/McpExchangeRepository.java`
- Create: `modules/persistence/src/main/java/org/hanbat/ses/persistence/adapter/JpaMcpExchangeLog.java`
- Modify: `modules/persistence/build.gradle.kts` (`api(project(":modules:mcp"))`)
- Test: `modules/persistence/src/test/java/org/hanbat/ses/persistence/adapter/JpaMcpExchangeLogTest.java`

**Interfaces:**
- Consumes: Task 1 `McpExchange`·`McpExchangeLog`
- Produces: `JpaMcpExchangeLog implements McpExchangeLog` (`@ConditionalOnJpaPersistence`)

**기록 실패가 본 흐름을 막지 않는다.** `LlmCallRecorder` 의 기존 규약과 같다 — 감사 기록을
저장하지 못했다고 시뮬레이션을 실패시키면, 로그 테이블 문제가 서비스 장애가 된다.

`memory` 프로파일에서는 `McpExchangeLog.noop()` 을 쓴다. Task 10의 `McpConfiguration` 이
`ObjectProvider<McpExchangeLog>` 로 받아 없으면 `noop()` 으로 떨어진다 —
`LlmConfiguration.llmGateway(...)` 가 `ObjectProvider<LlmCallRecorder>` 를 쓰는 방식과 같다.

- [ ] **Step 1: 마이그레이션을 쓴다**

`V5__mcp_exchange.sql`:

```sql
-- MCP 교환 기록. 실험의 mcp.jsonl 에 대응한다.
--
-- 이 시스템은 외부 모델을 Docker 안에서 실행하므로, 어떤 도구를 어떤 인자로 불렀고
-- 무엇이 돌아왔는지가 결과의 출처다. 그 기록이 없으면 결과를 재현할 수도, 어긋난
-- 결과의 원인을 짚을 수도 없다.
CREATE TABLE mcp_exchange (
    id           BIGSERIAL    PRIMARY KEY,
    run_id       UUID,
    session_id   UUID,
    server_name  VARCHAR(64)  NOT NULL,
    direction    VARCHAR(16)  NOT NULL,
    method       VARCHAR(64)  NOT NULL,
    tool_name    VARCHAR(64),
    payload      TEXT         NOT NULL,
    at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 한 실행의 교환을 시간순으로 훑는 것이 유일한 조회 패턴이다.
CREATE INDEX idx_mcp_exchange_run ON mcp_exchange (run_id, id);
CREATE INDEX idx_mcp_exchange_session ON mcp_exchange (session_id, id);
```

`payload` 를 `jsonb` 가 아니라 `TEXT` 로 두는 이유는 우리가 저장하는 것이 **JSON-RPC 원문**
이라서다. `jsonb` 는 키 순서를 보존하지 않아 원문 대조가 불가능해진다. 감사 기록의 목적은
질의가 아니라 재현이다.

- [ ] **Step 2: 엔티티와 리포지토리를 만든다**

`McpExchangeEntity.java`:

```java
package org.hanbat.ses.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "mcp_exchange")
public class McpExchangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "server_name", nullable = false, length = 64)
    private String serverName;

    @Column(nullable = false, length = 16)
    private String direction;

    @Column(nullable = false, length = 64)
    private String method;

    @Column(name = "tool_name", length = 64)
    private String toolName;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "at", nullable = false)
    private Instant at;

    protected McpExchangeEntity() {
    }

    public McpExchangeEntity(UUID runId, UUID sessionId, String serverName, String direction,
                             String method, String toolName, String payload, Instant at) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.serverName = serverName;
        this.direction = direction;
        this.method = method;
        this.toolName = toolName;
        this.payload = payload;
        this.at = at;
    }

    public Long getId() {
        return id;
    }

    public String getServerName() {
        return serverName;
    }

    public String getDirection() {
        return direction;
    }

    public String getMethod() {
        return method;
    }

    public String getToolName() {
        return toolName;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getAt() {
        return at;
    }
}
```

`McpExchangeRepository.java`:

```java
package org.hanbat.ses.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.hanbat.ses.persistence.entity.McpExchangeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpExchangeRepository extends JpaRepository<McpExchangeEntity, Long> {

    List<McpExchangeEntity> findByRunIdOrderByIdAsc(UUID runId);

    List<McpExchangeEntity> findBySessionIdOrderByIdAsc(UUID sessionId);
}
```

- [ ] **Step 3: 어댑터를 만든다**

`JpaMcpExchangeLog.java`:

```java
package org.hanbat.ses.persistence.adapter;

import java.time.Instant;

import org.hanbat.ses.mcp.McpExchange;
import org.hanbat.ses.mcp.McpExchangeLog;
import org.hanbat.ses.persistence.entity.McpExchangeEntity;
import org.hanbat.ses.persistence.repository.McpExchangeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 교환을 DB 에 남긴다.
 *
 * <p>기록 실패가 본 흐름을 막지 않는다 — LlmCallRecorder 와 같은 규약이다. 로그 테이블
 * 문제가 시뮬레이션 장애가 되면 감사 기능이 서비스 위험이 된다.
 *
 * <p>REQUIRES_NEW 로 별도 트랜잭션에 쓴다. 호출부의 트랜잭션에 얹으면 실행이 실패해
 * 롤백될 때 "무엇을 불렀다가 실패했는지"가 함께 사라진다 — 정확히 그때 필요한 기록이다.
 */
@Component
@ConditionalOnJpaPersistence
public class JpaMcpExchangeLog implements McpExchangeLog {

    private static final Logger log = LoggerFactory.getLogger(JpaMcpExchangeLog.class);

    private final McpExchangeRepository repository;

    public JpaMcpExchangeLog(McpExchangeRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(McpExchange exchange) {
        try {
            repository.save(new McpExchangeEntity(
                    McpRunContext.runId(), McpRunContext.sessionId(),
                    exchange.server(), exchange.direction(), exchange.method(),
                    exchange.toolName(), exchange.payload(), Instant.now()));
        } catch (RuntimeException e) {
            log.warn("MCP 교환 기록 실패 ({} {}): {}",
                    exchange.direction(), exchange.method(), e.toString());
        }
    }
}
```

`McpRunContext` 는 `modules:mcp` 에 두는 스레드 로컬이다. `modules:llm` 의
`SessionContext` 와 같은 패턴이고 그것을 본떠 만든다.

`modules/mcp/src/main/java/org/hanbat/ses/mcp/McpRunContext.java`:

```java
package org.hanbat.ses.mcp;

import java.util.UUID;

/**
 * 지금 실행 중인 시나리오/세션 식별자.
 *
 * <p>McpClient 의 시그니처에 runId 를 끼워 넣지 않으려고 스레드 로컬을 쓴다.
 * modules:llm 의 SessionContext 와 같은 패턴이다 — 감사 기록에만 쓰이는 값이
 * 도메인 메서드 시그니처를 오염시키지 않게 한다.
 *
 * <p>설정하지 않으면 null 이고 기록에 null 로 남는다. 그것도 정보다 — 어느 실행에도
 * 속하지 않은 교환(예: 설계 단계의 카탈로그 조회)이 있다.
 */
public final class McpRunContext {

    private static final ThreadLocal<UUID> RUN = new ThreadLocal<>();
    private static final ThreadLocal<UUID> SESSION = new ThreadLocal<>();

    private McpRunContext() {
    }

    public static void set(UUID runId, UUID sessionId) {
        RUN.set(runId);
        SESSION.set(sessionId);
    }

    public static void clear() {
        RUN.remove();
        SESSION.remove();
    }

    public static UUID runId() {
        return RUN.get();
    }

    public static UUID sessionId() {
        return SESSION.get();
    }
}
```

`modules/persistence/build.gradle.kts` 에 `api(project(":modules:mcp"))` 를 추가한다.

- [ ] **Step 4: 테스트를 쓴다**

`JpaMcpExchangeLogTest.java` — `@DataJpaTest` 는 이 프로젝트에서 PostgreSQL 을 요구하므로
`@Tag("integration")` 을 붙이고 기존 `PostgresPersistenceIT` 의 Testcontainers 설정을 따른다.
그 파일에 미커밋 변경이 있으므로(스펙 §3.4) 착수 시 현재 형태를 먼저 읽는다.

단위 수준에서는 저장 실패가 예외를 던지지 않는 것만 확인한다 — 이것이 이 클래스의
유일한 계약이다:

```java
package org.hanbat.ses.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.hanbat.ses.mcp.McpExchange;
import org.hanbat.ses.persistence.entity.McpExchangeEntity;
import org.hanbat.ses.persistence.repository.McpExchangeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JpaMcpExchangeLogTest {

    @Test
    @DisplayName("저장이 실패해도 예외를 던지지 않는다")
    void swallowsPersistenceFailure() {
        McpExchangeRepository broken = new BrokenRepository();

        assertThatCode(() -> new JpaMcpExchangeLog(broken).record(
                new McpExchange("queue-models", "request", "tools/call",
                        "run_processor", "{}")))
                .doesNotThrowAnyException();
    }

    /** save 만 터지는 최소 스텁. JpaRepository 의 나머지는 쓰지 않는다. */
    private static final class BrokenRepository
            implements McpExchangeRepository {

        @Override
        public <S extends McpExchangeEntity> S save(S entity) {
            throw new IllegalStateException("DB 연결 없음");
        }

        // 나머지 메서드는 이 테스트에서 호출되지 않는다.
        // IDE 의 "Implement methods" 로 생성하고 전부
        // throw new UnsupportedOperationException(); 로 둔다.
    }
}
```

`JpaRepository` 의 미구현 메서드가 많아 스텁이 길어진다. Mockito 가 이미
`spring-boot-starter-test` 에 있으므로 `Mockito.mock(McpExchangeRepository.class)` +
`doThrow(...)` 로 대체해도 좋다. 기존 테스트가 Mockito 를 쓰는지 먼저 확인한다 —
`grep -rl "Mockito\|@Mock" modules/*/src/test` 로 관례를 따른다.

- [ ] **Step 5: 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :modules:persistence:test`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add modules/persistence modules/mcp app/src/main/resources/db/migration/V5__mcp_exchange.sql
git commit -m "MCP 교환 감사로그 영속화

V5 마이그레이션과 JPA 어댑터. payload 를 jsonb 가 아니라 TEXT 로 두는
이유는 저장 대상이 JSON-RPC 원문이고 jsonb 는 키 순서를 보존하지 않아
원문 대조가 불가능해지기 때문이다. 감사 기록의 목적은 질의가 아니라 재현이다.

REQUIRES_NEW 로 별도 트랜잭션에 쓴다. 호출부 트랜잭션에 얹으면 실행이
실패해 롤백될 때 무엇을 불렀다가 실패했는지가 함께 사라진다.

기록 실패는 삼킨다 - LlmCallRecorder 와 같은 규약이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: `POST /api/v1/design` 과 Spring 배선

**Files:**
- Create: `app/src/main/java/org/hanbat/ses/app/config/McpConfiguration.java`
- Create: `app/src/main/java/org/hanbat/ses/app/config/TopologyConfiguration.java`
- Create: `modules/api/src/main/java/org/hanbat/ses/api/dto/DesignRequest.java`
- Create: `modules/api/src/main/java/org/hanbat/ses/api/dto/DesignView.java`
- Create: `modules/api/src/main/java/org/hanbat/ses/api/controller/DesignController.java`
- Modify: `modules/api/build.gradle.kts` (`api(project(":modules:design"))`)
- Modify: `modules/api/src/main/java/org/hanbat/ses/api/dto/SessionResponse.java` (`design` 필드 추가)
- Modify: `modules/api/src/main/java/org/hanbat/ses/api/service/SessionFacade.java` (`createFromDesign(String, DesignResult, LlmProperties)`)
- Modify: `modules/api/src/main/java/org/hanbat/ses/api/error/GlobalExceptionHandler.java` (`DesignFailedException`)
- Modify: `app/src/main/java/org/hanbat/ses/app/...` 시드 로더 (`queue.models.json` 등록)
- Modify: `app/src/main/resources/application.yml`, `application-memory.yml` (`mcp.*`)
- Test: `modules/api/src/test/java/org/hanbat/ses/api/controller/DesignControllerTest.java`
- Test: `app/src/test/java/org/hanbat/ses/app/DesignE2eTest.java`

**Interfaces:**
- Consumes: Task 7 `DesignService`·`DesignResult`·`DesignFailedException`·`DesignStage`,
  Task 1 `McpServerConfig`·`McpTimeouts`·`McpExchangeLog`,
  Task 7 `McpClientFactory`(`modules:mcp`), Task 2 `TopologyContractRegistry`,
  Task 4 `CompiledDesign.RoleContract`, 기존 `SessionFacade`·`SessionResponse`·`LlmProperties`
- Produces: `POST /api/v1/design`, `DesignView`, `SessionResponse.design` 필드

**`SessionResponse` 에 필드 하나만 더한다.** 이미 `sesSnapshot`·`provenance`·`candidates` 가
있으므로 `design` 은 그들이 담지 않는 것만 담는다 — 식별자, 제목, 가정, 계약 id, 모델 출처,
LLM 출처(스펙 §5.2).

**이 파일에는 미커밋 변경이 있다**(스펙 §3.4). 착수 시 현재 필드 목록을 먼저 읽고 마지막에
`design` 을 덧붙인다. record 생성자 호출 지점이 `SessionFacade.toResponse(...)` 두 곳과
`CHOOSE_TEMPLATE` 분기 한 곳이다.

- [ ] **Step 1: DTO 를 만든다**

`DesignRequest.java`:

```java
package org.hanbat.ses.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * @param contractId 쓸 토폴로지 계약. 비워 두면 유일한 계약을 쓴다 —
 *                   계약이 둘 이상이 되면 이 필드가 필수가 된다.
 */
public record DesignRequest(
        @NotBlank(message = "요청문이 필요합니다.") String request,
        String contractId
) {
}
```

`DesignView.java`:

```java
package org.hanbat.ses.api.dto;

import java.util.List;

/**
 * 무엇이 조립되었는지 — sesSnapshot 이 담지 않는 것만 담는다.
 *
 * <p>provenance 를 여기 중첩하는 이유는 SessionResponse.provenance 가 슬롯 단위 출처
 * (ProvenanceView 목록)라서다. 구조 초안의 출처는 슬롯이 아니라 세션 단위 사실이므로
 * 최상위 형제로 두면 같은 이름이 두 가지를 뜻하게 된다.
 */
public record DesignView(
        String templateId,
        String sesDefinitionId,
        String title,
        List<String> assumptions,
        String topologyContractId,
        List<ModelOrigin> models,
        DraftProvenance provenance
) {

    public record ModelOrigin(String modelId, String role, String origin) {
    }

    public record DraftProvenance(String llmProvider, String model, double temperature,
                                  Long seed, boolean schemaConstrained) {
    }
}
```

- [ ] **Step 2: `SessionResponse` 에 `design` 을 추가한다**

record 컴포넌트 목록 **끝**에 추가한다:

```java
        List<ProvenanceView> provenance,
        DesignView design
```

`SessionFacade` 의 생성자 호출 세 곳에 `null` 을 덧붙인다 (`CHOOSE_TEMPLATE` 분기,
`toResponse(...)`, 그 외). `design` 은 `POST /api/v1/design` 응답에만 채워진다 —
`jackson.default-property-inclusion: non_null` 설정이 이미 있어 다른 응답에서는
필드 자체가 나타나지 않는다.

- [ ] **Step 3: `SessionFacade` 에 진입점을 만든다**

```java
    /**
     * 설계 결과로 세션을 열고 첫 질문을 낸다 — 스펙 §5.1의 7단계.
     *
     * <p>DesignService 가 세션을 만들지 않는 이유는 dialogue 계층에 의존하게 되기
     * 때문이다. dialogue 와 scenario 를 아는 유일한 곳이 이 클래스라는 기존 규칙을 지킨다.
     */
    public SessionResponse createFromDesign(String request, DesignResult result,
                                            LlmProperties llm) {
        SessionResponse base = create(request, result.design().template().id());

        return new SessionResponse(
                base.sessionId(), base.phase(), base.outcome(), base.turn(),
                base.questions(), base.progress(), base.derivedFrom(), base.issues(),
                base.sesSnapshot(), base.scenarioId(), base.canUndo(),
                base.candidates(), base.provenance(),
                designView(result, llm));
    }

    private DesignView designView(DesignResult result, LlmProperties llm) {
        List<DesignView.ModelOrigin> models = result.design().contracts().stream()
                .map(rc -> new DesignView.ModelOrigin(
                        rc.contract().modelId(), rc.role(), rc.contract().origin()))
                .toList();

        return new DesignView(
                result.design().template().id(),
                result.design().ses().id(),
                result.draft().title(),
                result.draft().assumptions(),
                result.contractId(),
                models,
                new DesignView.DraftProvenance(
                        llm.effectiveProvider().name().toLowerCase().replace('_', '-'),
                        llm.modelFor(Purpose.STRUCTURE_DRAFT),
                        llm.temperatureFor(Purpose.STRUCTURE_DRAFT),
                        llm.seed(),
                        llm.schemaConstrained(Purpose.STRUCTURE_DRAFT)));
    }
```

`CompiledDesign.RoleContract` 가 역할을 함께 들고 있으므로(Task 4) 인덱스 대응에 기대지
않는다. `contractId` 는 Task 7의 `DesignResult` 가 담아 온다.

`create(...)` 에 초안 제목이 아니라 **원래 요청문**을 넘기는 것이 중요하다. 기존
`SlotExtractor`(LLM 슬롯 일괄 추출)가 원문에서 값을 뽑을 기회를 가져야 한다. 제목을
넘기면 "단일 FIFO 대기행렬"에서 수치를 찾게 되고, 사용자가 요청문에 "작업 5건"이라고
썼어도 그 값이 버려져 불필요한 질문이 하나 늘어난다.

- [ ] **Step 4: 컨트롤러를 만든다**

```java
package org.hanbat.ses.api.controller;

import jakarta.validation.Valid;

import org.hanbat.ses.api.dto.DesignRequest;
import org.hanbat.ses.api.dto.SessionResponse;
import org.hanbat.ses.api.service.SessionFacade;
import org.hanbat.ses.design.DesignResult;
import org.hanbat.ses.design.DesignService;
import org.hanbat.ses.llm.gateway.LlmProperties;
import org.hanbat.ses.template.topology.TopologyContractRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 요청문 하나로 설계·등록·세션 생성까지.
 *
 * <p>기존 POST /api/v1/sessions 는 템플릿이 이미 등록되어 있어야 한다. 이 엔드포인트가
 * 그 앞단을 담당한다. 이후 흐름(answers, scenario, runs)은 기존 API 그대로다.
 */
@RestController
@RequestMapping("/api/v1/design")
public class DesignController {

    private final DesignService designs;
    private final SessionFacade sessions;
    private final TopologyContractRegistry contracts;
    private final LlmProperties llm;

    public DesignController(DesignService designs, SessionFacade sessions,
                            TopologyContractRegistry contracts, LlmProperties llm) {
        this.designs = designs;
        this.sessions = sessions;
        this.contracts = contracts;
        this.llm = llm;
    }

    @PostMapping
    public ResponseEntity<SessionResponse> design(@Valid @RequestBody DesignRequest body) {
        String contractId = body.contractId() == null || body.contractId().isBlank()
                ? onlyContractId()
                : body.contractId();

        DesignResult result = designs.design(body.request(), contractId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sessions.createFromDesign(body.request(), result, llm));
    }

    /**
     * 계약을 지정하지 않았을 때.
     *
     * <p>계약이 하나뿐인 지금은 그것을 쓴다. 둘 이상이 되면 여기서 실패시켜
     * 클라이언트가 명시하게 만든다 — 임의로 고르면 사용자가 요청하지 않은 구조가 나온다.
     */
    private String onlyContractId() {
        var all = contracts.findAll();
        if (all.size() != 1) {
            throw new IllegalArgumentException("토폴로지 계약이 " + all.size()
                    + "개 등록되어 있습니다. contractId 를 지정하세요: "
                    + all.stream().map(c -> c.contractId()).toList());
        }
        return all.get(0).contractId();
    }
}
```

- [ ] **Step 5: 예외를 HTTP 상태로 매핑한다**

`GlobalExceptionHandler` 에 핸들러를 추가한다:

```java
    /**
     * 설계 단계 실패 — 어느 단계에서 막혔는지가 상태를 정한다 (스펙 §5.1).
     *
     * <p>COMPILE 이 500 인 이유는 검증을 통과한 입력에서 컴파일이 실패하면 그것은
     * 사용자 입력 문제가 아니라 컴파일러 결함이라서다.
     */
    @ExceptionHandler(DesignFailedException.class)
    public ResponseEntity<Map<String, Object>> designFailed(DesignFailedException e) {
        HttpStatus status = switch (e.stage()) {
            case DRAFT -> HttpStatus.SERVICE_UNAVAILABLE;
            case DRAFT_VALIDATION, MODEL_CONTRACT -> HttpStatus.UNPROCESSABLE_ENTITY;
            case MODEL_LOOKUP -> HttpStatus.BAD_GATEWAY;
            case COMPILE -> HttpStatus.INTERNAL_SERVER_ERROR;
            case REGISTER -> e.getMessage().contains("이미 등록된")
                    ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(Map.of(
                "stage", e.stage().name(),
                "message", e.getMessage(),
                "issues", e.issues().stream().map(IssueView::of).toList()));
    }
```

- [ ] **Step 6: Spring 배선을 만든다**

`TopologyConfiguration.java`:

```java
package org.hanbat.ses.app.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.hanbat.ses.template.topology.TopologyContract;
import org.hanbat.ses.template.topology.TopologyContractRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/** 시드 리소스에서 토폴로지 계약을 읽어 부팅 시 한 번 채운다. */
@Configuration
public class TopologyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TopologyConfiguration.class);
    private static final List<String> SEEDS = List.of("seed/queue.topology.json");

    @Bean
    public TopologyContractRegistry topologyContractRegistry() {
        List<TopologyContract> contracts = new ArrayList<>();
        for (String path : SEEDS) {
            try (InputStream in = new ClassPathResource(path).getInputStream()) {
                TopologyContract contract = TopologyContractRegistry.load(in);
                contracts.add(contract);
                log.info("토폴로지 계약 등록: {} (노드 {}개)",
                        contract.contractId(), contract.nodeCount());
            } catch (IOException e) {
                throw new IllegalStateException(
                        "토폴로지 계약을 읽지 못했습니다: " + path, e);
            }
        }
        return new TopologyContractRegistry(contracts);
    }
}
```

`McpConfiguration.java`:

```java
package org.hanbat.ses.app.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.design.McpModelLookup;
import org.hanbat.ses.design.ModelLookup;
import org.hanbat.ses.mcp.McpClientFactory;
import org.hanbat.ses.mcp.McpExchangeLog;
import org.hanbat.ses.mcp.McpServerConfig;
import org.hanbat.ses.mcp.McpTimeouts;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 서버 설정.
 *
 * <p>서버 커맨드를 코드에 박지 않는다. ExecutionSpec.mcpTools 가 이미 도구 목록을
 * 데이터로 들고 있으므로, 실행 경로도 설정에서 와야 둘이 어긋나지 않는다.
 */
@Configuration
public class McpConfiguration {

    @Bean
    public McpClientFactory mcpClientFactory(
            @Value("${mcp.servers.queue-models.command:python,mcp/queue-models/mcp_server.py}")
            String command,
            @Value("${mcp.servers.queue-models.tools:"
                    + "search_models,describe_model,provision_model,run_processor}")
            String tools,
            @Value("${mcp.servers.queue-models.timeout-ms.default:30000}") long defaultMs,
            @Value("${mcp.servers.queue-models.timeout-ms.provision:600000}") long provisionMs,
            @Value("${mcp.servers.queue-models.timeout-ms.run:60000}") long runMs,
            @Value("${mcp.max-concurrent:1}") int maxConcurrent,
            ObjectProvider<McpExchangeLog> logs) {

        McpServerConfig queueModels = new McpServerConfig("queue-models",
                split(command), split(tools),
                new McpTimeouts(defaultMs, provisionMs, runMs));

        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        servers.put(queueModels.name(), queueModels);

        // memory 프로파일에는 JPA 어댑터가 없다. LlmConfiguration 이
        // ObjectProvider<LlmCallRecorder> 를 쓰는 방식과 같다.
        return new McpClientFactory(servers,
                logs.getIfAvailable(McpExchangeLog::noop), maxConcurrent);
    }

    @Bean
    public ModelLookup modelLookup(McpClientFactory factory) {
        return new McpModelLookup(factory);
    }

    private static List<String> split(String csv) {
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
```

`application.yml` 에 추가:

```yaml
mcp:
  # 동시 실행 상한. provision_model 이 Docker 빌드를 병렬로 여러 번 돌리는 것을 막는다.
  max-concurrent: 1
  servers:
    queue-models:
      # 서버 프로세스 커맨드. 저장소 루트 기준 상대 경로다.
      command: python,mcp/queue-models/mcp_server.py
      # 호출을 허용하는 도구. 서버가 더 노출해도 여기 없는 것은 부르지 않는다.
      tools: search_models,describe_model,provision_model,run_processor
      timeout-ms:
        default: 30000
        # Docker 빌드와 PyPI 다운로드를 포함한다.
        provision: 600000
        run: 60000
```

- [ ] **Step 7: 시드 로더에 `queue.models.json` 을 추가한다**

기존 시드 로더를 찾는다:

```bash
grep -rn "resort.models.json\|seed-demo-domain" app/src/main/java
```

찾은 로더의 모델 시드 목록에 `seed/queue.models.json` 을 추가한다.
`queue-local-source`·`queue-local-sink` 는 `AtomicModelFactory` 가 없으므로
`AssetController.registerModel` 과 같은 경고가 로그에 남는다 — **기대 동작이다.**
이 두 모델은 내부 DEVS 엔진이 아니라 `McpBatchScenarioExecutor` 가 해석한다.
로더가 경고를 오류로 취급하면 그 조건에서 이 두 모델을 예외로 둔다.

- [ ] **Step 8: 컨트롤러 테스트를 쓴다**

`DesignControllerTest.java` — `@WebMvcTest` 로 `DesignService` 를 목으로 두고
단계별 HTTP 상태를 확인한다:

```java
    @Test
    @DisplayName("성공하면 201 과 첫 질문, design 필드를 낸다")
    void created() throws Exception {
        mvc.perform(post("/api/v1/design")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":\"대기행렬을 만들어줘\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.questions[0].slot").value("arrivalInterval"))
                .andExpect(jsonPath("$.design.templateId").exists())
                .andExpect(jsonPath("$.design.topologyContractId")
                        .value("fifo-single-server"))
                .andExpect(jsonPath("$.design.provenance.seed").value(42))
                .andExpect(jsonPath("$.design.provenance.schemaConstrained").value(true))
                .andExpect(jsonPath("$.design.models[1].origin").value("mcp"));
    }

    @Test
    @DisplayName("요청문이 비면 400")
    void blankRequest() throws Exception {
        mvc.perform(post("/api/v1/design")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
```

단계별 상태 매핑은 파라미터화 테스트로 여섯 단계를 한 번에 확인한다:

| 단계 | 기대 상태 |
|---|---|
| `DRAFT` | 503 |
| `DRAFT_VALIDATION` | 422 |
| `MODEL_LOOKUP` | 502 |
| `MODEL_CONTRACT` | 422 |
| `COMPILE` | 500 |
| `REGISTER` (일반) | 422 |
| `REGISTER` ("이미 등록된" 포함) | 409 |

- [ ] **Step 9: 앱이 뜨는지 확인한다**

Run: `./gradlew :modules:api:test :app:test`
Expected: PASS. 컨텍스트 로딩이 새 빈 3개(`TopologyContractRegistry`,
`McpClientFactory`, `ModelLookup`)로 깨지지 않아야 한다.

`modules/api/build.gradle.kts` 에 `api(project(":modules:design"))` 를 추가한다.
`app/build.gradle.kts` 에는 `implementation(project(":modules:design"))` 를 추가한다.

- [ ] **Step 10: 커밋**

```bash
git add modules/api app
git commit -m "POST /api/v1/design 신설

요청문 하나로 LLM 초안, 초안 검증, MCP 모델 조회, 계약 검증, 컴파일,
등록, 세션 생성, 첫 질문까지. 이후 흐름은 기존 API 그대로이고 기존
엔드포인트는 하나도 바뀌지 않는다.

SessionResponse 에 design 필드 하나만 더한다. sesSnapshot, provenance,
candidates 가 이미 있으므로 그들이 담지 않는 것만 담는다 - 식별자, 제목,
가정, 계약 id, 모델 출처, LLM 출처.

출처를 남기는 이유는 연구용이라서다. 어떤 모델이 어떤 온도/시드로 어떤
구조를 제안했는지 기록되지 않으면 결과를 재현할 수 없다.

DesignStage 를 HTTP 상태로 매핑한다. COMPILE 이 500 인 이유는 검증을
통과한 입력에서 컴파일이 실패하면 컴파일러 결함이기 때문이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 11: MCP 서버 승격과 골든 통합 테스트

**Files:**
- Move: `experiments/queue-mcp/mcp_server.py` → `mcp/queue-models/mcp_server.py`
- Move: `experiments/queue-mcp/catalog.json` → `mcp/queue-models/catalog.json`
- Move: `experiments/queue-mcp/processor/` → `mcp/queue-models/processor/`
- Create: `mcp/queue-models/README.md`
- Modify: `experiments/queue-mcp/README.md` (역할 축소 명시)
- Modify: `experiments/queue-mcp/verify_live.py` (새 경로)
- Delete: `experiments/queue-mcp/experiment.py`, `test_experiment.py`, `run-demo.ps1`, `__pycache__/`
- Create: `app/src/test/java/org/hanbat/ses/app/QueueMcpGoldenIT.java`
- Modify: `README.md` (새 엔드포인트와 경로)

**Interfaces:**
- Consumes: Task 1–10 전부
- Produces: 골든 회귀 테스트, 승격된 MCP 서버 경로

**이 태스크가 승격을 완료한다.** `experiment.py` 465줄이 하는 일이 Task 1–10에 전부
들어갔으므로 그 파일을 지운다. 남기면 두 경로가 갈라지고, 어느 쪽이 진짜인지 알 수 없게 된다.

**Docker 가 필요하다.** 2026-09-09 실측 시점에 이 머신의 Docker Desktop 이 꺼져 있었다
(스펙 §3.3). 이 태스크를 시작하기 전에 띄운다:

```bash
docker version --format '{{.Server.Version}}'
```

- [ ] **Step 1: MCP 서버를 정식 경로로 옮긴다**

```bash
mkdir -p mcp/queue-models
git mv experiments/queue-mcp/mcp_server.py mcp/queue-models/mcp_server.py
git mv experiments/queue-mcp/catalog.json mcp/queue-models/catalog.json
git mv experiments/queue-mcp/processor mcp/queue-models/processor
rm -rf experiments/queue-mcp/__pycache__
```

`mcp_server.py` 는 `ROOT = Path(__file__).resolve().parent` 로 자기 위치를 잡고
`catalog.json` 과 `processor/` 를 그 기준으로 찾는다. 함께 옮기면 코드 수정이 없다.
`.gitignore` 에 `experiments/queue-mcp/.gitignore` 가 있으므로 `mcp/queue-models/.gitignore`
로 필요한 항목만 옮긴다.

- [ ] **Step 2: 실험 폴더의 역할을 줄인다**

```bash
git rm experiments/queue-mcp/experiment.py experiments/queue-mcp/test_experiment.py \
       experiments/queue-mcp/run-demo.ps1
```

`experiments/queue-mcp/README.md` 의 맨 앞에 다음을 넣는다:

```markdown
> **이 실험은 백엔드 정식 경로로 승격되었다.** 파이프라인은 이제
> `POST /api/v1/design` → `POST /api/v1/sessions/{id}/answers` →
> `POST /api/v1/sessions/{id}/scenario` → `POST /api/v1/scenarios/{id}/runs` 가 수행한다.
> MCP 서버는 `mcp/queue-models/` 로 옮겼다.
>
> 이 폴더에는 라이브 검증 스크립트(`verify_live.py`)만 남아 있다. 파이프라인 자체의
> 회귀는 `app/src/test/java/org/hanbat/ses/app/QueueMcpGoldenIT.java` 가 확인한다.
> 설계 근거는 `docs/superpowers/specs/2026-09-09-mcp-orchestration-promotion-design.md` 를 본다.
```

기존 본문 중 `experiment.py design/answer/assemble/run` 사용법 절은 새 엔드포인트 호출로
바꾸거나 지운다. **골든 검증값 표(도착 2분·처리 3분·작업 5건 → 평균 대기 2, 최대 4, t=17)와
"실험 범위와 구분" 절은 남긴다** — 그 두 절이 이 경로의 한계를 유일하게 문서화하고 있다.

`mcp/queue-models/README.md` 를 새로 만든다:

```markdown
# 대기행렬 외부 모델 MCP 서버

`external-simpy-fifo` 하나를 노출하는 stdio MCP 서버(2025-11-25). 백엔드의
`McpClientFactory` 가 요청마다 이 프로세스를 띄우고 닫는다.

| 도구 | 부작용 |
|---|---|
| `search_models` | 없음 |
| `describe_model` | 없음 |
| `provision_model` | Docker 빌드 + PyPI 에서 SimPy 4.1.1 |
| `run_processor` | 격리 컨테이너 실행 |

## 보안 경계

Java 는 Docker 를 직접 부르지 않는다. 다음은 전부 이 서버 안에 있다.

- 실행 허용목록 — `catalog.json` 의 `modelId` 1건
- 이미지 라벨 검증 — `ses.experiment=queue-mcp`
- 페이로드 200KB 상한
- 컨테이너 격리 — `--network=none --read-only --cap-drop=ALL`
  `--security-opt=no-new-privileges --memory=128m --cpus=1 --pids-limit=32`
- 타임아웃 시 `docker rm --force`
- LLM 이 제안한 URL·셸 코드·Docker 명령은 실행하지 않는다

JSON-RPC stdout 에는 프로토콜 메시지만 나간다. Docker 출력은 포착해서 흘리지 않는다.

## 직접 실행

```bash
python mcp/queue-models/mcp_server.py
```

stdin 으로 JSON-RPC 를 한 줄씩 넣는다. 백엔드가 부르는 순서는
`initialize` → `notifications/initialized` → `tools/list` → `tools/call` 이다.
```

- [ ] **Step 3: 골든 통합 테스트를 쓴다**

`app/src/test/java/org/hanbat/ses/app/QueueMcpGoldenIT.java`:

```java
package org.hanbat.ses.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * design -> answers -> scenario -> runs 전 구간 골든 회귀.
 *
 * <p>experiment.py 의 demo 명령이 확인했던 것을 그대로 확인한다. 이것이 승격의
 * 근거다 — 같은 입력에 같은 결과가 나오지 않으면 옮긴 것이 아니라 다시 만든 것이다.
 *
 * <p>Docker Desktop 과 Ollama 가 필요하다. 기본 test 태스크에서 제외되며
 * ./gradlew :app:integrationTest 로 돌린다.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"memory", "ollama"})
class QueueMcpGoldenIT {

    private static final String REQUEST =
            "작업들이 한 처리기 앞에서 기다리는 대기행렬을 만들고 평균 대기시간을 보고 싶어";

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("골든 입력이 완료 5건, 평균 대기 2분, 최대 4분, 종료 17분을 낸다")
    @SuppressWarnings("unchecked")
    void goldenPipeline() {
        // 1. 설계 — LLM 초안, MCP 조회, 컴파일, 등록, 세션 생성
        Map<String, Object> design = rest.postForObject("/api/v1/design",
                Map.of("request", REQUEST), Map.class);

        assertThat(design).isNotNull();
        String sessionId = (String) design.get("sessionId");
        Map<String, Object> designView = (Map<String, Object>) design.get("design");
        assertThat(designView).containsEntry("topologyContractId", "fifo-single-server");

        // 2. 질문에 답한다. 슬롯 순서는 LLM 의 노드 id 와 SES 정렬에 따라 달라질 수 있으므로
        //    나온 질문에 맞춰 값을 고른다.
        Map<String, Object> fixture = Map.of(
                "arrivalInterval", 2, "serviceTime", 3, "totalJobs", 5);

        Map<String, Object> turn = design;
        for (int i = 0; i < 8 && !"COMPLETE".equals(turn.get("outcome")); i++) {
            var questions = (java.util.List<Map<String, Object>>) turn.get("questions");
            assertThat(questions)
                    .as("완료되지 않았는데 질문이 없다")
                    .isNotEmpty();

            Map<String, Object> answers = new java.util.LinkedHashMap<>();
            questions.forEach(q -> {
                String slot = (String) q.get("slot");
                assertThat(fixture).containsKey(slot);
                answers.put(slot, fixture.get(slot));
            });

            turn = rest.postForObject("/api/v1/sessions/" + sessionId + "/answers",
                    Map.of("answers", answers), Map.class);
        }
        assertThat(turn.get("outcome")).isEqualTo("COMPLETE");

        // 3. 시나리오 확정
        Map<String, Object> scenario = rest.postForObject(
                "/api/v1/sessions/" + sessionId + "/scenario", Map.of(), Map.class);
        String scenarioId = (String) scenario.get("scenarioId");
        assertThat((Map<String, Object>) scenario.get("simConfig"))
                .containsEntry("engine", "queue-mcp-batch");

        // 4. 실행 — MCP provision_model + run_processor
        Map<String, Object> run = rest.postForObject(
                "/api/v1/scenarios/" + scenarioId + "/runs", Map.of(), Map.class);

        Map<String, Object> result = awaitSucceeded((String) run.get("runId"));
        Map<String, Object> payload = (Map<String, Object>) result.get("result");
        Map<String, Object> raw = (Map<String, Object>) payload.get("raw");

        // 골든값 — experiment.py 의 verification.json 과 같다.
        assertThat(number(payload.get("endTime"))).isEqualTo(17.0, Offset.offset(1e-7));
        assertThat(raw).hasEntrySatisfying(keyEndingWith(raw, ".jobsCompleted"),
                v -> assertThat(number(v)).isEqualTo(5.0));
        assertThat(number(raw.get(keyEndingWith(raw, ".jobsLost")))).isEqualTo(0.0);
        assertThat(number(raw.get(keyEndingWith(raw, ".meanWait"))))
                .isEqualTo(2.0, Offset.offset(1e-7));
        assertThat(number(raw.get(keyEndingWith(raw, ".maxWait"))))
                .isEqualTo(4.0, Offset.offset(1e-7));
        assertThat(number(raw.get("utilizationFromTimeZero")))
                .isEqualTo(15.0 / 17.0, Offset.offset(1e-9));

        // 출처 — 이것이 없으면 결과를 재현할 수 없다.
        assertThat(raw.get("libraryVersion")).isEqualTo("4.1.1");
        assertThat((String) raw.get("imageId")).startsWith("sha256:");
        assertThat((String) raw.get("manifestSha256")).hasSize(64);
        assertThat(raw).containsEntry("contractId", "fifo-single-server");
    }

    @Test
    @DisplayName("범위를 벗어난 요청은 422 와 사유를 낸다")
    void unsupportedRequestIsRejected() {
        var response = rest.postForEntity("/api/v1/design",
                Map.of("request", "서버 3대가 병렬로 처리하고 우선순위가 있는 대기행렬"),
                Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).containsEntry("stage", "DRAFT_VALIDATION");
    }

    @Test
    @DisplayName("같은 요청 두 번이 구조적으로 같은 설계를 낸다")
    @SuppressWarnings("unchecked")
    void structurallyIdenticalDesigns() {
        String first = fingerprint(rest.postForObject("/api/v1/design",
                Map.of("request", REQUEST), Map.class));
        String second = fingerprint(rest.postForObject("/api/v1/design",
                Map.of("request", REQUEST), Map.class));

        assertThat(first).isEqualTo(second);
    }

    /**
     * 구조 지문 — 역할 집합, 모델 참조, 슬롯 이름만 본다.
     *
     * <p>설계 id 는 UUID 라 매번 다르고 노드 이름은 LLM 이 정한다. 시드를 고정했으므로
     * 이름까지 같을 것으로 기대하지만, 그것을 단정하면 모델 버전이 바뀔 때 이 테스트가
     * 구조 회귀가 아니라 이름 변화로 깨진다.
     */
    @SuppressWarnings("unchecked")
    private static String fingerprint(Map<String, Object> design) {
        Map<String, Object> view = (Map<String, Object>) design.get("design");
        var models = (java.util.List<Map<String, Object>>) view.get("models");
        var questions = (java.util.List<Map<String, Object>>) design.get("questions");

        return models.stream()
                .map(m -> m.get("role") + ":" + m.get("modelId")).sorted().toList()
                + "|" + questions.stream().map(q -> (String) q.get("slot")).sorted().toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitSucceeded(String runId) {
        for (int i = 0; i < 120; i++) {
            Map<String, Object> run = rest.getForObject("/api/v1/runs/" + runId, Map.class);
            String status = (String) run.get("status");
            if ("SUCCEEDED".equals(status)) {
                return run;
            }
            if ("FAILED".equals(status)) {
                throw new AssertionError("실행이 실패했습니다: " + run.get("error"));
            }
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("대기가 중단되었습니다", e);
            }
        }
        throw new AssertionError("실행이 10분 안에 끝나지 않았습니다: " + runId);
    }

    private static String keyEndingWith(Map<String, Object> map, String suffix) {
        return map.keySet().stream().filter(k -> k.endsWith(suffix)).findFirst()
                .orElseThrow(() -> new AssertionError(
                        suffix + " 로 끝나는 통계 키가 없습니다. 있는 키: " + map.keySet()));
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }
}
```

`RunController` 의 `GET /api/v1/runs/{id}` 응답 필드 이름(`status`, `result`, `error`)과
`ScenarioResponse` 의 `simConfig` 키 이름을 먼저 확인한다 — `SessionFacade.toScenarioResponse`
가 `Map.of("engine", ...)` 로 만들고 있으므로 `simConfig` 안에 `engine` 이 있다.

출처 증거(`imageId`, `manifestSha256`)는 `ResultFormatter` 의 `RAW` 섹션으로만 나온다.
Task 4의 `SesCompiler` 가 `OutputSection.RAW` 를 담으므로 응답에 `raw` 가 있다.

- [ ] **Step 4: Docker 와 Ollama 를 띄우고 테스트를 돌린다**

```bash
docker version --format '{{.Server.Version}}'
curl -s -m 4 http://localhost:11434/api/version
```

Run: `./gradlew :app:integrationTest --tests '*QueueMcpGoldenIT'`
Expected: 3개 테스트 PASS

첫 실행은 `provision_model` 이 Docker 이미지를 빌드하므로 수 분 걸린다. 두 번째부터는
이미지가 있어 빠르다.

**실패 시 확인 순서.** `raw` 가 응답에 없으면 Task 4의 `OutputSpec` 에
`OutputSection.RAW` 가 빠진 것이다.
`endTime` 이 17이 아니면 첫 도착 시각을 t=0으로 잡은 것이다 — 첫 도착은 `arrivalInterval`
시점이다. `outcome` 이 `COMPLETE` 가 되지 않으면 슬롯 이름이 `fixture` 의 키와 다른 것이므로
`SesCompiler` 가 파라미터 이름을 그대로 쓰는지 확인한다.

- [ ] **Step 5: `verify_live.py` 를 새 경로에 맞춘다**

`verify_live.py` 가 `output/queue-mcp/<dir>` 의 `simulator.json`·`result.json` 을 읽는데,
그 파일들은 이제 생성되지 않는다. 두 갈래 중 하나를 고른다.

1. **지운다** — 골든 IT 가 같은 것을 확인하므로 중복이다. 이쪽을 권한다.
2. 새 엔드포인트를 호출해 응답을 검증하도록 다시 쓴다.

지우는 경우:

```bash
git rm experiments/queue-mcp/verify_live.py
```

그러면 `experiments/queue-mcp/` 에 `README.md` 만 남는다. 폴더를 지우고 README 내용을
`mcp/queue-models/README.md` 의 "실험 범위와 구분" 절로 합치는 것도 좋다 — 그 절이
이 경로의 한계를 문서화하는 유일한 곳이므로 **어디로 가든 남아야 한다.**

- [ ] **Step 6: 저장소 README 를 갱신한다**

`README.md` 에서 다음을 고친다.

- API 목록에 `POST /api/v1/design` 추가 — 요청/응답 예시와 단계별 상태 코드
- 모듈 목록에 `modules:mcp`, `modules:design` 추가
- `experiments/queue-mcp` 를 언급하는 곳을 `mcp/queue-models` 로
- 실행 요구사항에 Docker Desktop 과 Ollama 를 명시
- `queue-mcp-batch` 엔진과 `devs-internal` 엔진의 차이 한 단락

`README.md` 에는 미커밋 변경(+100줄)이 있다(스펙 §3.4). 착수 시 현재 내용을 먼저 읽는다.

- [ ] **Step 7: 전체 빌드와 전체 단위 테스트**

```bash
./gradlew clean build
```

Expected: 컴파일 + 전체 단위 테스트 PASS. `integration`·`live` 태그는 제외된다.

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "queue-mcp 실험을 정식 경로로 승격 완료

MCP 서버를 mcp/queue-models/ 로 옮기고 experiment.py 465줄을 지운다.
그 파일이 하던 일은 Task 1~10 에 전부 들어갔다. 남기면 두 경로가 갈라져
어느 쪽이 진짜인지 알 수 없게 된다.

QueueMcpGoldenIT 가 design -> answers -> scenario -> runs 전 구간을 돌려
완료 5건, 손실 0건, 평균 대기 2분, 최대 4분, 종료 17분, 이용률 15/17 을
확인한다. experiment.py 의 demo 가 확인했던 것과 같은 값이다 - 같은
입력에 같은 결과가 나오지 않으면 옮긴 것이 아니라 다시 만든 것이다.

출처 증거(imageId, libraryVersion, manifestSha256, contractId)도 함께
확인한다. 그래서 SesCompiler 가 OutputSection.RAW 를 담는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## 실행 후 확인 목록

계획을 끝까지 실행한 뒤 다음이 모두 참이어야 한다.

- [ ] `./gradlew clean build` 통과 (단위 테스트 전부)
- [ ] `./gradlew :app:integrationTest --tests '*QueueMcpGoldenIT'` 통과 (Docker + Ollama)
- [ ] 기존 시드 템플릿 3건(`ev-charging`, `resort-simulation`, `resort-capacity-review`)이
      `devs-internal` 엔진으로 그대로 동작 — `ResortDialogueE2eTest` 통과
- [ ] `grep -rn "experiment.py" .` 결과가 문서의 역사 서술뿐
- [ ] `POST /api/v1/sessions`, `/answers`, `/scenario`, `/runs`, `/templates`,
      `/ses-definitions`, `/models` 응답이 `design` 필드 추가 외에는 변경 없음
- [ ] `modules:scenario` 가 `modules:llm` 을 의존하지 않음 —
      `./gradlew :modules:scenario:dependencies --configuration compileClasspath | grep -c llm` 이 0
- [ ] `llm.seed` 를 설정하지 않은 프로파일(`application.yml` 기본)로도 앱이 뜸
