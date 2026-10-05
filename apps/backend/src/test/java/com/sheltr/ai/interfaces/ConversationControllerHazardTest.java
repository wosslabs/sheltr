package com.sheltr.ai.interfaces;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationControllerHazardTest {

    // An invalid request never reaches the collaborators, so nulls are safe here.
    private final ConversationController controller = new ConversationController(null, null);

    @Test
    void rejectsAnUnknownActiveHazardWithBadRequest() {
        var response = controller.ask(UUID.randomUUID(), new AskRequestDto("What should I do?", List.of("METEOR")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAMalformedCountryWithBadRequest() {
        var response = controller.ask(UUID.randomUUID(),
                new AskRequestDto("What should I do?", List.of(), "gb"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsABlankQuestionWithBadRequest() {
        var response = controller.ask(UUID.randomUUID(), new AskRequestDto("  ", List.of("FLOOD")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
