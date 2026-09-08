# SES 기반 LLM 시나리오 생성 서버 — 구현 계획서

> 서브태스크 템플릿으로 설계도를 구성하고, 요청에 필요한 모델을 SES 트리로 정리하며,
> 입력이 부족하면 되묻고 충분하면 시뮬레이션 시나리오를 생성하는 Spring 서버.

| 항목 | 결정 |
|---|---|
| 언어 | Java 21 (record, sealed interface, pattern matching switch) |
| 프레임워크 | Spring Boot 3.3.x, Spring Web MVC, Spring Data JPA |
| 저장소 | PostgreSQL 16 + JSONB (Flyway 마이그레이션) |
| LLM 연동 | `WebClient` → Anthropic Messages API 직접 호출 |
| 시뮬레이션 | 자체 DEVS 엔진 (순수 Java, 프레임워크 무의존) |
| 빌드 | Gradle 8.x 멀티모듈 (Kotlin DSL) |
| 성격 | 연구용 프로토타입 + 확장 고려 |

---

## 1. 설계 원칙

이 프로젝트의 성패를 가르는 원칙 네 가지입니다. 이후 모든 설계 결정은 여기서 파생됩니다.

**① SES가 단일 진실 원천(Single Source of Truth)이다.**
템플릿이 슬롯 목록을 손으로 들고 있지 않습니다. SES 트리의 미결정 지점이 곧 질문 목록입니다.
- `SpecNode` 미해결 → "무엇을 고를까요?"
- `MultiAspectNode` 개수 미정 → "몇 개인가요?"
- 리프 엔티티의 빈 변수 → "값은 얼마인가요?"

템플릿은 그 질문을 *어떻게 표현하고 검증할지*만 담당합니다. 두 곳에 슬롯을 적어두면 반드시 어긋나기 때문입니다.

**② 구조 결정과 값 설정을 분리한다.**
"케이블카를 넣을까요?"(구조)와 "케이블카 정원은?"(값)은 다른 종류입니다. 구조 결정이 **먼저** 일어나고, 그 결과로 새로운 값 슬롯이 **파생**됩니다. 케이블카를 고르기 전에는 "정원" 슬롯이 존재조차 하지 않습니다.

**③ LLM은 자연어 경계에만 쓴다.**
Pruning, 검증, 완료 판정은 전부 결정론적 Java 코드입니다. LLM은 세 지점에만 개입합니다 — 템플릿 라우팅 분류, 요청문에서 슬롯 값 추출, 질문/결과 문구 다듬기. 재현성이 필요한 시뮬레이션 시스템에서 LLM이 구조를 직접 결정하게 두면 같은 입력에 다른 모델이 나옵니다.

**④ Phase 1~3은 LLM 없이 완성한다.**
결정론적 코어(SES + DEVS + 대화 상태머신)를 먼저 검증하고 LLM을 나중에 얹습니다. 반대로 하면 버그가 프롬프트 문제인지 로직 문제인지 구분할 수 없습니다.

---

## 2. 시스템 아키텍처

```
                       ┌────────────────────────────────────────┐
 HTTP  ──▶  API Layer  │  SessionController · ScenarioController │
                       │  RunController · TemplateController    │
                       └───────────────┬────────────────────────┘
                                       │
        ┌──────────────────────────────▼──────────────────────────────┐
        │                    Orchestration Layer                       │
        │  RequestRouter → SesAssembler → SlotResolver                 │
        │       → DialogueController → ValidationChain                 │
        │       → CompletionJudge → PesBuilder → ScenarioExecutor      │
        └───┬───────────────┬───────────────┬──────────────────┬───────┘
            │               │               │                  │
   ┌────────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐  ┌────────▼───────┐
   │  core-ses     │ │  template   │ │  llm        │  │  core-devs     │
   │  SES/PES 트리 │ │  템플릿 정의│ │  WebClient  │  │  Atomic/Coupled│
   │  PruningEngine│ │  Registry   │ │  Gateway    │  │  Coordinator   │
   │  (순수 Java)  │ │             │ │  (재시도)   │  │  (순수 Java)   │
   └───────────────┘ └─────────────┘ └──────┬──────┘  └────────────────┘
            │               │               │                  │
   ┌────────▼───────────────▼───────────────▼──────────────────▼───────┐
   │            persistence — PostgreSQL + JSONB                        │
   │  subtask_template · ses_definition · model_base                    │
   │  dialogue_session · scenario · simulation_run                      │
   └────────────────────────────────────────────────────────────────────┘
                                       │
                                  Anthropic API  (외부, WebClient)
```

### 처리 흐름

```
사용자 요청
   │
   ├─▶ ① RequestRouter        적용 대상 매칭 → 서브태스크 DAG 구성
   │
   ├─▶ ② SesAssembler         도메인 SES 로딩 → workingSes 초기화
   │
   ├─▶ ③ SlotResolver         workingSes 순회 → 미결정 지점 수집 ◀─────┐
   │                                                                    │
   ├─▶ ④ SlotExtractor(LLM)   요청문에서 값 일괄 추출 + 기본값 적용     │
   │                                                                    │
   ├─▶ ⑤ DialogueController   남은 슬롯만 질문 (위상순서, 배치)         │
   │           │                                                        │
   │           └─▶ PruningEngine  답변 적용 → workingSes 재작성 ────────┘
   │                              (구조 답변이 새 슬롯을 파생시키면 ③으로)
   │
   ├─▶ ⑥ ValidationChain      타입 → 교차제약 → 단위 → 구조무결성
   │
   ├─▶ ⑦ CompletionJudge      슬롯충족 ∧ 검증통과 ∧ PES 유일성
   │
   ├─▶ ⑧ PesBuilder           workingSes → PES → ModelFactory
   │
   └─▶ ⑨ ScenarioExecutor     DEVS 시뮬레이션 실행 → 결과 형식화
```

---

## 3. 모듈 구조

```
ses-scenario-server/
├─ build.gradle.kts
├─ settings.gradle.kts
├─ modules/
│  ├─ core-ses/          SES·PES 자료구조, PruningEngine, SlotResolver   [Spring 무의존]
│  ├─ core-devs/         AtomicModel, CoupledModel, Coordinator          [Spring 무의존]
│  ├─ template/          SubtaskTemplate 모델, JSON 스키마, Registry
│  ├─ dialogue/          DialogueController, 상태머신, ValidationChain
│  ├─ llm/               LlmGateway, 프롬프트, 구조화 출력 파서
│  ├─ scenario/          PesBuilder, ModelFactory, ScenarioExecutor
│  ├─ persistence/       JPA 엔티티, Repository, JSONB 컨버터
│  └─ api/               Controller, DTO, 예외 처리, OpenAPI
└─ app/                  Spring Boot 부트스트랩, 설정, Flyway
```

