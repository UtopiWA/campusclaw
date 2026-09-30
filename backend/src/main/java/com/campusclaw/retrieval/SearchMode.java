package com.campusclaw.retrieval;

import com.campusclaw.common.BadRequestException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum SearchMode {
    KEYWORD,
    VECTOR,
    HYBRID;

    @JsonCreator
    public static SearchMode from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Search mode must be keyword, vector or hybrid");
        }
    }

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
