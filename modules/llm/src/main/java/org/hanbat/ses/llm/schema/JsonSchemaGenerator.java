package org.hanbat.ses.llm.schema;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * record 로부터 JSON 스키마 문자열을 만든다.
 *
 * <p>스키마를 손으로 쓰고 record 를 따로 두면 둘이 어긋난 채로 오래간다.
 * 파싱 대상 타입 하나만 유지하면 되도록 스키마를 리플렉션으로 파생시킨다.
 * 완전한 JSON Schema 가 아니라 LLM 이 형식을 맞추는 데 필요한 만큼만 만든다.
 */
public final class JsonSchemaGenerator {

    private static final int MAX_DEPTH = 5;

    private JsonSchemaGenerator() {
    }

    public static String of(Class<?> type) {
        return render(type, null, 0);
    }

    /**
     * 채워야 할 객체의 <b>골격 예시</b>를 만든다.
     *
     * <p>스키마만 주면 파라미터가 적은 모델은 스키마 자체를 되돌려주는 일이 흔하다 —
     * {@code {"type":"object","properties":{...}}} 를 그대로 답으로 내놓는다.
     * 흉내 낼 수 있는 예시를 함께 주면 그 실패가 크게 줄어든다.
     */
    public static String exampleOf(Class<?> type) {
        return example(type, null, 0);
    }

    private static String example(Class<?> raw, Type generic, int depth) {
        if (depth > MAX_DEPTH) {
            return "{}";
        }
        if (raw.isRecord()) {
            StringJoiner props = new StringJoiner(", ", "{", "}");
            for (RecordComponent rc : raw.getRecordComponents()) {
                props.add("\"" + rc.getName() + "\": "
                        + example(rc.getType(), rc.getGenericType(), depth + 1));
            }
            return props.toString();
        }
        if (raw.isEnum()) {
            Object[] constants = raw.getEnumConstants();
            return "\"" + (constants.length > 0 ? constants[0] : "") + "\"";
        }
        if (Collection.class.isAssignableFrom(raw)) {
            Class<?> item = typeArg(generic, 0);
            return "[" + (item == null ? "" : example(item, null, depth + 1)) + "]";
        }
        if (Map.class.isAssignableFrom(raw)) {
            return "{}";
        }
        return switch (primitiveType(raw)) {
            case "boolean" -> "true";
            case "integer" -> "0";
            case "number" -> "0.0";
            default -> "\"...\"";
        };
    }

    private static String render(Class<?> raw, Type generic, int depth) {
        if (depth > MAX_DEPTH) {
            return "{}";
        }
        if (raw.isRecord()) {
            return renderRecord(raw, depth);
        }
        if (raw.isEnum()) {
            StringJoiner values = new StringJoiner(", ", "[", "]");
            for (Object c : raw.getEnumConstants()) {
                values.add("\"" + c + "\"");
            }
            return "{\"type\": \"string\", \"enum\": " + values + "}";
        }
        if (Collection.class.isAssignableFrom(raw)) {
            Class<?> item = typeArg(generic, 0);
            return "{\"type\": \"array\", \"items\": "
                    + (item == null ? "{}" : render(item, null, depth + 1)) + "}";
        }
        if (Map.class.isAssignableFrom(raw)) {
            Class<?> value = typeArg(generic, 1);
            return "{\"type\": \"object\", \"additionalProperties\": "
                    + (value == null ? "{}" : render(value, null, depth + 1)) + "}";
        }
        return "{\"type\": \"" + primitiveType(raw) + "\"}";
    }

    private static String renderRecord(Class<?> raw, int depth) {
        StringJoiner props = new StringJoiner(", ");
        List<String> required = new ArrayList<>();
        for (RecordComponent rc : raw.getRecordComponents()) {
            props.add("\"" + rc.getName() + "\": "
                    + render(rc.getType(), rc.getGenericType(), depth + 1));
            required.add("\"" + rc.getName() + "\"");
        }
        return "{\"type\": \"object\", \"properties\": {" + props + "}, \"required\": ["
                + String.join(", ", required) + "]}";
    }

    private static String primitiveType(Class<?> raw) {
        if (raw == boolean.class || raw == Boolean.class) {
            return "boolean";
        }
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class) {
            return "integer";
        }
        if (Number.class.isAssignableFrom(raw) || raw.isPrimitive()) {
            return "number";
        }
        return "string";
    }

    private static Class<?> typeArg(Type generic, int index) {
        if (generic instanceof ParameterizedType p && p.getActualTypeArguments().length > index
                && p.getActualTypeArguments()[index] instanceof Class<?> c) {
            return c;
        }
        return null;
    }
}
