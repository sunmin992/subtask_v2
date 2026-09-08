package org.hanbat.ses.template.model;

import java.util.List;

/** 결과 형식. failurePolicy 가 없으면 실패 시 사용자가 아무 정보도 못 받는다. */
public record OutputSpec(
        OutputFormat format,
        List<OutputSection> sections,
        FailurePolicy failurePolicy
) {

    public OutputSpec {
        format = format == null ? OutputFormat.COMPOSITE : format;
        sections = sections == null || sections.isEmpty()
                ? List.of(OutputSection.SUMMARY) : List.copyOf(sections);
        failurePolicy = failurePolicy == null ? FailurePolicy.PARTIAL_RESULT : failurePolicy;
    }

    public static OutputSpec defaults() {
        return new OutputSpec(OutputFormat.COMPOSITE,
                List.of(OutputSection.SUMMARY, OutputSection.TABLE), FailurePolicy.PARTIAL_RESULT);
    }
}
