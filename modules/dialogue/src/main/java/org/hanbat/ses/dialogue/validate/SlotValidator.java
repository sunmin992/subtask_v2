package org.hanbat.ses.dialogue.validate;

import java.util.List;

import org.hanbat.ses.core.validate.ValidationIssue;

/**
 * 검증 단계 하나.
 *
 * <p>새 검증 규칙은 이 인터페이스 구현체를 하나 추가하는 것으로 끝난다 —
 * 오케스트레이션 코드를 건드리지 않는다.
 */
public interface SlotValidator {

    /** 낮을수록 먼저. 앞 단계가 오류를 내면 뒤 단계는 실행하지 않는다. */
    int order();

    String name();

    List<ValidationIssue> validate(ValidationContext ctx);
}
