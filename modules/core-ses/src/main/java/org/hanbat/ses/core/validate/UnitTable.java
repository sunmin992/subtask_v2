package org.hanbat.ses.core.validate;

import java.util.Map;
import java.util.Optional;

/**
 * 단위 정규화와 환산.
 *
 * <p>같은 양을 도메인마다 다르게 적는다 — "분"과 "min", "명"과 "인". 두 단위가 같은 것을
 * 말하는지 판단하려면 표준 이름으로 접어야 한다. 차원(시간·인원·길이…)이 다르면
 * 환산 자체가 불가능하고, 그건 배선이 틀렸다는 뜻이다.
 *
 * <p>{@code dialogue} 의 단위 검증기와 {@code core-ses} 의 구조 검사가 같은 표를 봐야
 * 하므로 core 쪽에 둔다. 두 곳에 표를 복사하면 한쪽만 고치는 날이 온다.
 */
public final class UnitTable {

    /** 차원 — 서로 다르면 환산할 수 없다. */
    public enum Dimension {
        TIME, COUNT, LENGTH, MASS, UNKNOWN
    }

    /** 표준 이름 -> (차원, 기준 단위 대비 배수). 시간의 기준은 분, 나머지는 1. */
    private record Unit(Dimension dimension, double factor) {
    }

    private static final Map<String, Unit> UNITS = Map.ofEntries(
            // 시간 — 기준은 분
            Map.entry("초", new Unit(Dimension.TIME, 1.0 / 60)),
            Map.entry("s", new Unit(Dimension.TIME, 1.0 / 60)),
            Map.entry("sec", new Unit(Dimension.TIME, 1.0 / 60)),
            Map.entry("분", new Unit(Dimension.TIME, 1.0)),
            Map.entry("min", new Unit(Dimension.TIME, 1.0)),
            Map.entry("시간", new Unit(Dimension.TIME, 60.0)),
            Map.entry("h", new Unit(Dimension.TIME, 60.0)),
            Map.entry("hour", new Unit(Dimension.TIME, 60.0)),
            Map.entry("hr", new Unit(Dimension.TIME, 60.0)),
            Map.entry("일", new Unit(Dimension.TIME, 1440.0)),
            Map.entry("day", new Unit(Dimension.TIME, 1440.0)),
            // 개수 — 같은 차원이면 배수는 모두 1이다. "명"과 "인"은 같은 것을 말한다.
            Map.entry("명", new Unit(Dimension.COUNT, 1.0)),
            Map.entry("인", new Unit(Dimension.COUNT, 1.0)),
            Map.entry("person", new Unit(Dimension.COUNT, 1.0)),
            Map.entry("개", new Unit(Dimension.COUNT, 1.0)),
            Map.entry("건", new Unit(Dimension.COUNT, 1.0)),
            Map.entry("대", new Unit(Dimension.COUNT, 1.0)),
            Map.entry("실", new Unit(Dimension.COUNT, 1.0)),
            // 길이·질량
            Map.entry("m", new Unit(Dimension.LENGTH, 1.0)),
            Map.entry("km", new Unit(Dimension.LENGTH, 1000.0)),
            Map.entry("kg", new Unit(Dimension.MASS, 1.0)),
            Map.entry("g", new Unit(Dimension.MASS, 0.001)));

    private UnitTable() {
    }

    /** 별칭 표를 적용해 표준 이름으로 접는다. */
    public static String normalize(String unit, Map<String, String> aliases) {
        if (unit == null) {
            return null;
        }
        String trimmed = unit.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String mapped = aliases == null ? trimmed : aliases.getOrDefault(trimmed, trimmed);
        return mapped.trim().toLowerCase();
    }

    public static Dimension dimensionOf(String unit, Map<String, String> aliases) {
        String key = normalize(unit, aliases);
        if (key == null) {
            return Dimension.UNKNOWN;
        }
        Unit u = UNITS.get(key);
        return u == null ? Dimension.UNKNOWN : u.dimension();
    }

    /** 시간 단위를 분으로 환산한 배수. 시간 단위가 아니면 빈 값. */
    public static Optional<Double> minutesPerUnit(String unit, Map<String, String> aliases) {
        String key = normalize(unit, aliases);
        if (key == null) {
            return Optional.empty();
        }
        Unit u = UNITS.get(key);
        return u != null && u.dimension() == Dimension.TIME
                ? Optional.of(u.factor()) : Optional.empty();
    }

    /**
     * 두 단위가 호환되는가.
     *
     * <p>한쪽이라도 비어 있으면 호환으로 본다 — 도메인이 밝히지 않은 것을 근거로
     * 오류를 내지 않는다. 표에 없는 단위끼리는 문자열이 같을 때만 호환으로 본다.
     */
    public static boolean compatible(String a, String b, Map<String, String> aliases) {
        String na = normalize(a, aliases);
        String nb = normalize(b, aliases);
        if (na == null || nb == null) {
            return true;
        }
        if (na.equals(nb)) {
            return true;
        }
        Dimension da = dimensionOf(a, aliases);
        Dimension db = dimensionOf(b, aliases);
        if (da == Dimension.UNKNOWN || db == Dimension.UNKNOWN) {
            // 둘 중 하나라도 표에 없으면 이름이 다른 이상 같다고 볼 근거가 없다.
            return false;
        }
        return da == db;
    }
}
