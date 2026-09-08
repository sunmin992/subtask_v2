package org.hanbat.ses.llm.gateway;

/**
 * API 키가 없을 때 쓰는 게이트웨이. 모든 호출이 즉시 실패한다.
 *
 * <p>조용히 빈 값을 돌려주지 않는 것이 중요하다. 호출부가 폴백을 갖고 있는지
 * 여부가 예외로 드러나야 "LLM 을 껐더니 아무 질문도 안 나온다" 같은 회귀를 잡을 수 있다.
 */
public final class DisabledLlmGateway implements LlmGateway {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String text(String system, String user, Purpose purpose) {
        throw new LlmUnavailableException("LLM 이 비활성화되어 있습니다 (purpose=" + purpose + ").");
    }

    @Override
    public <T> T complete(String system, String user, Class<T> type, Purpose purpose) {
        throw new LlmUnavailableException("LLM 이 비활성화되어 있습니다 (purpose=" + purpose + ").");
    }
}
