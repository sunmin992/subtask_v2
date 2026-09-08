package org.hanbat.ses.devs.model;

/** 포트에 실려 흐르는 값 한 건. */
public record Message(String port, Object value) {

    public static Message of(String port, Object value) {
        return new Message(port, value);
    }
}
