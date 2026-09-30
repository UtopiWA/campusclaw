package com.campusclaw.retrieval;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SearchRequest(
        @NotBlank String query,
        SearchMode mode,
        @Min(1) @Max(20) Integer limit,
        @JsonAlias("class_id") Long classId) {
}
