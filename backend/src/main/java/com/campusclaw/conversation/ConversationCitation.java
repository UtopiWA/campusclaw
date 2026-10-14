package com.campusclaw.conversation;

public record ConversationCitation(
        int number,
        Long materialId,
        String materialTitle,
        Long chunkId,
        int chunkIndex,
        String excerpt) {
}
