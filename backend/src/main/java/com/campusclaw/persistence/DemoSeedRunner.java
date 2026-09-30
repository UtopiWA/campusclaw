package com.campusclaw.persistence;

import com.campusclaw.config.AppProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

@Component
@Order(0)
@ConditionalOnProperty(prefix = "app.demo-seed", name = "enabled", havingValue = "true")
public class DemoSeedRunner implements ApplicationRunner {
    private final AppProperties properties;
    private final DemoSeedService seedService;

    public DemoSeedRunner(AppProperties properties, DemoSeedService seedService) {
        this.properties = properties;
        this.seedService = seedService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Demo data is opt-in, and its credential must come from configuration rather than source code.
        String password = properties.demoSeed().password();
        if (!StringUtils.hasText(password)) {
            throw new IllegalStateException("DEMO_SEED_PASSWORD is required when DEMO_SEED_ENABLED=true");
        }
        seedService.seed(password);
    }
}
