package org.hanbat.ses.scenario.model;

import java.util.UUID;

public class ScenarioNotFoundException extends RuntimeException {

    public ScenarioNotFoundException(UUID id) {
        super("시나리오를 찾을 수 없습니다: " + id);
    }
}
