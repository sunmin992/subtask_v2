package org.hanbat.ses.api.dto;

import org.hanbat.ses.core.validate.ValidationIssue;

public record IssueView(String slot, String code, String message, String severity) {

    public static IssueView of(ValidationIssue issue) {
        return new IssueView(issue.slot(), issue.code(), issue.message(), issue.severity().name());
    }
}
