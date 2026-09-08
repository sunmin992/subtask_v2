package org.hanbat.ses.scenario.factory;

import java.util.Collection;

public class UnknownModelException extends RuntimeException {

    public UnknownModelException(String modelRef, Collection<String> known) {
        super("등록되지 않은 원자 모델입니다: " + modelRef + " (사용 가능: " + known + ")");
    }
}
