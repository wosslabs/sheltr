package com.sheltr.ai.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * @param question      the user's question; required, at most 2000 characters
 * @param activeHazards optional hazard type names reported near the asker by an official feed; at most 12
 */
public record AskRequestDto(@NotBlank @Size(max = 2000) String question,
                            @Size(max = 12) List<String> activeHazards) {
    public AskRequestDto(String question) {
        this(question, null);
    }
}
