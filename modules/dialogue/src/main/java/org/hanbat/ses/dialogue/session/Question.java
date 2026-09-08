package org.hanbat.ses.dialogue.session;

import java.util.List;

/**
 * 사용자에게 나가는 질문 하나.
 *
 * @param slot       답변을 돌려줄 때 쓰는 키.
 * @param entityPath SES 상의 위치. "왜 이걸 묻는지"를 사용자가 가늠하게 해 준다.
 */
public record Question(
        String slot,
        String text,
        String hint,
        QuestionInputType inputType,
        String unit,
        RangeView range,
        Object defaultValue,
        List<String> options,
        String entityPath,
        boolean structural
) {

    public Question {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
