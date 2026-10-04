package com.sheltr.ingestion.interfaces;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionControllerValidationTest {

    // The service is never reached for an invalid request, so null collaborators are safe here.
    private final IngestionController controller = new IngestionController(null, null);

    @Test
    void rejectsARequestWithoutALicence() {
        var response = controller.trigger(new TriggerIngestionRequestDto(
                "https://example.org/a.pdf", "PDF", "Title", "Agency", List.of("FLOOD"), "2021-03-15", "", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAnUnknownHazard() {
        var response = controller.trigger(new TriggerIngestionRequestDto(
                "https://example.org/a.pdf", "PDF", "Title", "Agency", List.of("METEOR"), "2021-03-15",
                "test-fixture-licence", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAMalformedDate() {
        var response = controller.trigger(new TriggerIngestionRequestDto(
                "https://example.org/a.pdf", "PDF", "Title", "Agency", List.of("FLOOD"), "15 March 2021",
                "test-fixture-licence", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
