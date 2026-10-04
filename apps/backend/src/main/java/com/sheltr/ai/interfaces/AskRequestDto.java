package com.sheltr.ai.interfaces;

import java.util.List;

/**
 * @param activeHazards optional hazard type names reported near the asker by an official feed
 */
public record AskRequestDto(String question, List<String> activeHazards) {
    public AskRequestDto(String question) {
        this(question, null);
    }
}
