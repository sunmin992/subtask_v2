package org.hanbat.ses.dialogue.session;

/** 세션 수명주기. */
public enum Phase {
    ROUTING, ASSEMBLING, ELICITING, VALIDATING, BUILDING, RUNNING, DONE, FAILED;

    public boolean terminal() {
        return this == DONE || this == FAILED;
    }
}
