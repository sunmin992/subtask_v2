package org.hanbat.ses.core.model;

/**
 * 엔티티 변수 정의.
 *
 * <p>unit 은 "명", "m/s", "분" 처럼 표기하며 단위 일관성 검증에 사용한다.
 * value 가 null 이면 미충전이고, defaultValue 마저 없으면 질문 대상이 된다.
 */
public record VarDef(
        String name,
        VarType type,
        String unit,
        Object value,
        Object defaultValue,
        boolean inferable,
        Range range
) {

    public VarDef {
        range = range == null ? Range.none() : range;
    }

    public static VarDef of(String name, VarType type, String unit, Range range) {
        return new VarDef(name, type, unit, null, null, false, range);
    }

    public static VarDef withDefault(String name, VarType type, String unit, Range range, Object defaultValue) {
        return new VarDef(name, type, unit, null, defaultValue, true, range);
    }

    public VarDef withValue(Object newValue) {
        return new VarDef(name, type, unit, newValue, defaultValue, inferable, range);
    }

    /** 미충전 슬롯인가 — 값도 기본값도 없을 때만 질문 대상이 된다. */
    public boolean isOpen() {
        return value == null && defaultValue == null;
    }

    /** 값이 없으면 기본값으로 대체한 실효값. */
    public Object effectiveValue() {
        return value != null ? value : defaultValue;
    }
}
