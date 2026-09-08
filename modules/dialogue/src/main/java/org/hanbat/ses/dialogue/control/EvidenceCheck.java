package org.hanbat.ses.dialogue.control;

import java.util.Arrays;
import java.util.List;

/**
 * 추출값이 요청문에 실제로 근거를 두고 있는지 확인한다.
 *
 * <p>모델이 스스로 매긴 confidence 는 믿을 만한 신호가 아니다. 파라미터가 적은 모델은
 * 예시에 적힌 숫자를 그대로 베끼는 일이 잦아 — gemma2:9b 는 값을 정확히 뽑아 놓고
 * confidence 를 예시의 0.0 그대로 써 보냈다 — 신뢰도로 문턱을 걸면 멀쩡한 추출이 전부 버려진다.
 *
 * <p>대신 모델이 함께 돌려주는 evidence(근거 문구)를 요청문과 대조한다. 이건 검증할 수 있다.
 * 문장에 없는 값을 지어냈다면 근거 문구도 문장에 없다.
 */
public final class EvidenceCheck {

    /** 근거로 인정할 최소 토큰 일치 비율. */
    private static final double MIN_TOKEN_OVERLAP = 0.6;

    private EvidenceCheck() {
    }

    /**
     * @return evidence 가 요청문에 근거를 두고 있는가. 판단할 수 없으면 false.
     */
    public static boolean grounded(String request, String evidence) {
        if (request == null || request.isBlank() || evidence == null || evidence.isBlank()) {
            return false;
        }
        String haystack = squash(request);
        String needle = squash(evidence);
        if (needle.isEmpty()) {
            return false;
        }
        // 모델이 문장을 그대로 인용한 경우 — 가장 흔하고 가장 확실하다.
        if (haystack.contains(needle)) {
            return true;
        }
        // 조사를 떼거나 줄여 인용한 경우. 토큰 다수가 문장에 있으면 근거로 본다.
        List<String> tokens = Arrays.stream(evidence.trim().split("\\s+"))
                .map(EvidenceCheck::squash)
                .filter(t -> t.length() >= 2)
                .toList();
        if (tokens.isEmpty()) {
            return false;
        }
        long hits = tokens.stream().filter(haystack::contains).count();
        return (double) hits / tokens.size() >= MIN_TOKEN_OVERLAP;
    }

    /** 공백과 문장부호를 지우고 비교한다 — 인용은 대개 조사나 띄어쓰기가 어긋난다. */
    private static String squash(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
