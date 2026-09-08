package org.hanbat.ses.core.pes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PES 트리 루트 래퍼. 시뮬레이터가 쓰기 좋게 평탄화 조회를 제공한다. */
public record Pes(PesNode root) {

    /** 리프(원자 모델) 목록을 트리 순서대로. */
    public List<PesNode> leaves() {
        List<PesNode> out = new ArrayList<>();
        collectLeaves(root, out);
        return out;
    }

    private static void collectLeaves(PesNode n, List<PesNode> out) {
        if (n.isLeaf()) {
            out.add(n);
        } else {
            n.children().forEach(c -> collectLeaves(c, out));
        }
    }

    /** entityId 로 노드를 찾는 색인. */
    public Map<String, PesNode> index() {
        Map<String, PesNode> map = new LinkedHashMap<>();
        indexInto(root, map);
        return map;
    }

    private static void indexInto(PesNode n, Map<String, PesNode> map) {
        map.put(n.entityId(), n);
        n.children().forEach(c -> indexInto(c, map));
    }

    public int nodeCount() {
        return index().size();
    }
}
