package com.campusclaw.common;

public class NotFoundException extends RuntimeException {
    public NotFoundException() {
        super("Resource not found");
    }
}

