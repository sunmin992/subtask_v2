package org.hanbat.ses.devs.engine;

import java.util.List;
import java.util.Map;

import org.hanbat.ses.devs.model.Message;

/**
 * 한 이벤트 시각의 기록.
 *
 * @param imminents 이 시각에 내부 전이한 컴포넌트.
 * @param messages  이 시각에 오간 메시지(도착지 기준).
 */
public record TraceEntry(
        double time,
        List<String> imminents,
        Map<String, List<Message>> messages
) {
}
