package org.hanbat.ses.core.resolve;

/**
 * 미결정 지점의 종류.
 *
 * <p>SELECT/MULTIPLICITY 는 구조 결정이고 VALUE 는 값 설정이다.
 * 구조 결정이 먼저 일어나고 그 결과로 새 값 슬롯이 파생된다.
 */
public enum SlotKind {
    SELECT, MULTIPLICITY, VALUE;

    public boolean isStructural() {
        return this != VALUE;
    }
}
