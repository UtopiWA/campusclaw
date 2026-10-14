package com.campusclaw.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 为讲义解析和 SSE 长连接分别提供有界执行器，避免慢依赖耗尽进程资源。 */
@Configuration
public class AsyncConfig implements WebMvcConfigurer {
    private final AppProperties properties;

    public AsyncConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Bean(name = "materialIngestionExecutor")
    public Executor materialIngestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("material-ingestion-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(name = "conversationStreamExecutor")
    public AsyncTaskExecutor conversationStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("conversation-stream-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(conversationStreamExecutor());
        configurer.setDefaultTimeout(properties.retrieval().readTimeout().plusSeconds(10).toMillis());
    }
}
