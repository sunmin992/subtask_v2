package org.hanbat.ses.template.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import org.hanbat.ses.core.model.SesAnchor;

/**
 * 슬롯의 표현 계층.
 *
 * <p>여기에는 "무엇을 물어봐야 하는가"가 들어 있지 <b>않다</b>. 그건 SES 트리가 안다.
 * 이 타입이 담는 것은 <em>어떻게 표현하고 검증할지</em>뿐이다 — 질문 문구, 재질문 문구,
 * 기본값, 추론 허용 여부. 열린 슬롯 목록을 템플릿에도 적어 두면 SES 와 반드시 어긋난다.
 *
 * <p>anchor 가 템플릿과 SES 를 잇는 유일한 고리다. 슬롯 이름이 아니라 앵커로 결합한다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StructuralSlot.class, name = "STRUCTURAL"),
        @JsonSubTypes.Type(value = ValueSlot.class, name = "VALUE"),
        @JsonSubTypes.Type(value = ReferenceSlot.class, name = "REFERENCE")
})
public sealed interface SlotSpec
        permits StructuralSlot, ValueSlot, ReferenceSlot {

    String name();

    SesAnchor anchor();

    QuestionSpec question();

    java.util.List<String> dependsOn();
}
