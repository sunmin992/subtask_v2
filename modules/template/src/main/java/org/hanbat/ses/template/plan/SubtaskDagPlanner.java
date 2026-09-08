package org.hanbat.ses.template.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hanbat.ses.template.model.Dependency;
import org.hanbat.ses.template.model.SubtaskTemplate;
import org.hanbat.ses.template.registry.TemplateRegistry;

/**
 * 서브태스크 DAG 구성 (FR-103).
 *
 * <p>템플릿은 {@code Routing.prerequisites} 로 선행 서브태스크를 선언할 수 있다.
 * "리조트 수용력 검토"는 리조트 구성이 먼저 정해져 있어야 의미가 있다. 이 클래스는
 * 목표 템플릿에서 시작해 선행 관계를 닫고, 실행 가능한 순서로 정렬한다.
 *
 * <p><b>순환은 오류다.</b> A가 B를, B가 A를 선행으로 두면 어느 것도 먼저 실행할 수 없다.
 * 위상 정렬이 남는 노드를 보고하도록 두면 "왜 실행이 안 되지"로만 드러나므로,
 * 순환 자체를 이름과 함께 보고한다.
 *
 * <p>이 클래스는 <b>순서를 계산</b>할 뿐 실행하지는 않는다. 여러 서브태스크를 이어 실행하며
 * 선행 산출물을 참조 슬롯으로 넘기는 일은 별도 기능이고, 그것 없이도 "무엇을 먼저 해야
 * 하는가"를 사용자에게 알려 주는 값은 이미 있다.
 */
public final class SubtaskDagPlanner {

    private final TemplateRegistry templates;

    public SubtaskDagPlanner(TemplateRegistry templates) {
        this.templates = templates;
    }

    /** 실행 계획. order 는 선행이 앞에 오도록 정렬된 템플릿 id 목록. */
    public record Plan(String goal, List<Step> order, List<String> missing, List<String> cycle) {

        public Plan {
            order = order == null ? List.of() : List.copyOf(order);
            missing = missing == null ? List.of() : List.copyOf(missing);
            cycle = cycle == null ? List.of() : List.copyOf(cycle);
        }

        /** 이 계획을 그대로 실행할 수 있는가. 응답에도 실어 보낸다. */
        @com.fasterxml.jackson.annotation.JsonProperty("executable")
        public boolean executable() {
            return missing.isEmpty() && cycle.isEmpty();
        }

        /** 목표 하나뿐인가 — 선행이 없으면 DAG 를 구성할 이유가 없다. */
        @com.fasterxml.jackson.annotation.JsonProperty("single")
        public boolean single() {
            return order.size() <= 1;
        }
    }

    /**
     * @param required false 인 선행은 계획에 넣되 없어도 실행을 막지 않는다.
     */
    public record Step(String templateId, String name, boolean required, String reason) {
    }

    /**
     * 목표 템플릿의 선행 관계를 닫아 실행 순서를 만든다.
     *
     * @param goalTemplateId 최종적으로 수행하려는 서브태스크
     */
    public Plan plan(String goalTemplateId) {
        Map<String, SubtaskTemplate> resolved = new LinkedHashMap<>();
        Map<String, Dependency> declaredAs = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        // 선행 관계를 너비 우선으로 닫는다.
        Deque<String> queue = new ArrayDeque<>();
        queue.add(goalTemplateId);
        Set<String> seen = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!seen.add(id)) {
                continue;
            }
            var found = templates.findActive(id);
            if (found.isEmpty()) {
                missing.add(id);
                continue;
            }
            SubtaskTemplate t = found.get();
            resolved.put(id, t);
            for (Dependency d : t.routing().prerequisites()) {
                declaredAs.putIfAbsent(d.taskId(), d);
                queue.add(d.taskId());
            }
        }

        // 진입 차수 기반 위상 정렬. 선행이 먼저 오도록 한다.
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        resolved.keySet().forEach(id -> indegree.putIfAbsent(id, 0));
        for (SubtaskTemplate t : resolved.values()) {
            for (Dependency d : t.routing().prerequisites()) {
                if (!resolved.containsKey(d.taskId())) {
                    continue;
                }
                indegree.merge(t.id(), 1, Integer::sum);
                dependents.computeIfAbsent(d.taskId(), k -> new ArrayList<>()).add(t.id());
            }
        }

        List<Step> order = new ArrayList<>();
        Deque<String> ready = new ArrayDeque<>(indegree.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey)
                .toList());
        while (!ready.isEmpty()) {
            String id = ready.poll();
            SubtaskTemplate t = resolved.get(id);
            Dependency d = declaredAs.get(id);
            order.add(new Step(id, t.name(),
                    d == null || d.required(),
                    d == null ? "목표 서브태스크" : d.reason()));
            for (String next : dependents.getOrDefault(id, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }

        // 정렬에서 빠진 노드가 곧 순환이다.
        List<String> cycle = indegree.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(Map.Entry::getKey)
                .toList();

        return new Plan(goalTemplateId, order, missing, cycle);
    }
}
