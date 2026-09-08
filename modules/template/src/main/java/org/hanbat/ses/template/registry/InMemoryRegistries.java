package org.hanbat.ses.template.registry;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.hanbat.ses.template.model.SubtaskTemplate;

/**
 * DB 없이 도는 등록소.
 *
 * <p>Phase 1~3 은 LLM 도 DB 도 없이 검증하는 것이 목표다. E2E 테스트와 standalone
 * 프로파일이 이 구현을 쓰고, 운영 프로파일은 JPA 구현으로 갈아 끼운다.
 */
public final class InMemoryRegistries {

    private InMemoryRegistries() {
    }

    public static final class Templates implements TemplateRegistry {

        private final Map<String, SubtaskTemplate> byKey = new ConcurrentHashMap<>();
        private final Map<String, String> activeVersion = new ConcurrentHashMap<>();

        @Override
        public Optional<SubtaskTemplate> findActive(String id) {
            String version = activeVersion.get(id);
            return version == null ? Optional.empty() : find(id, version);
        }

        @Override
        public Optional<SubtaskTemplate> find(String id, String version) {
            return Optional.ofNullable(byKey.get(id + "@" + version));
        }

        @Override
        public List<SubtaskTemplate> findAllActive() {
            return activeVersion.entrySet().stream()
                    .map(e -> byKey.get(e.getKey() + "@" + e.getValue()))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparingInt((SubtaskTemplate t) -> t.routing().priority())
                            .reversed()
                            .thenComparing(SubtaskTemplate::id))
                    .toList();
        }

        @Override
        public SubtaskTemplate register(SubtaskTemplate template) {
            byKey.put(template.key(), template);
            // 마지막에 등록된 버전을 활성으로 본다.
            activeVersion.put(template.id(), template.version());
            return template;
        }
    }

    public static final class ModelBases implements ModelBaseRegistry {

        private final Map<String, ModelBaseEntry> byId = new ConcurrentHashMap<>();

        @Override
        public Optional<ModelBaseEntry> find(String modelId) {
            return Optional.ofNullable(byId.get(modelId));
        }

        @Override
        public List<ModelBaseEntry> findAll() {
            return byId.values().stream()
                    .sorted(Comparator.comparing(ModelBaseEntry::modelId))
                    .toList();
        }

        @Override
        public ModelBaseEntry register(ModelBaseEntry entry) {
            byId.put(entry.modelId(), entry);
            return entry;
        }
    }

    public static final class SesDefinitions implements SesRegistry {

        private final Map<String, SesDefinition> byId = new ConcurrentHashMap<>();

        @Override
        public Optional<SesDefinition> find(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<SesDefinition> findAll() {
            return byId.values().stream()
                    .sorted(Comparator.comparing(SesDefinition::id))
                    .toList();
        }

        @Override
        public SesDefinition register(SesDefinition definition) {
            byId.put(definition.id(), definition);
            return definition;
        }
    }
}
