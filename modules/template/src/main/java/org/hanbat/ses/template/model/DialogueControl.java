package org.hanbat.ses.template.model;

/**
 * 대화 제어.
 *
 * @param maxQuestionsPerTurn 의존성이 없는 슬롯을 한 턴에 몇 개까지 묶어 물을지.
 *                            이 값이 1이면 질문이 끝없이 이어지는 것처럼 느껴진다.
 */
public record DialogueControl(
        int maxTurns,
        int maxQuestionsPerTurn,
        UnfilledPolicy unfilledPolicy
) {

    public static DialogueControl defaults() {
        return new DialogueControl(6, 3, UnfilledPolicy.USE_DEFAULT);
    }

    public DialogueControl {
        if (maxTurns <= 0) {
            maxTurns = 6;
        }
        if (maxQuestionsPerTurn <= 0) {
            maxQuestionsPerTurn = 3;
        }
        if (unfilledPolicy == null) {
            unfilledPolicy = UnfilledPolicy.USE_DEFAULT;
        }
    }
}