**`core-ses`와 `core-devs`를 Spring 무의존으로 두는 것이 중요합니다.** 단위 테스트가 밀리초 단위로 끝나고, 나중에 CLI 도구나 배치 작업에서 그대로 재사용할 수 있습니다. 이 두 모듈에는 `org.springframework` import가 하나도 들어가지 않아야 합니다.

### 의존 방향

```
api ─▶ dialogue ─▶ template ─▶ core-ses
 │         │            │
 │         ├──────▶ llm  │
 │         │            │
 └──▶ scenario ─▶ core-devs
          │
          └──▶ core-ses

persistence ◀── (api, dialogue, scenario)
```

순환 의존이 없도록 Gradle에서 `api`/`implementation` 구분을 명시합니다.

---

## 4. 도메인 모델

### 4.1 SES 트리

Java 21의 sealed interface + record 조합이 SES에 정확히 들어맞습니다. 노드 종류가 닫혀 있으므로 pattern matching switch에서 컴파일러가 누락을 잡아줍니다.

```java
package org.hanbat.ses.core.model;

public sealed interface SesNode
        permits EntityNode, AspectNode, SpecNode, MultiAspectNode {
    String id();
    String name();
}

/** 엔티티 — 트리의 실체 노드. 아래에 축(axis) 노드를 여러 개 가질 수 있다. */
public record EntityNode(
        String id,
        String name,
        List<VarDef> vars,
        List<SesNode> axes          // AspectNode | SpecNode | MultiAspectNode
) implements SesNode {}

/** Aspect — AND 분해. 모든 컴포넌트가 함께 존재한다. */
public record AspectNode(
        String id,
        String name,
        List<EntityNode> components,
        List<CouplingSpec> couplings
) implements SesNode {}

/** Specialization — OR 선택. pruning 지점. selected가 null이면 미결정. */
public record SpecNode(
        String id,
        String name,
        List<EntityNode> variants,
        String selectedVariantId    // nullable — 미결정 상태
) implements SesNode {}

/** Multi-aspect — 동일 타입 n개 복제. count가 null이면 미결정. */
public record MultiAspectNode(
        String id,
        String name,
        EntityNode prototype,
        IntRange countRange,
        Integer count               // nullable — 미결정 상태
) implements SesNode {}

public record VarDef(
        String name,
        VarType type,               // INT, DOUBLE, STRING, BOOL, ENUM
        String unit,                // "명", "m/s", "분" — 단위 일관성 검증에 사용
        Object value,               // nullable — 미충전 상태
        Object defaultValue,
        boolean inferable,          // LLM 추정 허용 여부
        Range range
) {}

public record CouplingSpec(
        CouplingKind kind,          // EIC, EOC, IC
        String fromEntity, String fromPort,
        String toEntity,   String toPort
) {}
```

`selectedVariantId`, `count`, `VarDef.value`가 `null`이라는 것이 곧 "아직 물어봐야 할 것"이라는 뜻입니다. 별도의 슬롯 목록을 유지할 필요가 없습니다.

### 4.2 PES (Pruned Entity Structure)

```java
public record PesNode(
        String entityId,
        String modelRef,                 // model_base.model_id — 리프에만 존재
        Map<String, Object> params,
        List<PesNode> children,
        List<CouplingSpec> couplings
) {}
```

### 4.3 서브태스크 템플릿

앞서 SES 트리로 정리한 7개 블록이 그대로 record 구조가 됩니다.

```java
public record SubtaskTemplate(
        String id,
        String version,
        Routing routing,                 // A. 라우팅
        List<SlotSpec> slots,            // B. 슬롯 (표현 계층만)
        DialogueControl dialogue,        // C. 대화 제어
        StructureBinding binding,        // D. 구조 바인딩
        ValidationSpec validation,       // E. 검증
        ExecutionSpec execution,         // F. 실행
        OutputSpec output,               // G. 출력
        TemplateMeta meta
) {}

public record Routing(
        List<String> triggerPatterns,
        String intentDescription,        // LLM 분류용
        int priority,
        List<Dependency> prerequisites   // 선행 서브태스크
) {}

public sealed interface SlotSpec
        permits StructuralSlot, ValueSlot, ReferenceSlot {
    String name();
    SesAnchor anchor();
    QuestionSpec question();
}

/** 구조 결정 슬롯 — 답변이 SES를 가지치기한다. */
public record StructuralSlot(
        String name,
        SesAnchor anchor,
        StructuralKind kind,             // SELECT, MULTIPLICITY, INCLUSION
        List<String> options,
        QuestionSpec question,
        List<String> dependsOn
) implements SlotSpec {}

/** 값 설정 슬롯 — 답변이 파라미터로 바인딩된다. */
public record ValueSlot(
        String name,
        SesAnchor anchor,
        VarType type,
        String unit,
        Range range,
        Object defaultValue,
        boolean inferable,
        QuestionSpec question,
        List<String> dependsOn
) implements SlotSpec {}

/** 참조 슬롯 — 선행 서브태스크의 출력을 받는다. */
public record ReferenceSlot(
        String name,
        SesAnchor anchor,
        String sourceTaskId,
        String sourceField,
        QuestionSpec question
) implements SlotSpec {}

/** 슬롯이 SES 트리의 어디에 붙는지 — 템플릿과 SES를 잇는 유일한 고리. */
public record SesAnchor(
        String entityPath,               // "리조트/이동설비"
        AxisType axis,                   // ASPECT, SPECIALIZATION, MULTI_ASPECT, VARIABLE
        String targetNodeId
) {}

public record DialogueControl(
        int maxTurns,
        int maxQuestionsPerTurn,         // 독립 슬롯 배치 질문
        UnfilledPolicy unfilledPolicy    // ASK_AGAIN, USE_DEFAULT, LLM_INFER, DEFER
) {}

public record StructureBinding(
        String sesDefinitionId,
        String rootEntity,
        Map<String, String> pruningRules,   // 슬롯값 → 선택 노드 ID
        List<CouplingSpec> extraCouplings
) {}

public record ExecutionSpec(
        LlmConfig llm,
        List<String> mcpTools,
        SimulatorConfig simulator        // engine, timeResolution, seed, timeoutMs
) {}

public record SimulatorConfig(
        String engine,
        double timeResolution,
        double horizon,
        Long seed,
        RunMode mode,                    // SINGLE, MONTE_CARLO, PARAM_SWEEP
        int replications,
        long timeoutMs
) {}
```

