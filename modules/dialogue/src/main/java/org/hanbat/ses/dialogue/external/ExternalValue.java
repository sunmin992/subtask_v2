package org.hanbat.ses.dialogue.external;

import java.time.Instant;

/**
 * 외부 데이터원이 돌려준 값 하나 — 출처를 달고 다닌다.
 *
 * <p>값과 출처를 따로 돌려주면 둘을 잇는 일이 호출부마다 반복되고, 한 곳에서 빠뜨리면
 * 출처 없는 값이 트리에 들어간다. 그 값은 나중에 사용자가 말한 값과 구별되지 않는다.
 *
 * @param sourceId   데이터원 식별자. 어떤 파일·어떤 엔드포인트인지 알아볼 수 있어야 한다.
 * @param observedAt 값이 관측된 시각. 조회 시각이 아니다 — 캐시를 거치면 둘이 갈린다.
 */
public record ExternalValue(
        Object value,
        String unit,
        String sourceId,
        SourceTier tier,
        Instant observedAt,
        String note,
        DataQuality quality
) {

    public ExternalValue(Object value, String unit, String sourceId, SourceTier tier,
                         Instant observedAt, String note) {
        this(value, unit, sourceId, tier, observedAt, note,
                new DataQuality(tier == SourceTier.BUNDLED, false, false, java.util.List.of()));
    }

    public ExternalValue {
        quality = quality == null ? DataQuality.observed() : quality;
    }

    public SlotProvenance provenanceFor(String slot) {
        return new SlotProvenance(slot, FillSource.EXTERNAL_DATA, sourceId, tier,
                observedAt, note, Instant.now(), quality);
    }

    /** 같은 값을 다른 단(段)에서 얻은 것으로 다시 태그한다. 캐시가 자기 이름으로 되돌릴 때 쓴다. */
    public ExternalValue reTagged(SourceTier newTier, String newNote) {
        return new ExternalValue(value, unit, sourceId, newTier, observedAt, newNote, quality);
    }
}
