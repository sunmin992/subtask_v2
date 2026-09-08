package org.hanbat.ses.dialogue.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.hanbat.ses.core.resolve.SlotOption;
import org.hanbat.ses.llm.gateway.LlmGateway;
import org.hanbat.ses.llm.gateway.Purpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 요청문에서 열린 슬롯의 값을 일괄 추출한다.
 *
 * <p>"리조트에 8인승 케이블카를 3분 간격으로 놓고 시뮬레이션 돌려줘" 한 문장이면
 * 질문 세 개가 사라진다. 이것이 LLM 을 쓰는 가장 큰 이유다.
 *
 * <p>다만 추출된 값도 <b>예외 없이</b> ValidationChain 을 통과해야 한다.
 * 여기서는 값을 제안만 하고, 채택 여부는 DialogueController 가 검증 후에 정한다.
 * 신뢰도가 임계값 아래면 값을 채우되 확인 질문을 붙인다.
 */
@Component
public class SlotExtractor {

    private static final Logger log = LoggerFactory.getLogger(SlotExtractor.class);

    /** 이 값 미만이면 채우되 사용자에게 확인을 받는다. */
    public static final double CONFIRM_THRESHOLD = 0.75;

    /**
     * 이 값 미만이면서 근거도 확인되지 않으면 쓰지 않는다.
     *
     * <p>신뢰도만으로 문턱을 걸면 안 된다. 파라미터가 적은 모델은 예시에 적힌 숫자를
     * 그대로 베끼는 일이 잦다 — gemma2:9b 는 값을 정확히 뽑아 놓고 confidence 를
     * 예시의 0.0 그대로 써 보냈고, 그러면 멀쩡한 추출이 전부 버려진다.
     * 그래서 {@link EvidenceCheck} 로 근거 문구를 요청문과 대조해 구제한다.
     */
    public static final double MIN_THRESHOLD = 0.4;

    /** 근거는 확인됐지만 모델이 신뢰도를 주지 않은 경우에 쓰는 값. 확인 질문을 붙이게 된다. */
    private static final double GROUNDED_CONFIDENCE = 0.6;

    private static final String SYSTEM = """
            당신은 사용자의 시뮬레이션 요청문에서 파라미터를 추출하는 도구입니다.
            주어진 슬롯 목록 각각에 대해, 요청문에 명시적으로 드러난 값만 채우세요.
            문장에 없는 값을 상식으로 지어내지 마세요. 확실하지 않으면 그 슬롯을 결과에서 빼세요.
            숫자는 단위를 제거한 순수한 수로 주세요.
            evidence 에는 그 값의 근거가 된 요청문 구절을 그대로 인용하세요 — 이 인용으로 검증합니다.
            confidence 는 0 과 1 사이에서 스스로 판단해 적으세요. 예시의 숫자를 그대로 쓰지 마세요.
            """;

    /** LLM 이 채우는 구조. */
    public record Extraction(List<Extracted> values) {
    }

    public record Extracted(String slot, String value, double confidence, String evidence) {
    }

    public record Suggestion(Object value, double confidence, String evidence) {

        public boolean needsConfirmation() {
            return confidence < CONFIRM_THRESHOLD;
        }
    }

    private final LlmGateway llm;

    public SlotExtractor(LlmGateway llm) {
        this.llm = llm;
    }

    /**
     * @return 슬롯 이름 -> 제안값. LLM 을 쓸 수 없으면 빈 맵 — 그러면 전부 질문한다.
     */
    public Map<String, Suggestion> extract(String request, List<ResolvedSlot> open) {
        if (!llm.available() || request == null || request.isBlank() || open.isEmpty()) {
            return Map.of();
        }
        List<ResolvedSlot> allowed = open.stream()
                // 구조 슬롯은 항상 추출 대상이고, 값 슬롯은 템플릿이 추론을 허용한 것만.
                .filter(s -> s.kind().isStructural() || s.inferable())
                .toList();
        if (allowed.isEmpty()) {
            return Map.of();
        }
        try {
            Extraction result = llm.complete(SYSTEM, prompt(request, allowed),
                    Extraction.class, Purpose.EXTRACTION);
            return adopt(result, allowed, request);
        } catch (RuntimeException e) {
            log.warn("슬롯 추출 실패, 전부 질문으로 진행합니다: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Suggestion> adopt(Extraction result, List<ResolvedSlot> allowed,
                                          String request) {
        Map<String, ResolvedSlot> byName = allowed.stream()
                .collect(Collectors.toMap(ResolvedSlot::name, s -> s, (a, b) -> a,
                        LinkedHashMap::new));
        Map<String, Suggestion> out = new LinkedHashMap<>();
        if (result == null || result.values() == null) {
            return out;
        }
        for (Extracted e : result.values()) {
            if (e == null || e.slot() == null || e.value() == null) {
                continue;
            }
            if (!byName.containsKey(e.slot())) {
                continue;
            }
            double confidence = e.confidence();
            if (confidence < MIN_THRESHOLD) {
                // 신뢰도가 낮아도 근거 문구가 요청문에 실제로 있으면 채택한다.
                // 다만 확인 질문이 붙는 수준으로만 올린다 — 모델이 확신했다고 볼 근거는 없다.
                if (!EvidenceCheck.grounded(request, e.evidence())) {
                    log.debug("근거를 확인할 수 없어 추출값을 버립니다: {} = {} (evidence={})",
                            e.slot(), e.value(), e.evidence());
                    continue;
                }
                confidence = Math.max(confidence, GROUNDED_CONFIDENCE);
            }
            out.put(e.slot(), new Suggestion(e.value(), confidence, e.evidence()));
        }
        return out;
    }

    private String prompt(String request, List<ResolvedSlot> open) {
        String slots = open.stream().map(this::describe).collect(Collectors.joining("\n"));
        return "요청문:\n" + request + "\n\n채울 수 있는 슬롯:\n" + slots;
    }

    private String describe(ResolvedSlot slot) {
        StringBuilder sb = new StringBuilder("- slot: ").append(slot.name());
        sb.append("\n  위치: ").append(slot.entityPath());
        switch (slot.kind()) {
            case SELECT -> sb.append("\n  선택지: ").append(
                    slot.open().options().stream().map(SlotOption::label).toList());
            case MULTIPLICITY -> sb.append("\n  개수 범위: ").append(slot.open().countRange());
            case VALUE -> {
                var var = slot.open().varDef();
                sb.append("\n  타입: ").append(var.type());
                if (var.unit() != null) {
                    sb.append("\n  단위: ").append(var.unit());
                }
                if (!var.range().isEmpty()) {
                    sb.append("\n  허용 범위: ").append(var.range().describe());
                }
            }
        }
        return sb.toString();
    }
}
