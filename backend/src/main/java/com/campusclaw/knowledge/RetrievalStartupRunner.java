package com.campusclaw.knowledge;

import com.campusclaw.gateway.VectorStoreGateway;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 在 demo seed 之后校验 collection，并幂等补齐历史材料索引。 */
@Component
@Order(100)
@ConditionalOnProperty(prefix = "app.retrieval", name = "initialize-vector-store", havingValue = "true")
public class RetrievalStartupRunner implements ApplicationRunner {
    private final VectorStoreGateway vectors;
    private final IndexingService indexing;

    public RetrievalStartupRunner(VectorStoreGateway vectors, IndexingService indexing) {
        this.vectors = vectors;
        this.indexing = indexing;
    }

    @Override
    public void run(ApplicationArguments args) {
        vectors.ensureCollection();
        indexing.backfill();
    }
}
