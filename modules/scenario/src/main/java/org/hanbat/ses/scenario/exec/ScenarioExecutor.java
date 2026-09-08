package org.hanbat.ses.scenario.exec;

import org.hanbat.ses.core.pes.Pes;
import org.hanbat.ses.devs.engine.SimConfig;
import org.hanbat.ses.devs.engine.SimulationResult;

/**
 * 실행 엔진 경계.
 *
 * <p>인터페이스로 두는 것이 중요하다. 자체 DEVS 엔진으로 시작하지만 나중에
 * 외부 시뮬레이터(AnyLogic, SUMO 등)를 붙이고 싶어질 때, 오케스트레이션 계층 전체를
 * 건드리지 않고 구현체만 추가하면 된다.
 */
public interface ScenarioExecutor {

    boolean supports(String engine);

    SimulationResult execute(Pes pes, SimConfig config);
}
