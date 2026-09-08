package org.hanbat.ses.scenario.exec;

import org.hanbat.ses.core.pes.Pes;
import org.hanbat.ses.devs.engine.Coordinator;
import org.hanbat.ses.devs.engine.SimConfig;
import org.hanbat.ses.devs.engine.SimulationModel;
import org.hanbat.ses.devs.engine.SimulationResult;
import org.hanbat.ses.scenario.factory.PesFlattener;
import org.springframework.stereotype.Component;

/** 자체 DEVS 엔진 구현. */
@Component
public class DevsScenarioExecutor implements ScenarioExecutor {

    public static final String ENGINE = "devs-internal";

    private final PesFlattener flattener;
    private final Coordinator coordinator = new Coordinator();

    public DevsScenarioExecutor(PesFlattener flattener) {
        this.flattener = flattener;
    }

    @Override
    public boolean supports(String engine) {
        return engine == null || engine.isBlank() || ENGINE.equals(engine);
    }

    @Override
    public SimulationResult execute(Pes pes, SimConfig config) {
        SimulationModel model = flattener.flatten(pes, config.horizon(), config.seed());
        return coordinator.run(model, config);
    }
}
