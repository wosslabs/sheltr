package com.sheltr.eval.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

/** JSON settings shared by every eval file: snake_case names, unknown fields ignored. */
public final class EvalJson {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Same as MAPPER but rejects unknown fields, so typos in hand-written question files fail loudly. */
    public static final ObjectMapper STRICT = MAPPER.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** Writes one object per line, for .jsonl files. */
    public static final ObjectWriter LINE_WRITER = MAPPER.writer().without(SerializationFeature.INDENT_OUTPUT);

    private EvalJson() {
    }
}
