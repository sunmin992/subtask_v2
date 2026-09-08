package org.hanbat.ses.dialogue.control;

import java.util.List;

import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.pes.PesBuildResult;
import org.hanbat.ses.core.pes.PesBuilder;
import org.hanbat.ses.core.resolve.SlotResolver;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.springframework.stereotype.Component;

/**
 * 완료 판정 — 세 조건이 모두 성립해야 한다.
 *
 * <ol>
 *   <li>슬롯 충족: SES 에 미결정 지점이 없다.</li>
 *   <li>검증 통과: ERROR 등급 issue 가 없다.</li>
 *   <li>PES 유일성: 트리에서 PES 가 하나로 확정된다.</li>
 * </ol>
 *
 * <p>세 번째가 없으면 "질문에 다 답했는데 시뮬레이션이 안 돈다"는 상황이 생긴다.
 * 슬롯이 비어 있지 않다는 것과 구조가 확정됐다는 것은 다른 이야기다.
 */
@Component
public class CompletionJudge {

    private final SlotResolver resolver = new SlotResolver();
    private final PesBuilder pesBuilder = new PesBuilder();

    public record Verdict(boolean complete, List<ValidationIssue> blockers) {
    }

    public Verdict judge(SesNode ses, List<ValidationIssue> issues) {
        if (!resolver.scan(ses).isEmpty()) {
            return new Verdict(false, List.of(ValidationIssue.error("<session>", "SLOTS_OPEN",
                    "아직 결정되지 않은 항목이 남아 있습니다.")));
        }
        List<ValidationIssue> errors = issues.stream().filter(ValidationIssue::isError).toList();
        if (!errors.isEmpty()) {
            return new Verdict(false, errors);
        }
        PesBuildResult result = pesBuilder.tryBuild(ses);
        if (!result.ok()) {
            return new Verdict(false, result.issues());
        }
        return new Verdict(true, List.of());
    }

    public boolean isComplete(SesNode ses) {
        return judge(ses, List.of()).complete();
    }
}
