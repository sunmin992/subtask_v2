-- SES 기반 LLM 시나리오 생성 서버 — 초기 스키마
--
-- SES 트리·템플릿·세션 상태는 전부 깊이가 가변인 중첩 구조라 관계형으로 정규화하면
-- 조회할 때마다 재귀 CTE 를 써야 한다. 정체성과 검색 키만 컬럼으로 빼고
-- 본문은 JSONB 에 넣는 것이 이 도메인에서 가장 실용적이다.

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
    ports        JSONB       NOT NULL,
    state_vars   JSONB       NOT NULL,
    params       JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 대화 세션
--
-- 계획서의 컬럼에 네 개를 더했다. pending_questions/derived_from 이 없으면 세션을
-- 다시 읽었을 때 "무엇을 묻고 있었는지"가 사라지고, history 가 없으면 undo 를
-- 구현할 수 없다. request_text 는 LLM 슬롯 추출을 다시 돌릴 때 필요하다.
CREATE TABLE dialogue_session (
    session_id        UUID PRIMARY KEY,
    template_id       VARCHAR(64) NOT NULL,
    template_ver      VARCHAR(32) NOT NULL,
    phase             VARCHAR(16) NOT NULL,
    request_text      TEXT,
    working_ses       JSONB       NOT NULL,   -- 부분 pruning 된 트리
    answers           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    issues            JSONB       NOT NULL DEFAULT '[]'::jsonb,
    pending_questions JSONB       NOT NULL DEFAULT '[]'::jsonb,
    history           JSONB       NOT NULL DEFAULT '[]'::jsonb,
    derived_from      TEXT,
    turn_count        INT         NOT NULL DEFAULT 0,
    scenario_id       UUID,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_session_phase ON dialogue_session (phase, updated_at);

-- 생성된 시나리오
CREATE TABLE scenario (
    scenario_id  UUID PRIMARY KEY,
    session_id   UUID REFERENCES dialogue_session (session_id),
    template_id  VARCHAR(64) NOT NULL,
    pes          JSONB       NOT NULL,   -- 확정된 PES
    params       JSONB       NOT NULL,
    sim_config   JSONB       NOT NULL,   -- seed, horizon, mode, replications
    output_spec  JSONB       NOT NULL,
    summary      TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_scenario_session ON scenario (session_id);

-- 시뮬레이션 실행
CREATE TABLE simulation_run (
    run_id       UUID PRIMARY KEY,
    scenario_id  UUID NOT NULL REFERENCES scenario (scenario_id),
    status       VARCHAR(16) NOT NULL,   -- QUEUED|RUNNING|SUCCEEDED|FAILED
    progress     DOUBLE PRECISION NOT NULL DEFAULT 0,
    result       JSONB,
    error        TEXT,
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ
);
CREATE INDEX idx_run_status ON simulation_run (status, started_at);
CREATE INDEX idx_run_scenario ON simulation_run (scenario_id);

-- LLM 호출 감사 로그 (재현성·비용 추적)
--
-- request/response 를 TEXT 로 둔다. JSONB 로 두면 응답이 JSON 이 아닐 때 —
-- 파싱 실패가 정확히 그 경우다 — 저장 자체가 실패해서, 정작 조사해야 할 사례가 남지 않는다.
CREATE TABLE llm_call_log (
    call_id       UUID PRIMARY KEY,
    session_id    UUID,
    purpose       VARCHAR(32) NOT NULL,   -- ROUTING | EXTRACTION | PHRASING | SUMMARY
    model         VARCHAR(64),
    request       TEXT,
    response      TEXT,
    latency_ms    INT,
    input_tokens  INT,
    output_tokens INT,
    error         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_llm_session ON llm_call_log (session_id, created_at);
CREATE INDEX idx_llm_purpose ON llm_call_log (purpose, created_at);
