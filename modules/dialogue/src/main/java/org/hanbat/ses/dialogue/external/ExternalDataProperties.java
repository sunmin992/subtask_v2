package org.hanbat.ses.dialogue.external;

/**
 * 외부 데이터 조회 설정.
 *
 * <p>기본값은 <b>실시간 조회 꺼짐</b>이다. 켜져 있는 것이 기본이면 네트워크가 없는 환경에서
 * 첫 턴이 타임아웃만큼 늦어지고, 그 지연은 원인이 보이지 않는다. 내장 데이터셋은 늘 살아
 * 있으므로 꺼 두어도 3단 폴백의 마지막 단은 언제나 동작한다.
 *
 * <h2>조회 하나가 아니라 턴 전체를 지켜야 한다</h2>
 * <p>{@code timeoutMs} 는 조회 <b>하나</b>의 상한일 뿐이다. 값 슬롯이 열 개면 조회도 열 번이고,
 * 엔드포인트가 멎어 있으면 한 턴이 열 배로 늘어진다 — 실제로 충전기 20대 구성에서
 * {@code 20 × maxAttempts × timeoutMs} 까지 벌어진다. 그래서 상한을 두 겹으로 둔다.
 *
 * <ul>
 *   <li>{@code liveBudgetMs} — 한 턴에 실시간 조회로 쓸 수 있는 시간의 총량. 다 쓰면 남은
 *       슬롯은 곧바로 캐시·내장 데이터셋으로 내려간다. 슬롯 수와 무관하게 턴이 유계가 된다.</li>
 *   <li>{@code liveFailureThreshold} / {@code liveCooldownMs} — 연속 실패가 쌓이면 잠시
 *       실시간 단을 통째로 건너뛴다. 이것이 없으면 엔드포인트가 죽어 있는 동안
 *       <b>모든 턴</b>이 예산만큼을 다시 지불한다.</li>
 * </ul>
 *
 * @param baseUrl              실시간 조회 엔드포인트. 비어 있으면 1단을 건너뛴다.
 * @param timeoutMs            조회 하나의 대기 상한.
 * @param cacheTtlMinutes      캐시 스냅샷 유효 기간. 지나면 없는 것으로 친다.
 * @param maxAttempts          조회 하나당 재시도 포함 총 시도 횟수.
 * @param currentMaxAgeSeconds 현재 상태 조회에서 값이 유효한 기간.
 * @param referenceMaxAgeDays  예측용 참고값이 유효한 기간.
 * @param jumpRatio            직전 값 대비 이 배수 이상 벌어지면 급변으로 표시한다.
 * @param liveBudgetMs         한 턴의 실시간 조회 시간 총량. 0 이면 {@code timeoutMs × maxAttempts}.
 * @param liveFailureThreshold 이만큼 연속 실패하면 실시간 단을 잠시 닫는다.
 * @param liveCooldownMs       닫아 두는 시간.
 */
public record ExternalDataProperties(
        String baseUrl,
        long timeoutMs,
        long cacheTtlMinutes,
        int maxAttempts,
        long currentMaxAgeSeconds,
        long referenceMaxAgeDays,
        double jumpRatio,
        long liveBudgetMs,
        int liveFailureThreshold,
        long liveCooldownMs
) {

    public ExternalDataProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.trim();
        timeoutMs = timeoutMs <= 0 ? 2_000L : timeoutMs;
        cacheTtlMinutes = cacheTtlMinutes <= 0 ? 1_440L : cacheTtlMinutes;
        maxAttempts = Math.min(3, Math.max(1, maxAttempts));
        currentMaxAgeSeconds = currentMaxAgeSeconds <= 0 ? 120 : currentMaxAgeSeconds;
        referenceMaxAgeDays = referenceMaxAgeDays <= 0 ? 180 : referenceMaxAgeDays;
        jumpRatio = !Double.isFinite(jumpRatio) || jumpRatio <= 1 ? 3 : jumpRatio;
        // 기본 예산은 "조회 한 번이 완전히 실패하는 데 드는 시간"이다. 엔드포인트가 멎어 있어도
        // 한 턴이 그 사실을 알아내는 비용은 슬롯 하나 몫으로 끝난다.
        liveBudgetMs = liveBudgetMs <= 0 ? timeoutMs * maxAttempts : liveBudgetMs;
        liveFailureThreshold = liveFailureThreshold <= 0 ? 3 : liveFailureThreshold;
        liveCooldownMs = liveCooldownMs <= 0 ? 60_000L : liveCooldownMs;
    }

    public ExternalDataProperties(String baseUrl, long timeoutMs, long cacheTtlMinutes) {
        this(baseUrl, timeoutMs, cacheTtlMinutes, 2, 120, 180, 3, 0, 3, 60_000L);
    }

    public ExternalDataProperties(String baseUrl, long timeoutMs, long cacheTtlMinutes,
                                  int maxAttempts, long currentMaxAgeSeconds,
                                  long referenceMaxAgeDays, double jumpRatio) {
        this(baseUrl, timeoutMs, cacheTtlMinutes, maxAttempts, currentMaxAgeSeconds,
                referenceMaxAgeDays, jumpRatio, 0, 3, 60_000L);
    }

    public static ExternalDataProperties disabled() {
        return new ExternalDataProperties(null, 2_000L, 1_440L);
    }

    public boolean liveEnabled() {
        return baseUrl != null;
    }
}
