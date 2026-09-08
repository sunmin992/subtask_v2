package org.hanbat.ses.core.validate;

/**
 * 검증 결과 한 건.
 *
 * @param slot     문제가 발생한 슬롯 또는 노드 식별자.
 * @param code     기계 판독용 코드. 클라이언트 분기에 쓴다.
 * @param message  사용자에게 보여줄 한국어 설명.
 */
public record ValidationIssue(String slot, String code, String message, Severity severity) {

    public static ValidationIssue error(String slot, String code, String message) {
        return new ValidationIssue(slot, code, message, Severity.ERROR);
    }

    public static ValidationIssue warning(String slot, String code, String message) {
        return new ValidationIssue(slot, code, message, Severity.WARNING);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
