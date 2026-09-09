package org.hanbat.ses.app.config;

import org.hanbat.ses.dialogue.external.ExternalDataProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 외부 데이터 조회 설정.
 *
 * <p>기본값은 실시간 조회 꺼짐이다. LLM 설정과 같은 판단이다 — 외부 의존이 없어도
 * 서버는 뜨고 전체 흐름이 돈다. 여기서는 내장 데이터셋(3단)이 그 역할을 맡는다.
 */
@Configuration
public class ExternalDataConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ExternalDataConfiguration.class);

    @Bean
    public ExternalDataProperties externalDataProperties(
            @Value("${app.external-data.base-url:}") String baseUrl,
            @Value("${app.external-data.timeout-ms:2000}") long timeoutMs,
            @Value("${app.external-data.cache-ttl-minutes:1440}") long cacheTtlMinutes,
            @Value("${app.external-data.max-attempts:2}") int maxAttempts,
            @Value("${app.external-data.current-max-age-seconds:120}") long currentMaxAgeSeconds,
            @Value("${app.external-data.reference-max-age-days:180}") long referenceMaxAgeDays,
            @Value("${app.external-data.jump-ratio:3}") double jumpRatio,
            @Value("${app.external-data.live-budget-ms:0}") long liveBudgetMs,
            @Value("${app.external-data.live-failure-threshold:3}") int liveFailureThreshold,
            @Value("${app.external-data.live-cooldown-ms:60000}") long liveCooldownMs) {

        ExternalDataProperties properties =
                new ExternalDataProperties(baseUrl, timeoutMs, cacheTtlMinutes, maxAttempts,
                        currentMaxAgeSeconds, referenceMaxAgeDays, jumpRatio,
                        liveBudgetMs, liveFailureThreshold, liveCooldownMs);
        if (properties.liveEnabled()) {
            log.info("외부 데이터 실시간 조회: {} (타임아웃 {}ms, 턴 예산 {}ms, 연속 {}회 실패 시 {}ms 차단)",
                    properties.baseUrl(), properties.timeoutMs(), properties.liveBudgetMs(),
                    properties.liveFailureThreshold(), properties.liveCooldownMs());
        } else {
            log.info("외부 데이터 실시간 조회가 꺼져 있습니다. 캐시와 내장 데이터셋만 씁니다.");
        }
        return properties;
    }
}
