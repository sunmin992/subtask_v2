package org.hanbat.ses.scenario.exec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.pes.Pes;
import org.hanbat.ses.core.pes.PesBuilder;
import org.hanbat.ses.core.pes.PesNode;
import org.hanbat.ses.scenario.model.Scenario;
import org.hanbat.ses.template.model.SimulatorConfig;
import org.hanbat.ses.template.model.SubtaskTemplate;
import org.springframework.stereotype.Component;

/**
 * 확정된 SES 를 시나리오로 굳힌다.
 *
 * <p>요약 문장을 여기서 만드는 이유는 PES 를 방금 훑었기 때문이다. 나중에 다시
 * 트리를 돌며 문장을 만들면 실제 실행될 구조와 설명이 어긋날 여지가 생긴다.
 */
@Component
public class ScenarioBuilder {

    private final PesBuilder pesBuilder = new PesBuilder();

    public Scenario build(UUID sessionId, SubtaskTemplate template, SesNode resolvedSes) {
        Pes pes = pesBuilder.build(resolvedSes);
        Map<String, Object> params = flattenParams(pes);
        SimulatorConfig sim = template.execution().simulator();

        return new Scenario(UUID.randomUUID(), sessionId, template.id(), pes, params, sim,
                template.output(), summarize(pes, sim), Instant.now());
    }

    /**
     * "리조트/케이블카.정원" 형태의 평탄한 파라미터 맵. 감사와 표시에 쓴다.
     *
     * <p>이름이 같은 형제(버스 3대)에는 인덱스를 붙인다. 붙이지 않으면 경로가 겹쳐
     * 마지막 값이 앞선 값들을 덮어쓰고, 3대를 서로 다르게 설정한 사실이 조용히 사라진다.
     */
    public Map<String, Object> flattenParams(Pes pes) {
        Map<String, Object> out = new LinkedHashMap<>();
        flatten(pes.root(), pes.root().name(), out);
        return out;
    }

    private void flatten(PesNode node, String path, Map<String, Object> out) {
        node.params().forEach((k, v) -> out.put(path + "." + k, v));

        Map<String, Long> nameCount = node.children().stream().collect(
                java.util.stream.Collectors.groupingBy(PesNode::name,
                        java.util.stream.Collectors.counting()));
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (PesNode child : node.children()) {
            String label = child.name();
            if (nameCount.getOrDefault(label, 0L) > 1) {
                int i = seen.merge(label, 0, (a, b) -> a + 1);
                label = label + "[" + i + "]";
            }
            flatten(child, path + "/" + label, out);
        }
    }

    private String summarize(Pes pes, SimulatorConfig sim) {
        List<String> parts = new ArrayList<>();
        describe(pes.root(), parts);
        String composition = parts.isEmpty() ? "구성 없음" : String.join(" + ", parts);
        return "%s(%s)를 %s 동안 시뮬레이션합니다."
                .formatted(pes.root().name(), composition, humanDuration(sim.horizon()));
    }

    private void describe(PesNode node, List<String> parts) {
        for (PesNode child : node.children()) {
            if (child.isLeaf() && !child.params().isEmpty()) {
                String values = child.params().entrySet().stream()
                        .map(e -> e.getKey() + " " + format(e.getValue()))
                        .reduce((a, b) -> a + ", " + b).orElse("");
                parts.add(child.name() + " " + values);
            }
            describe(child, parts);
        }
    }

    private String format(Object value) {
        if (value instanceof Double d && d == Math.rint(d)) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(value);
    }

    /** horizon 은 도메인의 기본 시간 단위(분)로 해석한다. */
    private String humanDuration(double horizon) {
        if (horizon >= 1440 && horizon % 1440 == 0) {
            return (long) (horizon / 1440) + "일";
        }
        if (horizon >= 60 && horizon % 60 == 0) {
            return (long) (horizon / 60) + "시간";
        }
        return (long) horizon + "분";
    }
}
