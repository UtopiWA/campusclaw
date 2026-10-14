package com.campusclaw.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;

class ApiExceptionHandlerTest {

    @Test
    void mapsSaturatedAsyncExecutorToSanitizedServiceUnavailableResponse() {
        var response = new ApiExceptionHandler().taskRejected();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry(
                "error", "Streaming service is temporarily unavailable");
        assertThat(response.getBody().toString()).doesNotContain(TaskRejectedException.class.getName());
    }
}
