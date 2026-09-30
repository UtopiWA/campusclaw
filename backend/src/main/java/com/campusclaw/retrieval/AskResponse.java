package com.campusclaw.retrieval;

import java.util.List;

public record AskResponse(String answer, List<Citation> citations) {
    public record Citation(int number, Long materialId, String materialTitle, Long chunkId,
                           int chunkIndex, String excerpt) {
    }
}
