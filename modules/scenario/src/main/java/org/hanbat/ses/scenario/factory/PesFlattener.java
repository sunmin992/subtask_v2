package org.hanbat.ses.scenario.factory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hanbat.ses.core.model.CouplingKind;
import org.hanbat.ses.core.model.CouplingSpec;
import org.hanbat.ses.core.pes.Pes;
import org.hanbat.ses.core.pes.PesNode;
import org.hanbat.ses.devs.model.Coupling;
import org.hanbat.ses.devs.model.ModelRef;
import org.hanbat.ses.devs.engine.SimulationModel;
import org.springframework.stereotype.Component;

/**
 * 계층 PES 를 평탄한 실행 모델로 접는다.
 *
 * <p>핵심은 <b>EIC/EOC 를 따라가며 배선을 끝단까지 잇는 것</b>이다.
 * "방문객생성기 -> 셔틀버스" 라는 한 줄은 셔틀버스가 결합 모델이므로 그대로 실행할 수 없다.
 * 셔틀버스의 EIC 를 따라 내려가면 버스 3대에 각각 닿고, 그제야 원자 모델끼리의 배선이 된다.
 * 이 전개를 하지 않으면 메시지가 결합 모델에서 증발한다.
 */
@Component
public class PesFlattener {

    private final ModelFactoryRegistry registry;

    public PesFlattener(ModelFactoryRegistry registry) {
        this.registry = registry;
    }

    public SimulationModel flatten(Pes pes, double horizon, Long seed) {
        Map<String, PesNode> index = pes.index();

        List<ModelRef> components = new ArrayList<>();
        collectComponents(pes.root(), horizon, seed, components);

        List<Coupling> couplings = new ArrayList<>();
        collectCouplings(pes.root(), index, couplings);

        return SimulationModel.of(pes.root().entityId(), components, dedupe(couplings));
    }

    /**
     * 리프를 원자 모델로 만들면서 복제본에게 자기 순번과 총 개수를 알려 준다.
     *
     * <p>같은 프로토타입에서 나온 형제가 몇인지는 부모를 봐야 알 수 있다.
     * 이 정보가 없으면 EIC 브로드캐스트를 받은 버스 세 대가 <em>같은 승객을 각각</em> 실어
     * 방문객 500명이 숙박시설에 1500명으로 도착한다.
     */
    private void collectComponents(PesNode node, double horizon, Long seed, List<ModelRef> out) {
        // 같은 프로토타입에서 복제된 형제를 센다.
        Map<String, Integer> siblingCount = new LinkedHashMap<>();
        for (PesNode child : node.children()) {
            siblingCount.merge(prototypeOf(child.entityId()), 1, Integer::sum);
        }
        for (PesNode child : node.children()) {
            if (child.isLeaf()) {
                int count = siblingCount.getOrDefault(prototypeOf(child.entityId()), 1);
                ModelSpec spec = new ModelSpec(child.entityId(), child.name(), child.params(),
                        horizon, seed, instanceIndexOf(child.entityId()), count);
                out.add(new ModelRef(child.entityId(),
                        registry.create(spec, child.modelRef())));
            } else {
                collectComponents(child, horizon, seed, out);
            }
        }
        if (node.isLeaf() && out.stream().noneMatch(m -> m.id().equals(node.entityId()))) {
            // 루트 자체가 리프인 경우 (컴포넌트가 하나뿐인 모델).
            out.add(new ModelRef(node.entityId(), registry.create(
                    ModelSpec.single(node.entityId(), node.name(), node.params(), horizon, seed),
                    node.modelRef())));
        }
    }

    private static String prototypeOf(String entityId) {
        int i = entityId.indexOf('#');
        return i < 0 ? entityId : entityId.substring(0, i);
    }

    private static int instanceIndexOf(String entityId) {
        int i = entityId.indexOf('#');
        if (i < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(entityId.substring(i + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void collectCouplings(PesNode node, Map<String, PesNode> index, List<Coupling> out) {
        for (CouplingSpec c : node.couplings()) {
            // EIC/EOC 는 전개 과정에서 소비된다. 여기서는 같은 층의 IC 만 시작점으로 삼는다.
            if (c.kind() != CouplingKind.IC) {
                continue;
            }
            for (Endpoint from : expandSource(c.fromEntity(), c.fromPort(), index)) {
                for (Endpoint to : expandTarget(c.toEntity(), c.toPort(), index)) {
                    out.add(new Coupling(from.entityId(), from.port(), to.entityId(), to.port()));
                }
            }
        }
        node.children().forEach(child -> collectCouplings(child, index, out));
    }

    private record Endpoint(String entityId, String port) {
    }

    /** 결합 모델의 출력 포트를 EOC 를 따라 내려가 원자 모델의 출력 포트로 바꾼다. */
    private List<Endpoint> expandSource(String entityId, String port, Map<String, PesNode> index) {
        PesNode node = index.get(entityId);
        if (node == null || node.isLeaf()) {
            return List.of(new Endpoint(entityId, port));
        }
        List<Endpoint> out = new ArrayList<>();
        for (CouplingSpec c : node.couplings()) {
            if (c.kind() == CouplingKind.EOC
                    && c.toEntity().equals(entityId) && c.toPort().equals(port)) {
                out.addAll(expandSource(c.fromEntity(), c.fromPort(), index));
            }
        }
        return out;
    }

    /** 결합 모델의 입력 포트를 EIC 를 따라 내려가 원자 모델의 입력 포트로 바꾼다. */
    private List<Endpoint> expandTarget(String entityId, String port, Map<String, PesNode> index) {
        PesNode node = index.get(entityId);
        if (node == null || node.isLeaf()) {
            return List.of(new Endpoint(entityId, port));
        }
        List<Endpoint> out = new ArrayList<>();
        for (CouplingSpec c : node.couplings()) {
            if (c.kind() == CouplingKind.EIC
                    && c.fromEntity().equals(entityId) && c.fromPort().equals(port)) {
                out.addAll(expandTarget(c.toEntity(), c.toPort(), index));
            }
        }
        return out;
    }

    /** 같은 배선이 여러 경로로 두 번 잡히면 메시지가 두 번 배달된다. */
    private List<Coupling> dedupe(List<Coupling> couplings) {
        Map<String, Coupling> unique = new LinkedHashMap<>();
        for (Coupling c : couplings) {
            unique.putIfAbsent(
                    c.fromModel() + "|" + c.fromPort() + "|" + c.toModel() + "|" + c.toPort(), c);
        }
        return List.copyOf(unique.values());
    }
}
