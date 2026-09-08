package org.hanbat.ses.llm.gateway;

/**
 * LLM 경계.
 *
 * <p>인터페이스 뒤에 두는 이유는 교체 가능성보다 <b>끌 수 있게</b> 하기 위해서다.
 * Phase 3 까지의 결정론적 경로가 LLM 없이 그대로 도는 것이 폴백 설계의 증거다.
 */
public interface LlmGateway {

    /** 지금 이 게이트웨이로 호출이 가능한가. false 면 호출부는 폴백으로 간다. */
    boolean available();

    /** 자유 텍스트 응답. */
    String text(String system, String user, Purpose purpose);

    /**
     * 구조화 출력 — JSON 스키마를 프롬프트에 주입하고 파싱 실패 시 1회 재시도한다.
     * 두 번째도 실패하면 {@link LlmParseException} 을 던진다.
     */
    <T> T complete(String system, String user, Class<T> type, Purpose purpose);
}
