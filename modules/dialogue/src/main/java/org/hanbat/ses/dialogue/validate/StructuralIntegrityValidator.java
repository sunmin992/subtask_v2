package org.hanbat.ses.dialogue.validate;

import java.util.ArrayList;
import java.util.List;

import org.hanbat.ses.core.pes.PesBuildResult;
import org.hanbat.ses.core.pes.PesBuilder;
import org.hanbat.ses.core.validate.SesStructureChecker;
import org.hanbat.ses.core.validate.ValidationIssue;
import org.springframework.stereotype.Component;

/**
 * 4단계 — 구조 무결성. PES 를 미리 한 번 빌드해 보고 배선을 확인한다.
 *
 * <p>계획서 원본에 없던 단계다. "모든 spec 노드가 해소되었는가", "모든 커플링의 양 끝
 * 포트가 실제로 존재하는가"를 확인하지 않으면 오류가 시뮬레이터 실행 시점의
 * 정체불명 NPE 로 나타난다. 그때는 사용자에게 되물을 것도, 고칠 곳을 짚어 줄 것도 없다.
 *
 * <p>이어진 포트의 단위 대조도 여기서 한다(FR-403의 나머지 절반). 단위가 어긋난 배선은
 * NPE 조차 내지 않아 더 찾기 어렵다.
 */
@Component
public class StructuralIntegrityValidator implements SlotValidator {

    private final PesBuilder pesBuilder = new PesBuilder();

    @Override
    public int order() {
        return 40;
    }

    @Override
    public String name() {
        return "structure";
    }

    @Override
    public List<ValidationIssue> validate(ValidationContext ctx) {
        // 단위 별칭은 템플릿이 선언한다 — 검사기를 매번 새로 만들어 그것을 물려준다.
        SesStructureChecker structureChecker = new SesStructureChecker(
                ctx.template() == null ? java.util.Map.of()
                        : ctx.template().validation().unitAliases());
        List<ValidationIssue> issues = new ArrayList<>(structureChecker.check(ctx.ses()));

        PesBuildResult result = pesBuilder.tryBuild(ctx.ses());
        issues.addAll(result.issues());

        if (result.ok() && result.pes().leaves().stream()
                .anyMatch(leaf -> leaf.modelRef() == null || leaf.modelRef().isBlank())) {
            result.pes().leaves().stream()
                    .filter(leaf -> leaf.modelRef() == null || leaf.modelRef().isBlank())
                    .forEach(leaf -> issues.add(ValidationIssue.error(leaf.entityId(),
                            "LEAF_WITHOUT_MODEL",
                            leaf.name() + " 은(는) 리프인데 대응하는 원자 모델이 없습니다.")));
        }
        return issues;
    }
}
