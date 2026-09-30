package com.campusclaw.retrieval;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 兼容接收但忽略客户端控制字段，模型规则和检索班级始终由服务端决定。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AskRequest(
        @NotBlank String question,
        @Valid @Size(max = 50) List<HistoryMessage> history,
        String system,
        String context,
        String model,
        JsonNode parameters,
        @JsonAlias("class_id") Long classId) {

    public record HistoryMessage(@NotBlank String role, @NotBlank @Size(max = 4000) String content) {
    }
}
