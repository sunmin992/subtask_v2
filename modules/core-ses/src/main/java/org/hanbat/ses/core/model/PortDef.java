package org.hanbat.ses.core.model;

/**
 * 포트 선언.
 *
 * <p>unit 은 이 포트로 흐르는 양의 단위다("명", "kg", "건"). 커플링으로 이어진 두 포트가
 * 서로 다른 단위를 말하면 배선 자체가 틀린 것이고, 그건 실행해도 오류로 나타나지 않는다 —
 * 숫자는 흘러가고 결과만 조용히 무의미해진다. 그래서 확정 전에 대조한다(FR-403).
 *
 * <p>단위를 비워 두면 그 포트는 대조 대상에서 빠진다. 도메인이 단위를 말하지 않았는데
 * 시스템이 추측해서 오류를 내면 잘못된 도메인보다 나쁘다.
 */
public record PortDef(String name, PortDirection direction, String dataType, String unit) {

    public PortDef {
        dataType = dataType == null || dataType.isBlank() ? "any" : dataType;
    }

    public static PortDef in(String name) {
        return new PortDef(name, PortDirection.IN, "any", null);
    }

    public static PortDef out(String name) {
        return new PortDef(name, PortDirection.OUT, "any", null);
    }

    /** 단위를 밝힌 입력 포트. */
    public static PortDef in(String name, String unit) {
        return new PortDef(name, PortDirection.IN, "any", unit);
    }

    /** 단위를 밝힌 출력 포트. */
    public static PortDef out(String name, String unit) {
        return new PortDef(name, PortDirection.OUT, "any", unit);
    }

    public boolean hasUnit() {
        return unit != null && !unit.isBlank();
    }
}
