package org.hanbat.ses.scenario.output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.hanbat.ses.devs.engine.SimulationResult;
import org.hanbat.ses.devs.engine.TraceEntry;
import org.hanbat.ses.scenario.model.Scenario;
import org.hanbat.ses.template.model.OutputSection;
import org.hanbat.ses.template.model.OutputSpec;
import org.springframework.stereotype.Component;

/**
 * 실행 결과를 OutputSpec 이 요구한 형태로 만든다.
 *
 * <p>부분 결과 처리가 여기 핵심이다. 타임아웃이나 zeno 로 잘린 실행도 얻은 만큼은
 * 보여 준다. 실패했다고 빈 응답을 주면 사용자는 무엇을 고쳐야 할지 알 수 없고,
 * 대개 문제는 방금 답한 파라미터 하나에 있다.
 */
@Component
public class ResultFormatter {

    public Map<String, Object> format(Scenario scenario, SimulationResult result) {
        OutputSpec spec = scenario.output();
        Map<String, Object> out = new LinkedHashMap<>();

        out.put("dataEvidence", scenario.dataEvidence());

        out.put("status", result.status().name());
        out.put("partial", result.partial());
        out.put("endTime", result.endTime());
        out.put("eventCount", result.eventCount());
        if (result.note() != null) {
            out.put("note", result.note());
        }

        for (OutputSection section : spec.sections()) {
            switch (section) {
                case SUMMARY -> out.put("summary", summary(scenario, result));
                case TABLE -> out.put("table", table(result));
                case CHART -> out.put("chart", chart(result));
                case TRACE -> out.put("trace", trace(result));
                case RAW -> out.put("raw", result.statistics());
            }
        }
        return out;
    }

    private String summary(Scenario scenario, SimulationResult result) {
        StringBuilder sb = new StringBuilder(scenario.summary());
        sb.append('\n');
        if (scenario.dataEvidence().get("notice") instanceof String notice) {
            sb.append(notice).append('\n');
        }

        Map<String, Object> stats = result.statistics();
        appendSum(sb, stats, "도착 방문객", ".arrived");
        appendSum(sb, stats, "수송 인원", ".carried");
        appendMax(sb, stats, "최대 대기열", ".maxQueue");
        appendSum(sb, stats, "투숙", ".admitted");
        appendSum(sb, stats, "만실 거절", ".rejected");
        appendFirst(sb, stats, "평균 체류시간", ".avgTurnaround", "분");

        if (result.partial()) {
            sb.append("\n(주의: 실행이 끝까지 진행되지 않아 부분 결과입니다.)");
        }
        return sb.toString().trim();
    }

    /**
     * 같은 지표를 가진 컴포넌트를 합산한다.
     *
     * <p>첫 번째 값만 집으면 버스가 3대일 때 "수송 인원 167"이 나와, 방문객 500명 중
     * 167명만 실은 것처럼 읽힌다. 실제로는 세 대가 나눠 실어 합계가 500명이다.
     */
    private void appendSum(StringBuilder sb, Map<String, Object> stats,
                           String label, String suffix) {
        List<Double> values = valuesOf(stats, suffix);
        if (values.isEmpty()) {
            return;
        }
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        sb.append("- ").append(label).append(": ").append(format(sum));
        if (values.size() > 1) {
            sb.append(" (").append(values.size()).append("개 컴포넌트 합계)");
        }
        sb.append('\n');
    }

    /** 대기열처럼 더해도 뜻이 없는 지표는 최댓값을 보여 준다. */
    private void appendMax(StringBuilder sb, Map<String, Object> stats,
                           String label, String suffix) {
        valuesOf(stats, suffix).stream().mapToDouble(Double::doubleValue).max().ifPresent(max ->
                sb.append("- ").append(label).append(": ").append(format(max)).append('\n'));
    }

    /** 측정하지 못해 0 으로 남은 지표는 아예 싣지 않는다 — 0 은 "0이었다"로 읽힌다. */
    private void appendFirst(StringBuilder sb, Map<String, Object> stats,
                             String label, String suffix, String unit) {
        List<Double> values = valuesOf(stats, suffix);
        if (values.isEmpty() || values.get(0) == 0.0) {
            return;
        }
        sb.append("- ").append(label).append(": ").append(format(values.get(0)))
                .append(unit).append('\n');
    }

    private List<Double> valuesOf(Map<String, Object> stats, String suffix) {
        List<Double> out = new ArrayList<>();
        stats.forEach((k, v) -> {
            if (k.endsWith(suffix) && v instanceof Number n) {
                out.add(n.doubleValue());
            }
        });
        return out;
    }

    private String format(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : "%.1f".formatted(v);
    }

    /** 컴포넌트별 지표 표. 통계 키 "컴포넌트.지표" 를 두 축으로 쪼갠다. */
    private List<Map<String, Object>> table(SimulationResult result) {
        Map<String, Map<String, Object>> byComponent = new TreeMap<>();
        result.statistics().forEach((k, v) -> {
            int dot = k.lastIndexOf('.');
            if (dot <= 0) {
                return;
            }
            byComponent.computeIfAbsent(k.substring(0, dot), c -> new LinkedHashMap<>())
                    .put(k.substring(dot + 1), v);
        });
        List<Map<String, Object>> rows = new ArrayList<>();
        byComponent.forEach((component, metrics) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("component", component);
            row.putAll(metrics);
            rows.add(row);
        });
        return rows;
    }

    /** 시각별 이벤트 수 — 클라이언트가 그대로 그릴 수 있는 형태. */
    private Map<String, Object> chart(SimulationResult result) {
        Map<Double, Integer> byTime = new TreeMap<>();
        for (TraceEntry e : result.trace()) {
            byTime.merge(e.time(), e.imminents().size(), Integer::sum);
        }
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", "line");
        chart.put("xLabel", "시각");
        chart.put("yLabel", "이벤트 수");
        chart.put("x", new ArrayList<>(byTime.keySet()));
        chart.put("y", new ArrayList<>(byTime.values()));
        return chart;
    }

    private List<Map<String, Object>> trace(SimulationResult result) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TraceEntry e : result.trace()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", e.time());
            row.put("imminents", e.imminents());
            row.put("messages", e.messages().entrySet().stream()
                    .map(entry -> entry.getKey() + " <- " + entry.getValue().size() + "건")
                    .toList());
            rows.add(row);
        }
        return rows;
    }
}