### 4.4 세션 상태

```java
public record SessionState(
        UUID sessionId,
        String templateId,
        Phase phase,
        SesNode workingSes,              // 부분 pruning 된 트리 — 세션의 핵심 상태
        Map<String, Object> answers,
        List<OpenSlot> openSlots,        // SlotResolver가 매 턴 재계산
        int turnCount,
        List<ValidationIssue> issues
) {}

public enum Phase {
    ROUTING, ASSEMBLING, ELICITING, VALIDATING, BUILDING, RUNNING, DONE, FAILED
}

/** SlotResolver의 출력 — SES에서 파생된 "지금 물어봐야 할 것". */
public record OpenSlot(
        String slotName,
        SesAnchor anchor,
        SlotKind kind,                   // STRUCTURAL, VALUE
        List<String> options,            // SELECT일 때
        int depth,                       // 위상 정렬용
        SlotSpec spec                    // 템플릿의 표현 정보 (질문 문구 등)
) {}
```

`workingSes`가 세션의 유일한 상태입니다. 답변은 `answers`에 이력으로 남기지만, 실제 진행 상황은 트리가 얼마나 가지치기되었는지로 판단합니다.

---

## 5. 데이터베이스 스키마

SES 트리·템플릿·세션 상태는 전부 깊이가 가변인 중첩 구조라 관계형으로 정규화하면 조회할 때마다 재귀 CTE를 써야 합니다. **정체성과 검색 키만 컬럼으로 빼고 본문은 JSONB에 넣는 것**이 이 도메인에서 가장 실용적입니다.

```sql
-- V1__init.sql

-- 서브태스크 템플릿 (버전 관리)
CREATE TABLE subtask_template (
    id           VARCHAR(64)  NOT NULL,
    version      VARCHAR(32)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    spec         JSONB        NOT NULL,   -- SubtaskTemplate 전문
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id, version)
);
CREATE INDEX idx_template_active ON subtask_template (active, id);
CREATE INDEX idx_template_trigger ON subtask_template
    USING GIN ((spec -> 'routing' -> 'triggerPatterns'));

-- 도메인 SES 정의
CREATE TABLE ses_definition (
    id           VARCHAR(64) PRIMARY KEY,
    domain       VARCHAR(64) NOT NULL,
    version      VARCHAR(32) NOT NULL,
    tree         JSONB       NOT NULL,   -- SesNode 루트
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ses_domain ON ses_definition (domain);

-- 모델 베이스 — SES 리프가 어떤 원자 모델로 해석되는지
CREATE TABLE model_base (
    model_id     VARCHAR(64) PRIMARY KEY,
    kind         VARCHAR(16) NOT NULL,   -- ATOMIC | COUPLED
    display_name VARCHAR(200),
    ports        JSONB       NOT NULL,   -- {"in":[...], "out":[...]}
    state_vars   JSONB       NOT NULL,
    params       JSONB       NOT NULL,   -- 이름·타입·단위·기본값
    impl_class   VARCHAR(255),           -- AtomicModel 구현 FQCN
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 대화 세션
CREATE TABLE dialogue_session (
    session_id   UUID PRIMARY KEY,
    template_id  VARCHAR(64) NOT NULL,
    template_ver VARCHAR(32) NOT NULL,
    phase        VARCHAR(16) NOT NULL,
    working_ses  JSONB       NOT NULL,   -- 부분 pruning 된 트리
    answers      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    turn_count   INT         NOT NULL DEFAULT 0,
    issues       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_session_phase ON dialogue_session (phase, updated_at);

-- 생성된 시나리오
CREATE TABLE scenario (
    scenario_id  UUID PRIMARY KEY,
    session_id   UUID REFERENCES dialogue_session (session_id),
    pes          JSONB       NOT NULL,   -- 확정된 PES
    params       JSONB       NOT NULL,
    sim_config   JSONB       NOT NULL,   -- seed, horizon, mode, replications
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 시뮬레이션 실행
CREATE TABLE simulation_run (
    run_id       UUID PRIMARY KEY,
    scenario_id  UUID REFERENCES scenario (scenario_id),
    status       VARCHAR(16) NOT NULL,   -- QUEUED|RUNNING|SUCCEEDED|FAILED
    progress     DOUBLE PRECISION DEFAULT 0,
    result       JSONB,
    error        TEXT,
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ
);
CREATE INDEX idx_run_status ON simulation_run (status, started_at);

-- LLM 호출 감사 로그 (재현성·비용 추적)
CREATE TABLE llm_call_log (
    call_id      UUID PRIMARY KEY,
    session_id   UUID,
    purpose      VARCHAR(32) NOT NULL,   -- ROUTING | EXTRACTION | PHRASING | SUMMARY
    model        VARCHAR(64),
    request      JSONB,
    response     JSONB,
    latency_ms   INT,
    input_tokens INT,
    output_tokens INT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### JSONB 매핑

Hibernate 6은 `@JdbcTypeCode(SqlTypes.JSON)`으로 JSONB를 바로 지원합니다. 별도 라이브러리가 필요 없습니다.

```java
@Entity
@Table(name = "dialogue_session")
public class DialogueSessionEntity {
    @Id private UUID sessionId;
    private String templateId;
    @Enumerated(EnumType.STRING) private Phase phase;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private SesNode workingSes;          // Jackson이 폴리모픽 직렬화

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> answers;
    // ...
}
```

sealed interface의 폴리모픽 직렬화는 Jackson `@JsonTypeInfo`로 처리합니다.

```java
@JsonTypeInfo(use = Id.NAME, property = "nodeType")
@JsonSubTypes({
    @Type(value = EntityNode.class,      name = "ENTITY"),
    @Type(value = AspectNode.class,      name = "ASPECT"),
    @Type(value = SpecNode.class,        name = "SPEC"),
    @Type(value = MultiAspectNode.class, name = "MULTI")
})
public sealed interface SesNode { ... }
```

---

## 6. 핵심 컴포넌트 설계

### 6.1 SlotResolver — SES에서 질문을 뽑아내는 곳

이 시스템에서 가장 중요한 클래스입니다. 트리를 순회하며 미결정 지점을 수집합니다.

```java
public final class SlotResolver {

