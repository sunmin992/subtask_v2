package org.hanbat.ses.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * @param templateId 라우팅을 건너뛰고 템플릿을 직접 지정할 때. 비워 두면 라우터가 고른다.
 */
public record CreateSessionRequest(
        @NotBlank(message = "요청문이 필요합니다.") String request,
        String templateId,
        org.hanbat.ses.dialogue.external.DataUsage usage
) {
    public CreateSessionRequest(String request, String templateId) {
        this(request, templateId, null);
    }
}
