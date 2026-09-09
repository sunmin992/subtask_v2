package org.hanbat.ses.dialogue.external;

import java.util.Optional;

/**
 * 슬롯 값을 대화 밖에서 가져오는 통로.
 *
 * <p>Reider &amp; Lang (2025) 의 프레임워크는 ERP/MES 와 기획 문서를 전처리해 값을 미리
 * 채우고, 부족한 값을 되묻는 절차는 두지 않는다. 이 시스템은 되묻기를 핵심에 두지만,
 * <b>물어보지 않아도 알 수 있는 값을 굳이 묻는 것</b>은 그것대로 낭비다. 그래서 질문 앞에
 * 이 통로를 하나 둔다 — 답이 없으면 이전과 똑같이 LLM 추출로, 그다음 질문으로 내려간다.
 *
 * <p>구현체는 {@link SourceTier} 를 하나씩 맡는다. 세 단을 한 클래스에 넣으면
 * "실시간이 죽었을 때 캐시를 쓰는가"가 조건문에 숨고, 그 조건문은 시험하기 어렵다.
 * Spring 이 구현체를 모아 주므로 등록 코드는 없다 — {@code AtomicModelFactory} 와 같은 방식이다.
 *
 * <p><b>구현 규약.</b> {@link #lookup} 은 어떤 이유로도 예외를 던지지 않는다. 조회 실패는
 * 정상적인 결과이며 빈 값으로 알린다. 외부 시스템이 죽었다고 대화가 끊기면
 * 폴백을 둔 의미가 없다.
 */
public interface ExternalDataProvider {

    /** 감사 로그와 출처 표기에 쓰는 식별자. 구현체마다 유일해야 한다. */
    String sourceId();

    /** 이 공급자가 맡는 폴백 단계. */
    SourceTier tier();

    /** 지금 이 공급자를 물어볼 수 있는가. 설정이 비었거나 자료가 없으면 false. */
    default boolean available() {
        return true;
    }

    /** 값을 찾지 못했거나 조회에 실패했으면 빈 값. 예외를 던지지 않는다. */
    Optional<ExternalValue> lookup(ExternalQuery query);
}
