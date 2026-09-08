package org.hanbat.ses.core.validate;

import java.util.Optional;

import org.hanbat.ses.core.model.VarType;

/**
 * 느슨한 입력(JSON 숫자, 문자열, LLM 추출값)을 선언된 VarType 으로 정규화한다.
 *
 * <p>정규화에 실패하면 예외 대신 빈 Optional 을 돌려준다. 실패는 사용자에게
 * 되물어야 할 사건이지 서버 오류가 아니기 때문이다.
 */
public final class ValueCoercion {

    private ValueCoercion() {
    }

    public static Optional<Object> coerce(Object raw, VarType type) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.toString().trim();
        if (s.isEmpty()) {
            return Optional.empty();
        }
        try {
            return switch (type) {
                case INT -> Optional.of(parseInt(raw, s));
                case DOUBLE -> Optional.of(raw instanceof Number n ? n.doubleValue() : Double.parseDouble(s));
                case BOOL -> parseBool(raw, s);
                case STRING, ENUM -> Optional.of(s);
            };
        } catch (NumberFormatException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static Integer parseInt(Object raw, String s) {
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (d != Math.rint(d)) {
                throw new NumberFormatException("정수가 아님: " + d);
            }
            return (int) d;
        }
        return Integer.valueOf(s);
    }

    private static Optional<Object> parseBool(Object raw, String s) {
        if (raw instanceof Boolean b) {
            return Optional.of(b);
        }
        return switch (s.toLowerCase()) {
            case "true", "yes", "y", "예", "네", "1" -> Optional.of(Boolean.TRUE);
            case "false", "no", "n", "아니오", "아니요", "0" -> Optional.of(Boolean.FALSE);
            default -> Optional.empty();
        };
    }

    /** 숫자로 볼 수 있으면 double 로. 범위 검사용. */
    public static Optional<Double> asNumber(Object value) {
        if (value instanceof Number n) {
            return Optional.of(n.doubleValue());
        }
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(value.toString().trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
