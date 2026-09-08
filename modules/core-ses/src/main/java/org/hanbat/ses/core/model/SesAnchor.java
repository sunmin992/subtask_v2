package org.hanbat.ses.core.model;

/**
 * 슬롯이 SES 트리의 어디에 붙는지 — 템플릿과 SES 를 잇는 유일한 고리.
 *
 * <p>entityPath 는 사람이 읽는 경로("리조트/이동설비")이고 매칭에는 쓰지 않는다.
 * 실제 매칭 키는 targetNodeId 이며, multi-aspect 복제본은 인스턴스 접미사가 붙는다.
 * VARIABLE 축일 때는 targetNodeId 가 소유 엔티티 id 이고 varName 이 대상 변수다.
 */
public record SesAnchor(
        String entityPath,
        AxisType axis,
        String targetNodeId,
        String varName
) {

    public static final char INSTANCE_SEPARATOR = '#';

    public static SesAnchor node(String entityPath, AxisType axis, String targetNodeId) {
        return new SesAnchor(entityPath, axis, targetNodeId, null);
    }

    public static SesAnchor variable(String entityPath, String entityId, String varName) {
        return new SesAnchor(entityPath, AxisType.VARIABLE, entityId, varName);
    }

    /**
     * 템플릿 앵커와의 비교용 키. multi-aspect 복제본을 원본 프로토타입과
     * 같은 템플릿 슬롯에 매칭시키기 위해 인스턴스 접미사를 떼어낸다.
     */
    public String prototypeNodeId() {
        return stripInstanceSuffix(targetNodeId);
    }

    public static String stripInstanceSuffix(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        int i = nodeId.indexOf(INSTANCE_SEPARATOR);
        return i < 0 ? nodeId : nodeId.substring(0, i);
    }
}
