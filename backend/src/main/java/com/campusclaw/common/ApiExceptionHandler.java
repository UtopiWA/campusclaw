package com.campusclaw.common;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<Map<String, String>> badRequest(BadRequestException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound() {
        return error(HttpStatus.NOT_FOUND, "Resource not found");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, String>> forbidden() {
        return error(HttpStatus.FORBIDDEN, "Forbidden");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Invalid request");
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooLarge() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "File is too large");
    }

    @ExceptionHandler(StorageException.class)
    ResponseEntity<Map<String, String>> storageFailure() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "File operation failed");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> databaseFailure() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Database operation failed");
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
