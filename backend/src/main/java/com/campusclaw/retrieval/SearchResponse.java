package com.campusclaw.retrieval;

import java.util.List;

public record SearchResponse(SearchMode mode, String message, List<RetrievalHit> hits) {
}
