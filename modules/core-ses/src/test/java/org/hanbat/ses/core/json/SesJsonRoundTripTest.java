package org.hanbat.ses.core.json;

import static org.assertj.core.api.Assertions.assertThat;

import org.hanbat.ses.core.fixture.ResortSes;
import org.hanbat.ses.core.model.SesNode;
import org.hanbat.ses.core.resolve.OpenSlot;
import org.hanbat.ses.core.resolve.SlotResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 세션 상태(working_ses)는 JSONB 로 저장되고 매 턴 다시 읽힌다.
 * 폴리모픽 직렬화가 한 군데라도 깨지면 대화가 중간에 죽는다.
 */
class SesJsonRoundTripTest {

    @Test
    @DisplayName("미해결 트리를 직렬화했다 읽으면 열린 슬롯이 그대로다")
    void roundTripsOpenTree() {
        SesNode original = ResortSes.tree();

        SesNode restored = SesJson.read(SesJson.write(original), SesNode.class);

        assertThat(names(restored)).isEqualTo(names(original));
        assertThat(SesJson.write(restored)).isEqualTo(SesJson.write(original));
    }

    @Test
    @DisplayName("부분 pruning 된 트리도 왕복한다 — 복제본과 선택 상태가 보존된다")
    void roundTripsPrunedTree() {
        SesNode original = ResortSes.cableCarResolved();

        SesNode restored = SesJson.read(SesJson.write(original), SesNode.class);

        assertThat(SesJson.write(restored)).isEqualTo(SesJson.write(original));
        assertThat(names(restored)).isEmpty();
    }

    @Test
    @DisplayName("JSON 에 nodeType 판별자가 실린다")
    void writesTypeDiscriminator() {
        String json = SesJson.write(ResortSes.tree());

        assertThat(json).contains("\"nodeType\" : \"ENTITY\"")
                .contains("\"nodeType\" : \"ASPECT\"")
                .contains("\"nodeType\" : \"SPEC\"")
                .contains("\"nodeType\" : \"MULTI\"");
    }

    private static java.util.List<String> names(SesNode tree) {
        return new SlotResolver().scan(tree).stream().map(OpenSlot::slotName).toList();
    }
}