    public List<OpenSlot> scan(SesNode root, SubtaskTemplate tpl) {
        List<OpenSlot> out = new ArrayList<>();
        walk(root, "", 0, tpl, out);
        out.sort(Comparator.comparingInt(OpenSlot::depth));   // 얕은 것부터
        return out;
    }

    private void walk(SesNode node, String path, int depth,
                      SubtaskTemplate tpl, List<OpenSlot> out) {
        switch (node) {
            case EntityNode e -> {
                String p = path + "/" + e.name();
                // 미충전 변수 → 값 슬롯
                for (VarDef v : e.vars()) {
                    if (v.value() == null && v.defaultValue() == null) {
                        out.add(OpenSlot.value(p, v, depth, tpl));
                    }
                }
                for (SesNode axis : e.axes()) walk(axis, p, depth + 1, tpl, out);
            }
            case SpecNode s -> {
                if (s.selectedVariantId() == null) {
                    // 미해결 → 구조 슬롯. 하위는 아직 순회하지 않는다.
                    out.add(OpenSlot.select(path, s, depth, tpl));
                } else {
                    s.variants().stream()
                        .filter(v -> v.id().equals(s.selectedVariantId()))
                        .findFirst()
                        .ifPresent(v -> walk(v, path, depth + 1, tpl, out));
                }
            }
            case MultiAspectNode m -> {
                if (m.count() == null) {
                    out.add(OpenSlot.multiplicity(path, m, depth, tpl));
                } else {
                    for (int i = 0; i < m.count(); i++) {
                        walk(m.prototype(), path + "[" + i + "]", depth + 1, tpl, out);
                    }
                }
            }
            case AspectNode a -> {
                for (EntityNode c : a.components()) walk(c, path, depth + 1, tpl, out);
            }
        }
    }
}
```

**미해결 `SpecNode`의 하위를 순회하지 않는 것**이 핵심입니다. 케이블카를 고르기 전에는 케이블카의 "정원" 변수가 질문 목록에 들어가지 않습니다. 답변이 들어와 pruning되면 다음 `scan()`에서 자연스럽게 나타납니다. 이것이 "파생 슬롯"의 구현입니다.

### 6.2 PruningEngine — 답변을 트리에 적용

```java
public final class PruningEngine {

    /** 불변 트리를 답변에 따라 재작성하여 새 트리를 반환한다. */
    public SesNode apply(SesNode root, SesAnchor anchor, Object answer) {
        return rewrite(root, anchor, answer);
    }

    private SesNode rewrite(SesNode node, SesAnchor a, Object ans) {
        return switch (node) {
            case SpecNode s when s.id().equals(a.targetNodeId()) ->
                new SpecNode(s.id(), s.name(), s.variants(), resolveVariantId(s, ans));

            case MultiAspectNode m when m.id().equals(a.targetNodeId()) ->
                new MultiAspectNode(m.id(), m.name(), m.prototype(),
                                    m.countRange(), ((Number) ans).intValue());

            case EntityNode e when e.id().equals(a.targetNodeId())
                                   && a.axis() == AxisType.VARIABLE ->
                withVar(e, a, ans);

            // 대상이 아니면 자식만 재귀 재작성
            case EntityNode e -> new EntityNode(e.id(), e.name(), e.vars(),
                    e.axes().stream().map(n -> rewrite(n, a, ans)).toList());
            case AspectNode ap -> new AspectNode(ap.id(), ap.name(),
                    ap.components().stream()
                      .map(c -> (EntityNode) rewrite(c, a, ans)).toList(),
                    ap.couplings());
            case SpecNode s -> new SpecNode(s.id(), s.name(),
                    s.variants().stream()
                     .map(v -> (EntityNode) rewrite(v, a, ans)).toList(),
                    s.selectedVariantId());
            case MultiAspectNode m -> new MultiAspectNode(m.id(), m.name(),
                    (EntityNode) rewrite(m.prototype(), a, ans),
                    m.countRange(), m.count());
        };
    }
}
```

불변 재작성이라 세션 상태 롤백("이전 답변 취소")이 트리 하나 되돌리기로 끝납니다. 프로토타입 규모에서 성능 문제는 없습니다.

### 6.3 DialogueController — 상태머신

```java
@Service
public class DialogueController {

    public TurnResult submitAnswers(UUID sessionId, Map<String, Object> answers) {
        SessionState st = sessionStore.load(sessionId);

        // 1. 답변별 타입/도메인 검증
        var typeIssues = validationChain.validateAnswers(st, answers);
        if (!typeIssues.isEmpty()) return TurnResult.reask(typeIssues);

        // 2. pruning 적용
        SesNode ses = st.workingSes();
        for (var entry : answers.entrySet()) {
            SesAnchor anchor = anchorOf(st, entry.getKey());
            ses = pruningEngine.apply(ses, anchor, entry.getValue());
        }

        // 3. 슬롯 재스캔 (구조 답변이 새 슬롯을 파생시킬 수 있음)
        List<OpenSlot> open = slotResolver.scan(ses, template(st));

        // 4. 기본값·추론 허용 슬롯 자동 충전
        open = autoFill(open, ses, st);

        // 5. 완료 판정
        if (open.isEmpty()) {
            var issues = validationChain.validateAll(ses, template(st));
            if (issues.isEmpty() && completionJudge.isComplete(ses)) {
                return TurnResult.complete(sessionStore.advance(st, ses, Phase.BUILDING));
            }
            return TurnResult.reask(issues);
        }

        // 6. 턴 예산 초과 시 기본값으로 마감
        if (st.turnCount() >= dialogueControl(st).maxTurns()) {
            return forceComplete(st, ses, open);
        }

        // 7. 다음 질문 선정 — 의존성 없는 슬롯을 배치로
        List<Question> next = questionPlanner.plan(open, dialogueControl(st));
        return TurnResult.ask(sessionStore.advance(st, ses, Phase.ELICITING), next);
    }
}
```

### 6.4 ValidationChain — 4계층

Chain of Responsibility. 앞 단계가 실패하면 뒤는 실행하지 않습니다.

| 순서 | Validator | 검사 내용 | 구현 |
|---|---|---|---|
| 1 | `TypeValidator` | 자료형, 허용 범위, 열거값 | `ValueSlot.type/range` 직접 검사 |
| 2 | `CrossFieldValidator` | 필드 간 제약 (`객실수 * 1.5 <= 주차면수`) | Spring Expression Language |
| 3 | `UnitConsistencyValidator` | 단위 호환, 시간 해상도 vs 시뮬레이션 기간 | `VarDef.unit` + 환산표 |
| 4 | `StructuralIntegrityValidator` | 미해결 spec 노드, 고아 포트, 커플링 타입 불일치 | PES 사전 빌드 후 검사 |

```java
public interface SlotValidator {
    int order();
    List<ValidationIssue> validate(ValidationContext ctx);
}

