package org.hanbat.ses.core.resolve;

import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.MultiAspectNode;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.SpecNode;
import org.hanbat.ses.core.model.VarDef;

/**
 * 진행률 계산 — 이미 결정된 지점의 개수를 센다.
 * 남은 슬롯 수만 보여주면 사용자는 끝이 보이지 않는다고 느낀다.
 */
public final class SesProgress {

    public record Counts(int resolved, int open) {
        public double ratio() {
            int total = resolved + open;
            return total == 0 ? 1.0 : (double) resolved / total;
        }
    }

    public Counts count(SesNode root) {
        int[] acc = new int[2];
        walk(root, acc);
        return new Counts(acc[0], acc[1]);
    }

    private void walk(SesNode node, int[] acc) {
        switch (node) {
            case EntityNode e -> {
                for (VarDef v : e.vars()) {
                    if (v.isOpen()) {
                        acc[1]++;
                    } else {
                        acc[0]++;
                    }
                }
                e.axes().forEach(a -> walk(a, acc));
            }
            case SpecNode s -> {
                if (s.isResolved()) {
                    acc[0]++;
                    if (s.selected() != null) {
                        walk(s.selected(), acc);
                    }
                } else {
                    acc[1]++;
                }
            }
            case MultiAspectNode m -> {
                if (m.isResolved()) {
                    acc[0]++;
                    m.instances().forEach(i -> walk(i, acc));
                } else {
                    acc[1]++;
                }
            }
            case AspectNode a -> a.components().forEach(c -> walk(c, acc));
        }
    }
}
