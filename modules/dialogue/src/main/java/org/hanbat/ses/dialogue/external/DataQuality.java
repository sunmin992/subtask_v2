package org.hanbat.ses.dialogue.external;

import java.util.List;

/** 관측값과 계산/예시 가정을 구분하고 검증 결과를 출처와 함께 보관한다. */
public record DataQuality(boolean assumed, boolean stale, boolean reviewRequired, List<String> reasons) {
    public DataQuality {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
    public static DataQuality observed() {
        return new DataQuality(false, false, false, List.of());
    }
}
