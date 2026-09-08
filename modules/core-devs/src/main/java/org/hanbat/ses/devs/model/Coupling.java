package org.hanbat.ses.devs.model;

/**
 * 평탄화된 배선. core-devs 는 core-ses 를 모른다 —
 * SES 의 CouplingSpec 을 이 형태로 옮기는 일은 scenario 모듈이 한다.
 */
public record Coupling(String fromModel, String fromPort, String toModel, String toPort) {
}
