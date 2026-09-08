package org.hanbat.ses.template.model;

/**
 * 질문 문구.
 *
 * @param text      최초 질문.
 * @param reaskText 검증 실패 후 다시 물을 때. 비어 있으면 text 를 재사용한다.
 * @param hint      단위나 예시 같은 보조 설명.
 */
public record QuestionSpec(String text, String reaskText, String hint) {

    public static QuestionSpec of(String text) {
        return new QuestionSpec(text, null, null);
    }

    public String reaskOrText() {
        return reaskText == null || reaskText.isBlank() ? text : reaskText;
    }
}
