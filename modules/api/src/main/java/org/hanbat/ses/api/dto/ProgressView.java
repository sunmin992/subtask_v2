package org.hanbat.ses.api.dto;

/**
 * 진행률.
 *
 * @param estimatedRemainingTurns 남은 슬롯을 턴당 질문 수로 나눈 값.
 *                                파생 슬롯 때문에 늘어날 수 있으므로 어디까지나 추정이다.
 */
public record ProgressView(int resolved, int open, int estimatedRemainingTurns, double ratio) {
}
