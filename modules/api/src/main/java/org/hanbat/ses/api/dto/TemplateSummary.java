package org.hanbat.ses.api.dto;

import java.util.List;

import org.hanbat.ses.template.model.SubtaskTemplate;

public record TemplateSummary(
        String id,
        String version,
        String name,
        String intent,
        List<String> triggers,
        int priority,
        int slotCount,
        String sesDefinitionId
) {

    public static TemplateSummary of(SubtaskTemplate t) {
        return new TemplateSummary(t.id(), t.version(), t.name(),
                t.routing().intentDescription(), t.routing().triggerPatterns(),
                t.routing().priority(), t.slots().size(), t.binding().sesDefinitionId());
    }
}
