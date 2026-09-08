package org.hanbat.ses.api.dto;

import java.util.List;

import org.hanbat.ses.dialogue.session.Question;

public record QuestionView(
        String slot,
        String text,
        String hint,
        String inputType,
        String unit,
        RangeVal range,
        Object defaultValue,
        List<String> options,
        String entityPath,
        boolean structural
) {

    public record RangeVal(Double min, Double max, List<String> allowed) {
    }

    public static QuestionView of(Question q) {
        return new QuestionView(q.slot(), q.text(), q.hint(), q.inputType().name(), q.unit(),
                q.range() == null || q.range().isEmpty() ? null
                        : new RangeVal(q.range().min(), q.range().max(), q.range().allowed()),
                q.defaultValue(), q.options(), q.entityPath(), q.structural());
    }
}
