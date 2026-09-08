package org.hanbat.ses.llm.gateway;

/** LLM 이 꺼져 있거나 응답하지 않을 때. 호출부는 반드시 폴백 경로를 갖고 있어야 한다. */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
