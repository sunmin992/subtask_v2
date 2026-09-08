package org.hanbat.ses.api.controller;

import java.util.UUID;

import org.hanbat.ses.api.dto.RunResponse;
import org.hanbat.ses.api.dto.ScenarioResponse;
import org.hanbat.ses.api.service.SessionFacade;
import org.hanbat.ses.scenario.exec.ScenarioRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scenarios")
public class ScenarioController {

    private final SessionFacade facade;
    private final ScenarioRunner runner;

    public ScenarioController(SessionFacade facade, ScenarioRunner runner) {
        this.facade = facade;
        this.runner = runner;
    }

    @GetMapping("/{id}")
    public ScenarioResponse get(@PathVariable UUID id) {
        return facade.getScenario(id);
    }

    /**
     * 시뮬레이션 실행.
     *
     * @param wait true 면 동기 실행 후 결과까지 돌려준다. 기본은 202 + 폴링.
     */
    @PostMapping("/{id}/runs")
    public ResponseEntity<RunResponse> run(@PathVariable UUID id,
                                           @RequestParam(defaultValue = "false") boolean wait) {
        if (wait) {
            return ResponseEntity.ok(RunResponse.of(runner.runNow(id)));
        }
        return ResponseEntity.accepted().body(RunResponse.of(runner.submit(id)));
    }
}
