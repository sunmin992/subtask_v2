package org.hanbat.ses.template.model;

/** 선행 서브태스크. 복합 요청을 DAG 로 엮을 때 쓴다. */
public record Dependency(String taskId, String reason, boolean required) {
}
