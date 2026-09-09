package org.hanbat.ses.dialogue.external;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.hanbat.ses.core.model.Range;
import org.hanbat.ses.core.model.VarDef;
import org.hanbat.ses.core.model.VarType;
import org.hanbat.ses.core.validate.UnitTable;

/** 공급자 선택 안에서 실행한다. 환산 전 숫자로 범위를 검사하거나 조회 시각을 관측 시각으로 쓰지 않는다. */
public final class ExternalValueValidator {
    public record Check(ExternalValue value, String rejection) {
        public boolean accepted() { return value != null; }
    }

    public Check validate(ExternalValue raw, ExternalQuery query, VarDef var, Range templateRange,
                          Map<String, String> aliases, DataUsage usage, ExternalDataProperties policy,
                          Instant now, Optional<ExternalValue> previous) {
        if (raw.value() == null || raw.sourceId() == null || raw.sourceId().isBlank()
                || raw.tier() == null || raw.observedAt() == null) return reject("MISSING_FIELD");
        if (raw.observedAt().isAfter(now.plusSeconds(30))) return reject("FUTURE_OBSERVATION");
        Duration age = Duration.between(raw.observedAt(), now);
        Duration maxAge = usage == DataUsage.CURRENT_STATUS
                ? Duration.ofSeconds(policy.currentMaxAgeSeconds())
                : Duration.ofDays(policy.referenceMaxAgeDays());
        boolean stale = age.compareTo(maxAge) > 0;
        if (usage == DataUsage.CURRENT_STATUS && (raw.tier() == SourceTier.BUNDLED
                || raw.quality().assumed() || stale || raw.quality().reviewRequired())) {
            return reject("CURRENT_DATA_REQUIRED");
        }
        // 오래된 실시간 응답은 대체 공급자로 보낸다. 내장 참고자료는 예시에만 표시해서 사용한다.
        if (stale && raw.tier() != SourceTier.BUNDLED) return reject("STALE_OBSERVATION");

        Optional<Double> factor = UnitTable.conversionFactor(raw.unit(), query.unit(), aliases);
        if (factor.isEmpty()) return reject("UNIT_MISMATCH");
        Object value = raw.value();
        VarType type = var == null ? null : var.type();
        if (type == VarType.BOOL && !(value instanceof Boolean)) return reject("TYPE_MISMATCH");
        if ((type == VarType.STRING || type == VarType.ENUM) && !(value instanceof String)) return reject("TYPE_MISMATCH");
        if (value instanceof String token) value = token.trim();
        if (type == VarType.INT || type == VarType.DOUBLE || value instanceof Number) {
            if (!(value instanceof Number n)) return reject("TYPE_MISMATCH");
            double converted = n.doubleValue() * factor.get();
            if (!Double.isFinite(converted)) return reject("NON_FINITE");
            if (type == VarType.INT && (converted != Math.rint(converted)
                    || converted < Integer.MIN_VALUE || converted > Integer.MAX_VALUE)) return reject("TYPE_MISMATCH");
            if (var != null && !var.range().containsNumber(converted)) return reject("OUT_OF_RANGE");
            if (templateRange != null && !templateRange.containsNumber(converted)) return reject("OUT_OF_RANGE");
            if (UnitTable.dimensionOf(query.unit(), aliases) == UnitTable.Dimension.TIME && converted < 0) {
                return reject("NEGATIVE_DURATION");
            }
            value = type == VarType.INT ? (Object) Integer.valueOf((int) converted) : Double.valueOf(converted);
        } else {
            if (factor.get() != 1.0) return reject("TYPE_MISMATCH");
            if (type == VarType.BOOL && !(value instanceof Boolean)) return reject("TYPE_MISMATCH");
            if ((type == VarType.STRING || type == VarType.ENUM) && !(value instanceof String)) return reject("TYPE_MISMATCH");
            if (!(value instanceof String) && !(value instanceof Boolean)) return reject("TYPE_MISMATCH");
            if (value instanceof String s && (s.isBlank()
                    || var != null && !var.range().containsToken(s)
                    || templateRange != null && !templateRange.containsToken(s))) return reject("OUT_OF_RANGE");
        }

        List<String> reasons = new ArrayList<>(raw.quality().reasons());
        if (factor.get() != 1.0) reasons.add("UNIT_CONVERTED");
        if (stale) reasons.add("STALE_REFERENCE");
        boolean jump = raw.quality().reviewRequired();
        if (raw.tier() == SourceTier.LIVE && value instanceof Number current && previous.isPresent()
                && previous.get().value() instanceof Number old
                && previous.get().observedAt() != null
                && previous.get().observedAt().isBefore(raw.observedAt())
                && java.util.Objects.equals(previous.get().unit(), query.unit())) {
            double a = Math.abs(current.doubleValue()), b = Math.abs(old.doubleValue());
            jump |= Double.isFinite(b) && a != b && (Math.min(a, b) == 0
                    || Math.max(a, b) / Math.min(a, b) >= policy.jumpRatio());
        }
        if (jump) reasons.add("ABRUPT_CHANGE");
        if (jump && usage == DataUsage.CURRENT_STATUS) return reject("REVIEW_REQUIRED");
        DataQuality quality = new DataQuality(raw.quality().assumed() || raw.tier() == SourceTier.BUNDLED,
                stale, jump, reasons.stream().distinct().toList());
        return new Check(new ExternalValue(value, query.unit(), raw.sourceId(), raw.tier(),
                raw.observedAt(), raw.note(), quality), null);
    }

    private Check reject(String reason) { return new Check(null, reason); }
}
