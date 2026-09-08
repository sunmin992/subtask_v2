package org.hanbat.ses.devs.model;

/** 결합 모델의 컴포넌트 참조 — 인스턴스 id 와 실제 원자 모델. */
public record ModelRef(String id, AtomicModel<?> model) {
}