@Component
public class UnitConsistencyValidator implements SlotValidator {
    @Override public int order() { return 30; }

    @Override public List<ValidationIssue> validate(ValidationContext ctx) {
        var issues = new ArrayList<ValidationIssue>();
        double dt = ctx.simConfig().timeResolution();
        double horizon = ctx.simConfig().horizon();
        if (horizon / dt > 1_000_000) {
            issues.add(ValidationIssue.of("simulator.timeResolution",
                "시간 해상도 %.3f로 %.0f 기간을 돌리면 스텝이 100만 회를 넘습니다."
                    .formatted(dt, horizon)));
        }
        // 포트 연결된 변수 간 단위 호환성
        ctx.couplings().forEach(c -> checkUnitMatch(ctx, c, issues));
        return issues;
    }
}
```

4번 `StructuralIntegrityValidator`가 원본 템플릿에 없던 항목입니다. "모든 spec 노드가 해소되었는가", "모든 커플링의 양 끝 포트가 실제로 존재하는가"를 확인하지 않으면 시뮬레이터 실행 시점에 정체불명의 NPE가 납니다.

### 6.5 DEVS 엔진

Zeigler의 표준 atomic/coupled 정형에 충실하게 구현합니다.

```java
package org.hanbat.ses.devs;

public interface AtomicModel<S> {
    S initialState();
    S internalTransition(S state);
    S externalTransition(S state, double elapsed, List<Message> input);
    default S confluentTransition(S state, List<Message> input) {
        return externalTransition(internalTransition(state), 0.0, input);
    }
    List<Message> output(S state);
    double timeAdvance(S state);        // Double.POSITIVE_INFINITY = passive
}

public record CoupledModel(
        String id,
        List<ModelRef> components,
        List<CouplingSpec> couplings
) {}

public record Message(String port, Object value) {}
```

시뮬레이터는 root-coordinator 알고리즘을 씁니다.

```java
public final class Coordinator {
    private double tN;   // 다음 내부 전이 시각
    private double tL;   // 마지막 전이 시각

    public SimulationResult run(SimulationModel model, SimConfig cfg) {
        double t = 0.0;
        var trace = new ArrayList<TraceEntry>();
        while (t < cfg.horizon() && t < Double.POSITIVE_INFINITY) {
            t = nextEventTime();
            if (t > cfg.horizon()) break;
            var out = collectOutputs(t);
            routeMessages(out);
            applyTransitions(t);
            trace.add(new TraceEntry(t, snapshot()));
            if (System.nanoTime() - startNs > cfg.timeoutNs()) {
                throw new SimulationTimeoutException(t);
            }
        }
        return SimulationResult.of(trace, statistics(trace));
    }
}
```

타임아웃 검사를 루프 안에 넣는 것을 잊지 마세요. LLM이 생성한 시나리오는 `timeAdvance`가 0에 수렴하는 무한 루프(zeno behavior)를 만들 수 있습니다.

### 6.6 LlmGateway — WebClient 직접 호출

```java
@Component
public class LlmGateway {
    private final WebClient client;
    private final ObjectMapper mapper;

    public LlmGateway(WebClient.Builder builder,
                      @Value("${llm.api-key}") String apiKey,
                      @Value("${llm.base-url}") String baseUrl) {
        this.client = builder
            .baseUrl(baseUrl)
            .defaultHeader("x-api-key", apiKey)
            .defaultHeader("anthropic-version", "2023-06-01")
            .defaultHeader("content-type", "application/json")
            .build();
    }

