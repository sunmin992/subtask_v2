package org.hanbat.ses.core.model;

import java.util.List;

/**
 * Specialization — OR 선택. pruning 지점.
 * selectedVariantId 가 null 이면 미결정이며 곧 질문 대상이다.
 */
public record SpecNode(
        String id,
        String name,
        List<EntityNode> variants,
        String selectedVariantId
) implements SesNode {

    public SpecNode {
        variants = variants == null ? List.of() : List.copyOf(variants);
    }

    public static SpecNode open(String id, String name, List<EntityNode> variants) {
        return new SpecNode(id, name, variants, null);
    }

    public boolean isResolved() {
        return selectedVariantId != null;
    }

    public EntityNode selected() {
        if (selectedVariantId == null) {
            return null;
        }
        return variants.stream()
                .filter(v -> v.id().equals(selectedVariantId))
                .findFirst()
                .orElse(null);
    }
}
