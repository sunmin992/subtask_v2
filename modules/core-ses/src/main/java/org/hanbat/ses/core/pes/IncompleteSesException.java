package org.hanbat.ses.core.pes;

import java.util.List;

import org.hanbat.ses.core.validate.ValidationIssue;

/** 미결정 지점이 남은 SES 로 PES 를 확정하려 했을 때. */
public class IncompleteSesException extends RuntimeException {

    private final transient List<ValidationIssue> issues;

    public IncompleteSesException(List<ValidationIssue> issues) {
        super("SES 에 미결정 지점이 남아 있어 PES 를 확정할 수 없습니다: " + issues.size() + "건");
        this.issues = List.copyOf(issues);
    }

    public List<ValidationIssue> issues() {
        return issues;
    }
}
