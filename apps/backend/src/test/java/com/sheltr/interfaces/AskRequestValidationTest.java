package com.sheltr.interfaces;

import com.sheltr.ai.interfaces.AskRequestDto;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AskRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsBlankQuestion() {
        assertThat(validator.validate(new AskRequestDto("  ", List.of()))).isNotEmpty();
    }

    @Test
    void rejectsQuestionLongerThan2000Characters() {
        assertThat(validator.validate(new AskRequestDto("x".repeat(2001), List.of()))).isNotEmpty();
    }

    @Test
    void acceptsAnAbsentOrTwoUppercaseLetterCountry() {
        assertThat(validator.validate(new AskRequestDto("q", List.of(), null))).isEmpty();
        assertThat(validator.validate(new AskRequestDto("q", List.of(), "GB"))).isEmpty();
    }

    @Test
    void rejectsACountryThatIsNotTwoUppercaseLetters() {
        assertThat(validator.validate(new AskRequestDto("q", List.of(), "gb"))).isNotEmpty();
        assertThat(validator.validate(new AskRequestDto("q", List.of(), "GBR"))).isNotEmpty();
        assertThat(validator.validate(new AskRequestDto("q", List.of(), "G1"))).isNotEmpty();
    }

    @Test
    void acceptsQuestionOf2000Characters() {
        assertThat(validator.validate(new AskRequestDto("x".repeat(2000), List.of()))).isEmpty();
    }
}
