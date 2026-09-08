package org.hanbat.ses.api.controller;

import java.util.UUID;

import org.hanbat.ses.api.dto.RunResponse;
import org.hanbat.ses.scenario.model.RunStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunStore runs;

    public RunController(RunStore runs) {
        this.runs = runs;
    }

    @GetMapping("/{id}")
    public RunResponse get(@PathVariable UUID id) {
        return RunResponse.of(runs.require(id));
    }
}
