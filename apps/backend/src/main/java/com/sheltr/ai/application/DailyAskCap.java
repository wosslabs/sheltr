package com.sheltr.ai.application;

import com.sheltr.ai.domain.AskQuota;
import com.sheltr.ai.domain.ConversationRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Shared daily cap on answered questions, used by both the REST and Lumina chat paths. */
public final class DailyAskCap {

    public static final String LIMIT_MESSAGE = "Daily question limit reached. Try again tomorrow.";

    private final ConversationRepository conversationRepository;
    private final int maxAsksPerDay;
    private final Clock clock;

    public DailyAskCap(ConversationRepository conversationRepository, int maxAsksPerDay, Clock clock) {
        this.conversationRepository = conversationRepository;
        this.maxAsksPerDay = maxAsksPerDay;
        this.clock = clock;
    }

    /** True while today's USER message count (since 00:00 UTC) is below the cap. */
    public boolean allows() {
        long askedToday = conversationRepository.countUserMessagesSince(startOfTodayUtc());
        return AskQuota.allows(askedToday, maxAsksPerDay);
    }

    private Instant startOfTodayUtc() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
