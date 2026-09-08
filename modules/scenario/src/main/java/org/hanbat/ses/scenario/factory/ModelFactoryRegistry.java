package org.hanbat.ses.scenario.factory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.hanbat.ses.core.pes.PesNode;
import org.hanbat.ses.devs.model.AtomicModel;
import org.springframework.stereotype.Component;

/**
 * modelRef -> AtomicModelFactory 색인.
 *
 * <p>리플렉션으로 FQCN 을 부르는 대신 Spring 이 모아 준 구현체 목록을 쓴다.
 * 클래스 이름을 DB 문자열로 들고 있으면 리팩터링 한 번에 조용히 깨지고,
 * 깨진 사실은 실행 시점에야 드러난다.
 */
@Component
public class ModelFactoryRegistry {

    private final Map<String, AtomicModelFactory> factories;

    public ModelFactoryRegistry(List<AtomicModelFactory> discovered) {
        this.factories = discovered.stream().collect(Collectors.toMap(
                AtomicModelFactory::modelId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    public AtomicModel<?> create(ModelSpec spec, String modelRef) {
        AtomicModelFactory factory = factories.get(modelRef);
        if (factory == null) {
            throw new UnknownModelException(modelRef, factories.keySet());
        }
        return factory.create(spec);
    }

    /** 복제본이 아닌 단독 컴포넌트를 만들 때. */
    public AtomicModel<?> create(PesNode leaf, double horizon, Long seed) {
        return create(ModelSpec.single(leaf.entityId(), leaf.name(), leaf.params(), horizon, seed),
                leaf.modelRef());
    }

    public Set<String> knownModels() {
        return factories.keySet();
    }

    public boolean supports(String modelRef) {
        return factories.containsKey(modelRef);
    }
}
