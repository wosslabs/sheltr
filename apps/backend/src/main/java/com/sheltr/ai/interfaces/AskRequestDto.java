package com.sheltr.ai.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * @param question      the user's question; required, at most 2000 characters
 * @param activeHazards optional hazard type names reported near the asker by an official feed; at most 12
 * @param country       optional ISO 3166-1 alpha-2 code of the asker's country, two uppercase letters;
 *                      boosts guidance for that country and never filters other guidance out
 */
public record AskRequestDto(@NotBlank @Size(max = 2000) String question,
                            @Size(max = 12) List<String> activeHazards,
                            @Pattern(regexp = "[A-Z]{2}", message = "must be two uppercase letters (ISO 3166-1 alpha-2)")
                            String country) {
    public AskRequestDto(String question, List<String> activeHazards) {
        this(question, activeHazards, null);
    }

    public AskRequestDto(String question) {
        this(question, null, null);
    }
}
