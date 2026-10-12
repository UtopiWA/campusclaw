package com.campusclaw.knowledge;

import com.campusclaw.gateway.VectorStoreGateway;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 在 demo seed 之后校验 collection；材料恢复由应用就绪后的有界调度器负责。 */
@Component
@Order(100)
@ConditionalOnProperty(prefix = "app.retrieval", name = "initialize-vector-store", havingValue = "true")
public class RetrievalStartupRunner implements ApplicationRunner {
    private final VectorStoreGateway vectors;

    public RetrievalStartupRunner(VectorStoreGateway vectors) {
        this.vectors = vectors;
    }

    @Override
    public void run(ApplicationArguments args) {
        vectors.ensureCollection();
    }
}
