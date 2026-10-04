package com.sheltr.eval.domain;

public enum EvalKind {
    /** One corpus passage answers it. */
    ANSWERABLE,
    /** Needs passages about more than one hazard or document. */
    MULTI_STEP,
    /** Nothing to do with disasters. */
    OFF_TOPIC,
    /** About disasters, but the corpus does not answer it. */
    UNCOVERED;

    public boolean mustRefuse() {
        return this == OFF_TOPIC || this == UNCOVERED;
    }
}
