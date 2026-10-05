package com.sheltr.ai.domain;

/** Daily cap on answered questions. Strict: a request is allowed while the count is below the cap. */
public final class AskQuota {

    private AskQuota() {
    }

    public static boolean allows(long askedToday, int maxPerDay) {
        return askedToday < maxPerDay;
    }
}
