package org.hanbat.ses.api.dto;

import java.util.Map;

import jakarta.validation.constraints.NotNull;

/** 슬롯 이름 -> 값. 한 턴에 여러 슬롯을 함께 보낼 수 있다. */
public record AnswerRequest(@NotNull Map<String, Object> answers) {
}
