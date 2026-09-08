package org.hanbat.ses.scenario.model;

import java.util.UUID;

public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException(UUID id) {
        super("실행 기록을 찾을 수 없습니다: " + id);
    }
}
