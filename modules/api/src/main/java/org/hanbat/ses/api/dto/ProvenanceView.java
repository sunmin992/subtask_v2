package org.hanbat.ses.api.dto;

import java.time.Instant;

import org.hanbat.ses.dialogue.external.SlotProvenance;

/**
 * 슬롯 하나의 출처 — 응답에 실어 보내는 형태.
 *
 * <p>응답에 이것이 없으면 화면은 "평균 충전시간 34분"만 받는다. 사용자가 그 숫자를 말한 적이
 * 없어도 그렇게 보이고, 다르다고 생각해도 어디를 고쳐야 하는지 알 수 없다.
 *
 * @param stated 사용자가 직접 말한 값인가. 화면이 자동 채움을 다르게 표시하는 데 쓴다.
 */
public record ProvenanceView(
        String slot,
        String source,
        String sourceLabel,
        String tier,
        String tierLabel,
        String sourceId,
        Instant observedAt,
        String detail,
        boolean stated,
        boolean fallback,
        org.hanbat.ses.dialogue.external.DataQuality quality
) {

    public static ProvenanceView of(SlotProvenance p) {
        return new ProvenanceView(
                p.slot(),
                p.source().name(),
                p.source().label(),
                p.tier() == null ? null : p.tier().name(),
                p.tier() == null ? null : p.tier().label(),
                p.sourceId(),
                p.observedAt(),
                p.detail(),
                p.source().stated(),
                p.tier() == org.hanbat.ses.dialogue.external.SourceTier.CACHED
                        || p.tier() == org.hanbat.ses.dialogue.external.SourceTier.BUNDLED
                        || p.source() == org.hanbat.ses.dialogue.external.FillSource.TEMPLATE_DEFAULT,
                p.quality());
    }
}
