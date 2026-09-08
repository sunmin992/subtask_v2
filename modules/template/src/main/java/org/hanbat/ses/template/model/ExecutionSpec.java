package org.hanbat.ses.template.model;

import java.util.List;

/** 실행 도구 — LLM 과 시뮬레이터를 분리해 둔다. 둘은 교체 주기가 다르다. */
public record ExecutionSpec(
        LlmConfig llm,
        List<String> mcpTools,
        SimulatorConfig simulator
) {

    public ExecutionSpec {
        llm = llm == null ? LlmConfig.defaults() : llm;
        mcpTools = mcpTools == null ? List.of() : List.copyOf(mcpTools);
        simulator = simulator == null ? SimulatorConfig.defaults() : simulator;
    }

    public static ExecutionSpec defaults() {
        return new ExecutionSpec(LlmConfig.defaults(), List.of(), SimulatorConfig.defaults());
    }
}
