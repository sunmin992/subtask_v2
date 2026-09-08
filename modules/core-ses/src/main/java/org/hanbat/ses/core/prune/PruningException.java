package org.hanbat.ses.core.prune;

/** 답변을 트리에 적용할 수 없을 때. 앵커가 가리키는 노드가 없거나 답변이 선택지 밖일 때 발생한다. */
public class PruningException extends RuntimeException {

    public PruningException(String message) {
        super(message);
    }
}
