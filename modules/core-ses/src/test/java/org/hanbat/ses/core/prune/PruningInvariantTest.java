package org.hanbat.ses.core.prune;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import org.hanbat.ses.core.fixture.ResortSes;
import org.hanbat.ses.core.json.SesJson;
import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.MultiAspectNode;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.SpecNode;
import org.hanbat.ses.core.pes.Pes;
import org.hanbat.ses.core.pes.PesBuilder;
import org.hanbat.ses.core.resolve.OpenSlot;
import org.hanbat.ses.core.resolve.SlotResolver;

/**
 * 계획서가 못 박은 pruning 불변식 세 가지를 속성 기반으로 확인한다.
 * 이 셋이 깨지면 같은 대화를 두 번 해도 다른 시나리오가 나온다.
 */
class PruningInvariantTest {

    private final PruningEngine engine = new PruningEngine();
    private final SlotResolver resolver = new SlotResolver();
    private final PesBuilder builder = new PesBuilder();

    @Property(tries = 50)
    void pruning_순서를_바꿔도_최종_PES_는_동일하다(@ForAll("pickOrders") List<Integer> order) {
        Pes shuffled = resolveAll(ResortSes.tree(), order);
        Pes sequential = resolveAll(ResortSes.tree(), List.of());

        assertThat(SesJson.write(shuffled.root())).isEqualTo(SesJson.write(sequential.root()));
    }

    @Property(tries = 50)
    void 모든_결정지점이_해소되면_PES_는_유일하다(@ForAll("pickOrders") List<Integer> order) {
        Pes first = resolveAll(ResortSes.tree(), order);
        Pes second = resolveAll(ResortSes.tree(), order);

        assertThat(SesJson.write(first.root())).isEqualTo(SesJson.write(second.root()));
        assertThat(first.leaves()).hasSameSizeAs(second.leaves());
    }

    @Property(tries = 50)
    void pruning_은_미결정_구조지점을_단조_감소시킨다(@ForAll("pickOrders") List<Integer> order) {
        SesNode tree = ResortSes.tree();
        int previous = Integer.MAX_VALUE;

        for (int step = 0; step < 60; step++) {
            List<OpenSlot> open = resolver.scan(tree);
            int undecided = countUndecided(tree);
            // multi-aspect 복제는 "열린 슬롯" 수를 일시적으로 늘리지만
            // 미결정 구조 지점 자체는 절대 늘어나면 안 된다.
            assertThat(undecided).isLessThanOrEqualTo(previous);
            previous = undecided;
            if (open.isEmpty()) {
                break;
            }
            OpenSlot slot = open.get(pick(order, step, open.size()));
            tree = engine.apply(tree, slot.anchor(), answerFor(slot));
        }

        assertThat(resolver.scan(tree)).isEmpty();
    }

    @Provide
    Arbitrary<List<Integer>> pickOrders() {
        return Arbitraries.integers().between(0, 7).list().ofMinSize(0).ofMaxSize(10);
    }

    /**
     * 열린 슬롯을 매 턴 다시 스캔해 전부 채운다.
     * order 는 "어느 열린 슬롯부터 답하는가"만 바꾼다 — 답 자체는 결정론적이다.
     */
    private Pes resolveAll(SesNode tree, List<Integer> order) {
        SesNode t = tree;
        for (int step = 0; step < 80; step++) {
            List<OpenSlot> open = resolver.scan(t);
            if (open.isEmpty()) {
                break;
            }
            OpenSlot slot = open.get(pick(order, step, open.size()));
            t = engine.apply(t, slot.anchor(), answerFor(slot));
        }
        return builder.build(t);
    }

    private static int pick(List<Integer> order, int step, int size) {
        if (order.isEmpty()) {
            return 0;
        }
        return Math.floorMod(order.get(step % order.size()), size);
    }

    /** 결정론적 답변 — 항상 첫 선택지, 항상 하한값. 그래야 결과 비교가 의미를 갖는다. */
    private Object answerFor(OpenSlot slot) {
        return switch (slot.kind()) {
            case SELECT -> slot.options().get(0).id();
            case MULTIPLICITY -> slot.countRange().min();
            case VALUE -> switch (slot.varDef().type()) {
                case INT -> slot.varDef().range().min() == null
                        ? 1 : slot.varDef().range().min().intValue();
                case DOUBLE -> slot.varDef().range().min() == null
                        ? 1.0 : slot.varDef().range().min();
                case BOOL -> Boolean.TRUE;
                case STRING, ENUM -> "x";
            };
        };
    }

    private int countUndecided(SesNode node) {
        List<Integer> acc = new ArrayList<>();
        walkUndecided(node, acc);
        return acc.size();
    }

    private void walkUndecided(SesNode node, List<Integer> acc) {
        switch (node) {
            case EntityNode e -> e.axes().forEach(a -> walkUndecided(a, acc));
            case AspectNode a -> a.components().forEach(c -> walkUndecided(c, acc));
            case SpecNode s -> {
                if (!s.isResolved()) {
                    acc.add(1);
                }
                s.variants().forEach(v -> walkUndecided(v, acc));
            }
            case MultiAspectNode m -> {
                if (!m.isResolved()) {
                    acc.add(1);
                }
                walkUndecided(m.prototype(), acc);
                m.instances().forEach(i -> walkUndecided(i, acc));
            }
        }
    }
}
