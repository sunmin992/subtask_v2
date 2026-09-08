package org.hanbat.ses.template.model;

import java.util.List;
import java.util.Map;

/**
 * 검증 규칙.
 *
 * @param unitAliases 단위 별칭. "min" 과 "분" 을 같은 단위로 볼지 여기서 정한다.
 */
public record ValidationSpec(
        List<CrossFieldRule> crossFieldRules,
        Map<String, String> unitAliases
) {

    public ValidationSpec {
        crossFieldRules = crossFieldRules == null ? List.of() : List.copyOf(crossFieldRules);
        unitAliases = unitAliases == null ? Map.of() : Map.copyOf(unitAliases);
    }

    public static ValidationSpec empty() {
        return new ValidationSpec(List.of(), Map.of());
    }
}
