package com.campusclaw.gateway;

import java.util.List;

public interface ChatGateway {
    String complete(List<ChatMessage> messages);

    record ChatMessage(String role, String content) {
    }
}
