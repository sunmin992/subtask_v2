package org.hanbat.ses.llm.gateway;

/** 구조화 출력 파싱 실패. 원문을 들고 있어야 감사 로그에서 원인을 볼 수 있다. */
public class LlmParseException extends RuntimeException {

    private final String raw;

    public LlmParseException(String raw, Throwable cause) {
        super("LLM 응답을 파싱하지 못했습니다: " + abbreviate(raw), cause);
        this.raw = raw;
    }

    public String raw() {
        return raw;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
