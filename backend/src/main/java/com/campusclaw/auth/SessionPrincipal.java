package com.campusclaw.auth;

import java.io.Serializable;

public record SessionPrincipal(Long userId) implements Serializable {
}

