package org.hanbat.ses.core.model;

import java.util.List;

/**
 * 엔티티 — 트리의 실체 노드.
 *
 * <p>vars 는 엔티티가 가진 변수, axes 는 아래에 달리는 분해 축
 * (AspectNode | SpecNode | MultiAspectNode) 이다.
 * modelRef 는 리프 엔티티가 해석될 원자 모델 id(model_base.model_id)이며 비리프는 null 이다.
 * ports 는 커플링 무결성 검증에 쓰이는 포트 선언이다.
 *
 * <p>aliases 는 이 엔티티를 부르는 다른 이름이다. "케이블카"를 사람들은 "곤돌라"라고도
 * 부르고, LLM 은 요청문의 그 단어를 선택지와 맞춰야 한다. 별칭이 없으면 모델은
 * 추측할 수밖에 없고 — 실제로 한 모델은 "8인승 곤돌라"를 셔틀버스 8대로 읽었다 —
 * 그 추측은 검증으로 잡을 수 없다. 도메인이 아는 동의어는 도메인이 말해 줘야 한다.
 *
 * <p>couplings 는 이 엔티티의 내부 배선이다. AspectNode 의 couplings 가 "이 aspect 가
 * 선언한 컴포넌트끼리의 연결"이라면, 이쪽은 형제 축을 가로지르는 연결
 * (예: 자기 자신의 입력 포트를 multi-aspect 복제본들에게 EIC 로 흘리는 배선)을 담는다.
 * 축이 여럿인 엔티티에서는 어느 aspect 에도 속하지 않는 배선이 반드시 생긴다.
 */
public record EntityNode(
        String id,
        String name,
        List<VarDef> vars,
        List<SesNode> axes,
        String modelRef,
        List<PortDef> ports,
        List<CouplingSpec> couplings,
        List<String> aliases
) implements SesNode {

    public EntityNode {
        vars = vars == null ? List.of() : List.copyOf(vars);
        axes = axes == null ? List.of() : List.copyOf(axes);
        ports = ports == null ? List.of() : List.copyOf(ports);
        couplings = couplings == null ? List.of() : List.copyOf(couplings);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public static EntityNode of(String id, String name, List<SesNode> axes) {
        return new EntityNode(id, name, List.of(), axes, null, List.of(), List.of(), List.of());
    }

    public static EntityNode leaf(String id, String name, String modelRef,
                                  List<VarDef> vars, List<PortDef> ports) {
        return new EntityNode(id, name, vars, List.of(), modelRef, ports, List.of(), List.of());
    }

    public EntityNode withVars(List<VarDef> newVars) {
        return new EntityNode(id, name, newVars, axes, modelRef, ports, couplings, aliases);
    }

    public EntityNode withAxes(List<SesNode> newAxes) {
        return new EntityNode(id, name, vars, newAxes, modelRef, ports, couplings, aliases);
    }

    public EntityNode withPorts(List<PortDef> newPorts) {
        return new EntityNode(id, name, vars, axes, modelRef, newPorts, couplings, aliases);
    }

    public EntityNode withCouplings(List<CouplingSpec> newCouplings) {
        return new EntityNode(id, name, vars, axes, modelRef, ports, newCouplings, aliases);
    }

    /** 이 엔티티를 부르는 다른 이름들을 붙인다. */
    public EntityNode withAliases(List<String> newAliases) {
        return new EntityNode(id, name, vars, axes, modelRef, ports, couplings, newAliases);
    }

    /**
     * 이 토큰이 이 엔티티를 가리키는가 — id, 이름, 별칭을 모두 본다.
     *
     * <p>공백과 대소문자를 무시한다. 사용자가 "케이블 카"라고 띄어 쓰거나
     * LLM 이 "Gondola"로 돌려주는 경우가 흔하다.
     */
    public boolean matchesName(String token) {
        if (token == null) {
            return false;
        }
        String needle = squash(token);
        if (needle.isEmpty()) {
            return false;
        }
        if (squash(id).equals(needle) || squash(name).equals(needle)) {
            return true;
        }
        return aliases.stream().anyMatch(a -> squash(a).equals(needle));
    }

    private static String squash(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    public boolean isLeaf() {
        return axes.isEmpty();
    }

    public VarDef var(String varName) {
        return vars.stream().filter(v -> v.name().equals(varName)).findFirst().orElse(null);
    }

    public boolean hasPort(String portName, PortDirection direction) {
        return ports.stream().anyMatch(p -> p.name().equals(portName) && p.direction() == direction);
    }
}
