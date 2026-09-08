package org.hanbat.ses.core.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * SES(System Entity Structure) 트리의 노드.
 *
 * <p>노드 종류가 sealed 로 닫혀 있으므로 pattern matching switch 에서 컴파일러가 누락을 잡아준다.
 * 이 계층이 시스템의 단일 진실 원천이다 — 미결정 지점(선택되지 않은 SpecNode,
 * 개수가 정해지지 않은 MultiAspectNode, 값이 비어 있는 VarDef)이 곧 질문 목록이다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "nodeType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = EntityNode.class, name = "ENTITY"),
        @JsonSubTypes.Type(value = AspectNode.class, name = "ASPECT"),
        @JsonSubTypes.Type(value = SpecNode.class, name = "SPEC"),
        @JsonSubTypes.Type(value = MultiAspectNode.class, name = "MULTI")
})
public sealed interface SesNode
        permits EntityNode, AspectNode, SpecNode, MultiAspectNode {

    String id();

    String name();
}
