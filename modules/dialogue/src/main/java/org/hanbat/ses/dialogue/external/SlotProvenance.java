package org.hanbat.ses.dialogue.external;

import java.time.Instant;

/**
 * 슬롯 하나의 출처 기록.
 *
 * <p>세션에 답변과 나란히 보관되고 시나리오까지 따라간다. 이것이 있어야
 * "이 결과에서 사용자가 실제로 말한 것은 무엇이고, 서버가 채운 것은 무엇인가"를
 * 나중에 되짚을 수 있다. 연구 검증에서 자동 추출 정확도와 기본값 의존도를 재려면
 * 정확히 이 정보가 필요하다.
 *
 * @param sourceId   외부 데이터일 때의 데이터원 식별자. 그 외에는 null.
 * @param tier       외부 데이터일 때 몇 단에서 왔는지. 그 외에는 null.
 * @param observedAt 값이 관측된 시각. 외부 데이터가 아니면 null.
 * @param detail     사람이 읽을 한 줄. 추출이면 근거 문구, 기본값이면 그 사실.
 * @param recordedAt 이 기록을 남긴 시각.
 */
public record SlotProvenance(
        String slot,
        FillSource source,
        String sourceId,
        SourceTier tier,
        Instant observedAt,
        String detail,
        Instant recordedAt,
        DataQuality quality
) {

    public SlotProvenance(String slot, FillSource source, String sourceId, SourceTier tier,
                          Instant observedAt, String detail, Instant recordedAt) {
        this(slot, source, sourceId, tier, observedAt, detail, recordedAt,
                new DataQuality(source == FillSource.TEMPLATE_DEFAULT || tier == SourceTier.BUNDLED,
                        false, false, java.util.List.of()));
    }

    public SlotProvenance {
        quality = quality == null
                ? new DataQuality(source == FillSource.TEMPLATE_DEFAULT || tier == SourceTier.BUNDLED,
                    false, false, java.util.List.of()) : quality;
    }

    public static SlotProvenance userAnswer(String slot) {
        return new SlotProvenance(slot, FillSource.USER_ANSWER, null, null, null,
                null, Instant.now());
    }

    public static SlotProvenance extracted(String slot, String evidence) {
        return new SlotProvenance(slot, FillSource.LLM_EXTRACTION, null, null, null,
                evidence == null ? null : "근거: " + evidence, Instant.now());
    }

    public static SlotProvenance templateDefault(String slot, Object value) {
        return new SlotProvenance(slot, FillSource.TEMPLATE_DEFAULT, null, null, null,
                "기본값 " + value + " 으로 마감", Instant.now());
    }

    /** 결과 화면과 감사 로그에 그대로 쓰는 한 줄. */
    public String describe() {
        StringBuilder sb = new StringBuilder(source.label());
        if (tier != null) {
            sb.append(" · ").append(tier.label());
        }
        if (sourceId != null) {
            sb.append(" · ").append(sourceId);
        }
        if (detail != null) {
            sb.append(" — ").append(detail);
        }
        if (observedAt != null) sb.append(" · 관측 시각 ").append(observedAt);
        if (quality.assumed()) sb.append(" · 예시 계산의 참고값이며 현재 현황이 아닙니다");
        if (quality.stale()) sb.append(" · 오래된 참고자료입니다");
        if (quality.reviewRequired()) sb.append(" · 이전 값과 차이가 커 추가 확인이 필요합니다");
        return sb.toString();
    }
}
