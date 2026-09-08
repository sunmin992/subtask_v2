package org.hanbat.ses.core.model;

/** multi-aspect 개수 범위. 상한은 트리 폭발을 막는 안전장치이기도 하다. */
public record IntRange(int min, int max) {

    public IntRange {
        if (min > max) {
            throw new IllegalArgumentException("min(" + min + ") > max(" + max + ")");
        }
    }

    public boolean contains(int v) {
        return v >= min && v <= max;
    }
}
