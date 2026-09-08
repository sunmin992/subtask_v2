package org.hanbat.ses.core.model;

import java.util.List;

/** 값 도메인. 수치형은 min/max 를, ENUM 은 allowed 를 쓴다. */
public record Range(Double min, Double max, List<String> allowed) {

    public Range {
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }

    public static Range none() {
        return new Range(null, null, List.of());
    }

    public static Range between(double min, double max) {
        return new Range(min, max, List.of());
    }

    public static Range oneOf(List<String> allowed) {
        return new Range(null, null, allowed);
    }

    public boolean containsNumber(double v) {
        if (min != null && v < min) {
            return false;
        }
        return max == null || !(v > max);
    }

    public boolean containsToken(String v) {
        return allowed.isEmpty() || allowed.contains(v);
    }

    public boolean isEmpty() {
        return min == null && max == null && allowed.isEmpty();
    }

    public String describe() {
        if (!allowed.isEmpty()) {
            return String.join(" | ", allowed);
        }
        if (min == null && max == null) {
            return "제한 없음";
        }
        return "%s ~ %s".formatted(min == null ? "-inf" : min, max == null ? "+inf" : max);
    }
}
