package org.hanbat.ses.devs.random;

/**
 * 상태를 값으로 들고 다니는 난수원.
 *
 * <p>{@code java.util.Random} 을 모델 필드에 두면 안 된다. 원자 모델은 상태를 필드가 아니라
 * 인자로 주고받는 함수형 형태이고(같은 인스턴스를 복제본이 공유한다), 그 전제 위에서만
 * "같은 답변 -> 같은 결과"(NFR-01)가 성립한다. 필드에 숨은 난수 커서가 하나라도 있으면
 * 복제본 세 대가 서로의 난수열을 갉아먹고, 실행 순서가 결과를 바꾼다.
 *
 * <p>그래서 난수 커서를 <b>상태 레코드 안에</b> 넣는다. 이 클래스는 그 커서 한 칸이다.
 * splitmix64 를 쓰는 이유는 상태가 long 하나뿐이라 상태 레코드에 그대로 실을 수 있고,
 * 순차 시드(seed, seed+1, seed+2 …)를 줘도 서로 상관 없는 수열이 나오기 때문이다 —
 * 복제본마다 시드를 1씩 늘려 주는 것으로 독립 난수원이 확보된다.
 */
public record Rng(long state) {

    private static final long GAMMA = 0x9E3779B97F4A7C15L;

    /** 2^-53. 53비트 가수에 정확히 담기는 최대 해상도다. */
    private static final double UNIT = 0x1.0p-53;

    public static Rng seeded(long seed) {
        return new Rng(seed);
    }

    /**
     * 복제본용 독립 난수원.
     *
     * <p>시드가 null 이면(재현성을 포기한 실행) 인스턴스 번호만으로 고정 시드를 만든다.
     * 비결정적으로 두면 "가끔 다른 결과"가 나오는데, 그건 디버깅할 수 없는 종류의 차이다.
     */
    public static Rng forInstance(Long seed, int instanceIndex, long salt) {
        long base = seed == null ? 0L : seed;
        return new Rng(base + salt + (long) instanceIndex * GAMMA);
    }

    /** 다음 커서. 값은 뽑지 않는다. */
    public Rng advance() {
        return new Rng(state + GAMMA);
    }

    /** 현재 커서의 [0, 1) 균등 난수. 같은 커서는 언제 물어도 같은 값을 준다. */
    public double uniform() {
        return (mix(state) >>> 11) * UNIT;
    }

    /** 지수분포 표본과 다음 커서. rate 는 단위 시간당 발생률(λ 또는 μ)이다. */
    public Draw exponential(double rate) {
        if (rate <= 0) {
            throw new IllegalArgumentException("발생률은 0보다 커야 합니다: " + rate);
        }
        Rng next = advance();
        // u 를 (0, 1] 로 밀어 log(0) 을 피한다. 열린 끝을 그냥 두면 2^-53 확률로 무한대가 나오고,
        // 그 한 번이 시뮬레이션 전체를 멈춘다 — 재현되지 않아 원인을 찾기도 어렵다.
        double u = 1.0 - next.uniform();
        return new Draw(-Math.log(u) / rate, next);
    }

    /** 표본 하나와, 그 표본을 뽑고 난 뒤의 커서. */
    public record Draw(double value, Rng rng) {
    }

    private static long mix(long z) {
        z += GAMMA;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
