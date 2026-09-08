package org.hanbat.ses.core.pes;

import java.util.List;

import org.hanbat.ses.core.validate.ValidationIssue;

/**
 * PES 사전 빌드 결과.
 *
 * <p>검증 단계에서는 예외 대신 issue 목록이 필요하다. 미해결 지점이 남아 있으면
 * pes 가 null 이고 issues 에 이유가 담긴다.
 */
public record PesBuildResult(Pes pes, List<ValidationIssue> issues) {

    public boolean ok() {
        return pes != null && issues.stream().noneMatch(ValidationIssue::isError);
    }
}
