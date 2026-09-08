package org.hanbat.ses.core.fixture;

import java.util.List;

import org.hanbat.ses.core.model.AspectNode;
import org.hanbat.ses.core.model.CouplingKind;
import org.hanbat.ses.core.model.CouplingSpec;
import org.hanbat.ses.core.model.EntityNode;
import org.hanbat.ses.core.model.IntRange;
import org.hanbat.ses.core.model.MultiAspectNode;
import org.hanbat.ses.core.model.PortDef;
import org.hanbat.ses.core.model.Range;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.model.SpecNode;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.model.VarType;

/**
 * 테스트와 시연에서 공용으로 쓰는 리조트 도메인 SES.
 *
 * <pre>
 * 리조트
 *   ├─ (aspect) 구성 : 방문객생성기 · 집계기
 *   ├─ (spec)   이동설비 : 케이블카 | 셔틀버스 | 모노레일
 *   └─ (spec)   숙박시설 : 호텔 | 콘도
 *
 * 셔틀버스
 *   └─ (multi)  버스 x N
 * </pre>
 *
 * 셔틀버스를 고르면 "버스 몇 대?"가 파생되고, 대수를 정하면 대별 정원 슬롯이 또 파생된다.
 * 파생 슬롯 동작을 한 트리에서 모두 확인할 수 있도록 만든 픽스처다.
 */
public final class ResortSes {

    private ResortSes() {
    }

    public static EntityNode tree() {
        return new EntityNode(
                "ent-resort", "리조트",
                List.of(),
                List.of(coreAspect(), transportSpec(), lodgingSpec()),
                null,
                List.of(),
                List.of(
                        new CouplingSpec(CouplingKind.IC, "ent-arrival", "visitor", "spec-transport", "in"),
                        new CouplingSpec(CouplingKind.IC, "spec-transport", "out", "spec-lodging", "in"),
                        new CouplingSpec(CouplingKind.IC, "spec-lodging", "out", "ent-transducer", "done"),
                        new CouplingSpec(CouplingKind.IC, "ent-arrival", "visitor", "ent-transducer", "arrive")
                ),
                List.of());
    }

    private static AspectNode coreAspect() {
        return new AspectNode("asp-core", "구성", List.of(arrival(), transducer()), List.of());
    }

    private static EntityNode arrival() {
        return EntityNode.leaf("ent-arrival", "방문객생성기", "visitor-generator",
                List.of(
                        VarDef.withDefault("도착간격", VarType.DOUBLE, "분", Range.between(0.1, 120), 2.0),
                        VarDef.withDefault("총방문객", VarType.INT, "명", Range.between(1, 100000), 500)),
                List.of(PortDef.out("visitor")));
    }

    private static EntityNode transducer() {
        return EntityNode.leaf("ent-transducer", "집계기", "transducer",
                List.of(),
                List.of(PortDef.in("arrive"), PortDef.in("done")));
    }

    private static SpecNode transportSpec() {
        return SpecNode.open("spec-transport", "이동설비",
                List.of(cableCar(), shuttle(), monorail()));
    }

    private static EntityNode cableCar() {
        return EntityNode.leaf("ent-cablecar", "케이블카", "cable-car",
                List.of(
                        VarDef.of("정원", VarType.INT, "명", Range.between(1, 200)),
                        VarDef.of("운행간격", VarType.DOUBLE, "분", Range.between(0.5, 30))),
                List.of(PortDef.in("in"), PortDef.out("out")))
                .withAliases(List.of("곤돌라", "리프트"));
    }

    private static EntityNode monorail() {
        return EntityNode.leaf("ent-monorail", "모노레일", "monorail",
                List.of(
                        VarDef.of("정원", VarType.INT, "명", Range.between(1, 300)),
                        VarDef.of("배차간격", VarType.DOUBLE, "분", Range.between(1, 60))),
                List.of(PortDef.in("in"), PortDef.out("out")));
    }

    /** 셔틀버스는 결합 모델이다 — 대수가 정해져야 비로소 대별 슬롯이 생긴다. */
    private static EntityNode shuttle() {
        EntityNode bus = EntityNode.leaf("ent-bus", "버스", "shuttle-bus",
                List.of(
                        VarDef.of("정원", VarType.INT, "명", Range.between(1, 60)),
                        VarDef.withDefault("왕복시간", VarType.DOUBLE, "분", Range.between(1, 120), 12.0)),
                List.of(PortDef.in("in"), PortDef.out("out")));

        return new EntityNode(
                "ent-shuttle", "셔틀버스",
                List.of(),
                List.of(MultiAspectNode.open("multi-bus", "버스", bus, new IntRange(1, 10))),
                null,
                List.of(PortDef.in("in"), PortDef.out("out")),
                List.of(
                        new CouplingSpec(CouplingKind.EIC, "ent-shuttle", "in", "multi-bus", "in"),
                        new CouplingSpec(CouplingKind.EOC, "multi-bus", "out", "ent-shuttle", "out")
                ),
                List.of("셔틀"));
    }

    private static SpecNode lodgingSpec() {
        return SpecNode.open("spec-lodging", "숙박시설", List.of(hotel(), condo()));
    }

    private static EntityNode hotel() {
        return EntityNode.leaf("ent-hotel", "호텔", "lodging",
                List.of(
                        VarDef.of("객실수", VarType.INT, "실", Range.between(1, 2000)),
                        VarDef.withDefault("평균숙박시간", VarType.DOUBLE, "분", Range.between(30, 4320), 600.0)),
                List.of(PortDef.in("in"), PortDef.out("out")));
    }

    private static EntityNode condo() {
        return EntityNode.leaf("ent-condo", "콘도", "lodging",
                List.of(
                        VarDef.of("객실수", VarType.INT, "실", Range.between(1, 2000)),
                        VarDef.withDefault("평균숙박시간", VarType.DOUBLE, "분", Range.between(30, 4320), 720.0)),
                List.of(PortDef.in("in"), PortDef.out("out")));
    }

    /** 모든 미결정 지점을 한 번에 해소하는 표준 답변 (케이블카 경로). */
    public static SesNode cableCarResolved() {
        var engine = new org.hanbat.ses.core.prune.PruningEngine();
        SesNode t = tree();
        t = engine.apply(t, org.hanbat.ses.core.model.SesAnchor.node(
                "리조트/이동설비", org.hanbat.ses.core.model.AxisType.SPECIALIZATION, "spec-transport"), "케이블카");
        t = engine.apply(t, org.hanbat.ses.core.model.SesAnchor.node(
                "리조트/숙박시설", org.hanbat.ses.core.model.AxisType.SPECIALIZATION, "spec-lodging"), "호텔");
        t = engine.apply(t, org.hanbat.ses.core.model.SesAnchor.variable(
                "리조트/이동설비/케이블카", "ent-cablecar", "정원"), 8);
        t = engine.apply(t, org.hanbat.ses.core.model.SesAnchor.variable(
                "리조트/이동설비/케이블카", "ent-cablecar", "운행간격"), 3.0);
        t = engine.apply(t, org.hanbat.ses.core.model.SesAnchor.variable(
                "리조트/숙박시설/호텔", "ent-hotel", "객실수"), 200);
        return t;
    }
}