    /** 구조화 출력 — JSON 스키마를 프롬프트에 주입하고 파싱 실패 시 1회 재시도. */
    public <T> T complete(String system, String user, Class<T> type, Purpose purpose) {
        String schema = JsonSchemaGenerator.of(type);
        String prompt = user + "\n\n반드시 아래 JSON 스키마를 따르는 JSON만 출력하세요."
                             + "\n설명이나 코드펜스 없이 JSON 객체만 반환합니다.\n" + schema;

        for (int attempt = 0; attempt < 2; attempt++) {
            String raw = call(system, prompt, purpose);
            try {
                return mapper.readValue(stripFence(raw), type);
            } catch (JsonProcessingException e) {
                if (attempt == 1) throw new LlmParseException(raw, e);
                prompt = prompt + "\n\n이전 응답이 파싱에 실패했습니다: " + e.getOriginalMessage();
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private String call(String system, String user, Purpose purpose) {
        var body = Map.of(
            "model", modelFor(purpose),
            "max_tokens", 4096,
            "system", system,
            "messages", List.of(Map.of("role", "user", "content", user))
        );
        return client.post().uri("/v1/messages")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(AnthropicResponse.class)
            .timeout(Duration.ofSeconds(60))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                            .filter(this::isRetryable))
            .map(AnthropicResponse::firstText)
            .block();
    }
}
```

Resilience4j 대신 Reactor의 `retryWhen`으로 충분합니다. 429와 5xx만 재시도하고 4xx는 즉시 실패시킵니다.

### 6.7 LLM 사용 지점 — 딱 세 곳

| 지점 | 입력 | 출력 | 실패 시 |
|---|---|---|---|
| **라우팅** | 사용자 요청문 + 템플릿 요약 목록 | `{templateId, confidence}` | 키워드 매칭 폴백 |
| **슬롯 추출** | 요청문 + OpenSlot 목록 | `{slotName: value, confidence}` 맵 | 추출 생략, 전부 질문 |
| **문구 다듬기** | 기계 생성 질문/결과 | 자연스러운 한국어 문장 | 템플릿 원문 그대로 |

추출된 값도 **반드시 ValidationChain을 통과**해야 합니다. LLM이 "정원 500명"을 뽑아냈어도 범위가 1~200이면 거부하고 다시 묻습니다. 신뢰도가 임계값 아래면 값을 채우되 확인 질문("정원을 80명으로 이해했습니다. 맞나요?")을 붙입니다.

---

## 7. API 명세

```
POST   /api/v1/sessions                 요청문 → 세션 생성 · 라우팅 · 첫 질문
GET    /api/v1/sessions/{id}            현재 상태 · 남은 슬롯 · 진행률
POST   /api/v1/sessions/{id}/answers    답변 제출 → 다음 질문 또는 완료
POST   /api/v1/sessions/{id}/undo       직전 턴 되돌리기
GET    /api/v1/sessions/{id}/ses        현재 pruning 상태 트리 (시각화용)

POST   /api/v1/sessions/{id}/scenario   시나리오 생성 (완료 조건 충족 시)
GET    /api/v1/scenarios/{id}           PES · 파라미터 조회

POST   /api/v1/scenarios/{id}/runs      시뮬레이션 실행 (비동기, 202 반환)
GET    /api/v1/runs/{id}                실행 상태 · 결과

GET    /api/v1/templates                템플릿 목록
POST   /api/v1/templates                템플릿 등록 (JSON 스키마 검증)
GET    /api/v1/ses-definitions/{id}     도메인 SES 조회
GET    /api/v1/models                   모델 베이스 목록
```

### 응답 예시 — `POST /sessions/{id}/answers`

```json
{
  "sessionId": "3f2a...",
  "phase": "ELICITING",
  "turn": 3,
  "questions": [
    {
      "slot": "cableCar.capacity",
      "text": "케이블카 1대당 정원은 몇 명인가요?",
      "inputType": "INTEGER",
      "unit": "명",
      "range": { "min": 1, "max": 200 },
      "default": 8
    },
    {
      "slot": "cableCar.interval",
      "text": "운행 간격은 몇 분인가요?",
      "inputType": "DOUBLE",
      "unit": "분",
      "range": { "min": 0.5, "max": 30 }
    }
  ],
  "progress": { "resolved": 5, "open": 2, "estimatedRemainingTurns": 1 },
  "derivedFrom": "교통수단=케이블카 선택으로 새 항목이 추가되었습니다.",
  "sesSnapshot": {
    "리조트": {
      "이동설비": { "resolved": "케이블카", "pending": ["capacity", "interval"] },
      "숙박시설": { "resolved": "호텔", "pending": [] }
    }
  }
}
```

`derivedFrom` 필드로 "왜 갑자기 이 질문이 나왔는지"를 사용자에게 설명합니다. 파생 슬롯 구조에서는 이 설명이 없으면 질문이 끝없이 늘어나는 것처럼 느껴집니다.

### 완료 응답

```json
{
  "sessionId": "3f2a...",
  "phase": "BUILDING",
  "scenarioId": "9c81...",
  "summary": "리조트 1개(호텔 200실 + 케이블카 8인승 3분 간격)를 24시간 시뮬레이션합니다.",
  "pes": { "...": "확정된 모델 구조" },
  "validation": { "passed": true, "warnings": [] }
}
```

---

## 8. 단계별 개발 계획

총 11주 기준. 연구용 프로토타입이므로 각 Phase 끝에 **실행 가능한 산출물**이 나오도록 잘랐습니다.

### Phase 0 — 골격 (1주)

| 작업 | 산출물 |
|---|---|
| Gradle 멀티모듈 구성 | `settings.gradle.kts`, 8개 모듈 |
| Spring Boot + PostgreSQL + Flyway | `V1__init.sql` 적용 확인 |
| Jackson 폴리모픽 설정, 공통 예외 처리 | `GlobalExceptionHandler` |
| Testcontainers 세팅 | 통합 테스트 1개 통과 |

**완료 기준:** `./gradlew build` 통과, 헬스체크 200, DB 마이그레이션 성공.

### Phase 1 — SES 코어 (2주) · LLM 없음

| 작업 | 산출물 |
|---|---|
| `SesNode` 계층, `VarDef`, `CouplingSpec` | `core-ses` 자료구조 |
| SES JSON/YAML 파서 + 공리 검증기 | 교번 모드·형제 유일성 검사 |
| `SlotResolver.scan()` | 미결정 지점 수집 |
| `PruningEngine.apply()` | 불변 재작성 |
| `PesBuilder` | SES → PES 변환 |

**완료 기준:** CLI로 예제 SES를 읽고, 답변 시퀀스를 파일로 주면 PES가 나오는 것을 확인. 이 단계에서 **LLM도 Spring도 필요 없습니다.**

핵심 테스트 (속성 기반, jqwik):
- 모든 spec 노드가 해소되면 PES는 유일하다
- pruning 순서를 바꿔도 최종 PES는 동일하다 (교환법칙)
- pruning은 항상 트리를 축소한다 (노드 수가 늘지 않음)

### Phase 2 — DEVS 코어 (2주) · LLM 없음

| 작업 | 산출물 |
|---|---|
| `AtomicModel`, `CoupledModel`, `Message` | `core-devs` 인터페이스 |
| `Coordinator` root 알고리즘 | 이벤트 루프, 타임아웃, zeno 방지 |
| 고전 예제 3종 구현 | Generator, Processor, Transducer |
| 결과 통계 수집기 | throughput, utilization, queue length |

**완료 기준:** Generator-Processor-Transducer 예제가 이론값과 일치하는 결과를 냅니다. 이 골든 테스트가 이후 모든 회귀의 기준선이 됩니다.

> **범위 주의:** 이 Phase에서 도메인 모델(리조트, 교통 등)을 만들지 마세요. 엔진의 정확성만 검증합니다. 도메인 모델은 Phase 5에서 붙입니다.

### Phase 3 — 템플릿 + 대화 (2주) · LLM 없음

| 작업 | 산출물 |
|---|---|
| `SubtaskTemplate` 모델 + JSON 스키마 | 템플릿 정의 포맷 확정 |
| `TemplateRegistry` (캐싱, 버전) | `@Cacheable` |
| `DialogueController` 상태머신 | 턴 처리, 재스캔 루프 |
| `ValidationChain` 4계층 | 4개 Validator |
| `CompletionJudge` | 3조건 판정 |
| 세션 REST API | `/sessions`, `/answers` |

**완료 기준:** 정해진 답변 스크립트(JSON 파일)를 순서대로 POST하면 시나리오까지 도달하는 E2E 테스트가 통과합니다. 여전히 LLM은 없고, 라우팅은 템플릿 ID를 직접 지정합니다.

### Phase 4 — LLM 통합 (2주)

| 작업 | 산출물 |
|---|---|
| `LlmGateway` + 구조화 출력 파서 | 재시도, 타임아웃, 로깅 |
| `RequestRouter` LLM 분류 | 키워드 폴백 포함 |
| `SlotExtractor` 일괄 추출 | 신뢰도 기반 확인 질문 |
| 질문/결과 문구 다듬기 | 실패 시 원문 폴백 |
| `llm_call_log` 감사 기록 | 비용·지연 추적 |

**완료 기준:** 자연어 요청 한 문장으로 시작해 3턴 이내에 시나리오가 생성됩니다. **Phase 3의 E2E 테스트가 여전히 통과해야 합니다** — LLM을 껐을 때 기존 경로가 그대로 동작하는 것이 폴백 설계의 증거입니다.

### Phase 5 — 실행·결과 (2주)

| 작업 | 산출물 |
|---|---|
| `ModelFactory` — PES 리프 → AtomicModel | 리플렉션 또는 `Map<String, Supplier>` 등록 |
| `ScenarioExecutor` 비동기 실행 | `@Async` + `simulation_run` 상태 폴링 |
| 결과 형식화 (요약/표/차트 데이터) | `OutputSpec` 분기 |
| 실패 처리 | 부분 결과 반환, 재질문 유도 |
| 도메인 모델 1세트 | 실제 시연용 |

**완료 기준:** 요청 → 대화 → 시나리오 → 실행 → 결과 요약이 한 흐름으로 동작합니다. **여기가 프로토타입 완성 지점입니다.**

### Phase 6 — 확장 (선택)

- 서브태스크 DAG 합성 (여러 템플릿을 엮은 복합 요청)
- 몬테카를로 / 파라미터 스윕 실행 모드
- SES pruning 상태 시각화 프론트엔드 (`/sessions/{id}/ses` 소비)
- 템플릿 저작 도구

### 타임라인

```
주차   1    2    3    4    5    6    7    8    9   10   11
      ├────┼────┼────┼────┼────┼────┼────┼────┼────┼────┤
P0    ███
P1         ██████████
P2                   ██████████
P3                             ██████████
P4                                       ██████████
P5                                                 ██████████
                                                   ▲
                                          프로토타입 완성
```

---

## 9. 테스트 전략

| 대상 | 방식 | 도구 |
|---|---|---|
| `core-ses` | 속성 기반 테스트 — pruning 불변식 | jqwik |
| `core-devs` | 골든 테스트 — 고전 예제의 이론값 대조 | JUnit 5 |
| `dialogue` | 시나리오 테스트 — 답변 시퀀스 → 기대 질문 시퀀스 | JUnit 5 + 픽스처 JSON |
| `llm` | 스텁 응답으로 파싱·재시도 검증 | WireMock |
| 통합 | 실제 PostgreSQL 컨테이너 | Testcontainers |
| E2E | 요청 → 결과 전 구간 | `@SpringBootTest` + MockMvc |

**LLM 실호출 테스트는 `@Tag("live")`로 분리**하고 CI 기본 실행에서 제외합니다. 비결정적이고 비용이 들며, 실패해도 코드 문제가 아닌 경우가 많습니다.

### 대화 시나리오 테스트 예시

```java
@Test
void 케이블카_선택시_정원_질문이_파생된다() {
    var session = api.createSession("리조트 하나 시뮬레이션 돌려줘", "resort-v1");

    var t1 = api.answer(session, Map.of("이동설비", "케이블카"));

    assertThat(t1.questions())
        .extracting(Question::slot)
        .contains("cableCar.capacity", "cableCar.interval")   // 파생됨
        .doesNotContain("shuttle.busCount");                  // 가지치기됨

    var t2 = api.answer(session, Map.of(
        "cableCar.capacity", 8, "cableCar.interval", 3.0));

    assertThat(t2.phase()).isEqualTo(Phase.BUILDING);
    assertThat(t2.pes()).isNotNull();
}
```

---

## 10. 확장 포인트

새 기능을 추가할 때 **코드를 고치지 않아도 되는 축**을 미리 확보해 둡니다.

| 확장 대상 | 필요한 작업 | 코드 수정 |
|---|---|---|
| 새 도메인 | `ses_definition` + `subtask_template` 행 추가 | 없음 |
| 새 원자 모델 | `AtomicModel` 구현 + `model_base` 등록 | 클래스 1개 |
| 새 검증 규칙 | `SlotValidator` 구현체 추가 | 클래스 1개 |
| LLM 교체 | `LlmGateway` 구현체 교체 | 인터페이스 뒤 |
| 시뮬레이터 교체 | `ScenarioExecutor` 구현체 교체 | 인터페이스 뒤 |

`ScenarioExecutor`를 인터페이스로 두는 것이 중요합니다. 자체 DEVS 엔진으로 시작하지만, 나중에 외부 시뮬레이터(AnyLogic, SUMO 등)를 붙이고 싶어질 때 오케스트레이션 계층 전체를 건드리지 않게 됩니다.

```java
public interface ScenarioExecutor {
    boolean supports(String engine);
    SimulationResult execute(Pes pes, SimConfig config);
}
```

---

## 11. 리스크와 대응

| 리스크 | 영향 | 대응 |
|---|---|---|
| LLM 슬롯 추출 오류 | 잘못된 시나리오 생성 | 추출값도 ValidationChain 필수 통과. 저신뢰 값은 확인 질문 |
| 질문이 너무 많아짐 | 사용성 붕괴 | 턴 예산 + 기본값 + 배치 질문 + `derivedFrom` 설명 |
| SES 트리 폭발 | 메모리·성능 | multi-aspect 개수 상한, pruning 깊이 제한, 노드 수 상한 |
| DEVS 직접 구현 부담 | 일정 초과 | Phase 2를 고전 예제로 한정. 도메인 모델은 Phase 5로 미룸 |
| 템플릿-SES 불일치 | 디버깅 지옥 | SES 단일 진실 원천 원칙. 템플릿은 표현 계층만 |
| 시뮬레이션 무한 루프 | 서버 행 | Coordinator 루프 내 타임아웃 + zeno 검출 |
| 세션 상태 비대 | DB 부하 | `working_ses`만 저장, 이력은 별도 테이블. TTL로 만료 세션 정리 |

가장 현실적인 위험은 **Phase 2의 DEVS 엔진 구현 분량**입니다. 일정이 밀린다면 Phase 2를 축소해 이산 이벤트 큐 수준의 단순 엔진으로 시작하고, 정식 DEVS 정형은 Phase 6으로 미루는 것이 안전합니다. Phase 1·3·4가 이 프로젝트의 학술적 기여에 해당하고, Phase 2는 그것을 보여주기 위한 수단이기 때문입니다.

---

## 12. 템플릿 정의 예시

실제로 작성하게 될 템플릿 JSON의 모습입니다.

```json
{
  "id": "resort-simulation",
  "version": "1.0.0",
  "name": "리조트 운영 시뮬레이션",
  "routing": {
    "triggerPatterns": ["리조트", "휴양지", "resort"],
    "intentDescription": "리조트의 숙박·이동·편의시설 구성을 정하고 방문객 흐름을 시뮬레이션",
    "priority": 10,
    "prerequisites": []
  },
  "binding": {
    "sesDefinitionId": "resort-ses",
    "rootEntity": "리조트",
    "pruningRules": {
      "이동설비=케이블카": "node-cablecar",
      "이동설비=셔틀버스": "node-shuttle",
      "이동설비=모노레일": "node-monorail"
    }
  },
  "slots": [
    {
      "kind": "STRUCTURAL",
      "name": "이동설비",
      "anchor": {
        "entityPath": "리조트/이동설비",
        "axis": "SPECIALIZATION",
        "targetNodeId": "spec-transport"
      },
      "structuralKind": "SELECT",
      "options": ["케이블카", "셔틀버스", "모노레일"],
      "question": {
        "text": "리조트 내 주 이동수단은 무엇인가요?",
        "reaskText": "케이블카, 셔틀버스, 모노레일 중에서 골라주세요."
      },
      "dependsOn": []
    },
    {
      "kind": "VALUE",
      "name": "cableCar.capacity",
      "anchor": {
        "entityPath": "리조트/이동설비/케이블카",
        "axis": "VARIABLE",
        "targetNodeId": "var-capacity"
      },
      "type": "INTEGER",
      "unit": "명",
      "range": { "min": 1, "max": 200 },
      "defaultValue": 8,
      "inferable": true,
      "question": { "text": "케이블카 1대당 정원은 몇 명인가요?" },
      "dependsOn": ["이동설비"]
    }
  ],
  "dialogue": {
    "maxTurns": 6,
    "maxQuestionsPerTurn": 3,
    "unfilledPolicy": "USE_DEFAULT"
  },
  "validation": {
    "crossFieldRules": [
      {
        "expression": "#객실수 * 1.5 <= #주차면수",
        "message": "객실 200실이면 주차면이 300면 이상이어야 합니다."
      }
    ]
  },
  "execution": {
    "llm": { "model": "claude-opus-4", "temperature": 0.2 },
    "mcpTools": [],
    "simulator": {
      "engine": "devs-internal",
      "timeResolution": 1.0,
      "horizon": 1440.0,
      "seed": 42,
      "mode": "SINGLE",
      "replications": 1,
      "timeoutMs": 30000
    }
  },
  "output": {
    "format": "COMPOSITE",
    "sections": ["SUMMARY", "TABLE", "CHART"],
    "failurePolicy": "PARTIAL_RESULT"
  },
  "meta": { "author": "sun", "createdAt": "2026-09-06" }
}
```

`slots` 배열에 있는 것은 **표현 정보와 SES 앵커뿐**입니다. "어떤 슬롯이 지금 열려 있는가"는 `SlotResolver`가 SES 트리에서 계산합니다. `cableCar.capacity`가 템플릿에 정의되어 있어도 `이동설비`가 케이블카로 결정되기 전에는 질문 목록에 나타나지 않습니다.

---

## 13. 착수 체크리스트

**첫 주에 할 일**

- [ ] Gradle 멀티모듈 스캐폴딩 (`core-ses`, `core-devs`는 `java-library` 플러그인만)
- [ ] Spring Boot 3.3 + PostgreSQL 16 컨테이너 기동
- [ ] Flyway `V1__init.sql` 작성 및 적용
- [ ] Jackson 폴리모픽 설정 (`SesNode` 직렬화 왕복 테스트)
- [ ] Testcontainers 통합 테스트 1개

**첫 달 안에 증명해야 할 것**

- [ ] SES 트리에서 질문 목록이 자동으로 나온다 (`SlotResolver`)
- [ ] 구조 답변이 새 질문을 파생시킨다 (`PruningEngine` → 재스캔)
- [ ] 모든 spec이 해소되면 PES가 유일하게 확정된다
- [ ] 이 전부가 LLM 없이 동작한다

이 네 가지가 되면 나머지는 통합 작업입니다. 반대로 이것이 안 되면 LLM을 아무리 잘 붙여도 시스템이 성립하지 않습니다.

---

## 부록 A. 원본 11개 필드 → 구현 대응

| 원본 템플릿 필드 | 구현 위치 | 비고 |
|---|---|---|
| 적용 대상 | `Routing.triggerPatterns/intentDescription` | LLM 분류 + 키워드 폴백 |
| 필수 입력값 | `SlotResolver.scan()` 결과 | **SES에서 도출** (템플릿이 아님) |
| 단계별 질문 | `QuestionSpec` + `QuestionPlanner` | 순서는 의존 그래프 위상정렬 |
| 입력 형식 | `ValueSlot.type/range/unit` | `TypeValidator` |
| 선택 조건 | `StructuralSlot.options` | `SpecNode.variants`와 동기 |
| 조건 간 관계 | `SlotSpec.dependsOn` + 파생 슬롯 | 미해결 spec 하위 미순회로 자연 구현 |
| 검증 규칙 | `ValidationChain` 4계층 | 단위·구조 검증 신규 |
| 완료 조건 | `CompletionJudge` 3조건 | PES 유일성 신규 |
| 설정값 연결 | `StructuralSlot`(구조) / `ValueSlot`(값) | **분리가 핵심 변경** |
| 실행 도구 | `ExecutionSpec.llm / mcpTools / simulator` | LLM과 시뮬레이터 분리 |
| 결과 형식 | `OutputSpec.format/sections/failurePolicy` | 실패 처리 신규 |

## 부록 B. 기술 스택 버전

```
Java              21 (LTS)
Spring Boot       3.3.x
Spring Framework  6.1.x
Hibernate         6.5.x  (@JdbcTypeCode(SqlTypes.JSON))
PostgreSQL        16
Flyway            10.x
Jackson           2.17.x
Gradle            8.8
JUnit             5.10
Testcontainers    1.19.x
jqwik             1.8.x   (속성 기반 테스트)
WireMock          3.x     (LLM 스텁)
```

> 버전은 착수 시점에 최신 안정판으로 재확인하세요. 특히 Spring Boot와 Hibernate는 JSONB 매핑 API가 마이너 버전 간에도 바뀐 적이 있습니다.
