package org.hanbat.ses.api.dto;

import java.util.List;
import java.util.Map;

/**
 * SES pruning 상태를 클라이언트가 그릴 수 있는 형태로 접은 것.
 *
 * <p>전체 트리를 그대로 내보내면 선택되지 않은 변형까지 전부 실려 나가 응답이 커지고,
 * 무엇이 정해졌는지가 오히려 안 보인다. 결정 지점과 그 상태만 남긴다.
 *
 * @param resolved 결정된 값. spec 은 선택된 변형 이름, multi 는 개수.
 * @param pending  이 노드 아래에서 아직 답하지 않은 항목.
 */
public record SesSnapshotNode(
        String id,
        String name,
        String type,
        String resolved,
        List<String> pending,
        Map<String, Object> params,
        List<SesSnapshotNode> children
) {

    public SesSnapshotNode {
        pending = pending == null ? List.of() : List.copyOf(pending);
        params = params == null ? Map.of() : Map.copyOf(params);
        children = children == null ? List.of() : List.copyOf(children);
    }
}
