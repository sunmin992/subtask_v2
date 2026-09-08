package org.hanbat.ses.dialogue.validate;

import java.util.Map;

import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.dialogue.control.ResolvedSlot;
import org.hanbat.ses.template.model.SimulatorConfig;
import org.hanbat.ses.template.model.SubtaskTemplate;

/**
 * 검증 입력.
 *
 * @param candidate 이번 턴에 들어온 답변. 타입 검증은 이것만 본다.
 * @param answers   지금까지 누적된 답변. 교차 제약은 전부를 본다.
 * @param slots     이번 턴에 열려 있던 슬롯. 답변을 슬롯에 대응시키는 데 쓴다.
 */
public record ValidationContext(
        SesNode ses,
        SubtaskTemplate template,
        Map<String, Object> candidate,
        Map<String, Object> answers,
        Map<String, ResolvedSlot> slots
) {

    public SimulatorConfig simConfig() {
        return template == null ? SimulatorConfig.defaults() : template.execution().simulator();
    }
}
