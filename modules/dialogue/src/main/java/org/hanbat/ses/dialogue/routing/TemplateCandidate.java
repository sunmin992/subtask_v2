package org.hanbat.ses.dialogue.routing;

/**
 * 라우팅 후보 하나.
 *
 * @param score 이 후보가 얼마나 맞아 보이는지. LLM 신뢰도 또는 키워드 일치 점수.
 * @param why   왜 후보로 올랐는지 — 사용자가 고를 때 판단 근거가 된다.
 */
public record TemplateCandidate(String templateId, String name, String intent,
                                double score, String why) {
}
