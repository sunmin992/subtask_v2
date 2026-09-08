package org.hanbat.ses.template.model;

/**
 * 필드 간 제약. SpEL 로 쓴다.
 *
 * <p>표현식 안에서 슬롯 값은 {@code #슬롯이름} 으로 참조한다.
 * 예: {@code #객실수 * 1.5 <= #주차면수}
 */
public record CrossFieldRule(String expression, String message, String[] involves) {

    public CrossFieldRule(String expression, String message) {
        this(expression, message, new String[0]);
    }
}
