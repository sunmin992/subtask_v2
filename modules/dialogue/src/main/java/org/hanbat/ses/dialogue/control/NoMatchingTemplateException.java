package org.hanbat.ses.dialogue.control;

/** 요청문에 맞는 서브태스크 템플릿이 없을 때. */
public class NoMatchingTemplateException extends RuntimeException {

    public NoMatchingTemplateException(String request) {
        super("요청에 맞는 서브태스크를 찾지 못했습니다: " + abbreviate(request));
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "<빈 요청>";
        }
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }
}
