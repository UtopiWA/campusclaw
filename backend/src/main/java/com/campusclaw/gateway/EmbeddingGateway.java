package com.campusclaw.gateway;

import java.util.List;

public interface EmbeddingGateway {
    List<List<Double>> embed(List<String> texts);
}
