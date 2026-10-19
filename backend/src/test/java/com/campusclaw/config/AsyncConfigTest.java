package com.campusclaw.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

class AsyncConfigTest {

    @Test
    void configuresBoundedConversationExecutorAndMvcTimeout() {
        AppProperties properties = mock(AppProperties.class);
        when(properties.retrieval()).thenReturn(new AppProperties.Retrieval(
                Duration.ofSeconds(3), Duration.ofSeconds(30),
                1000, 10, 20, 0.35, 0.55, 0.45, 60, true));
        AsyncConfig config = new AsyncConfig(properties);
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.conversationStreamExecutor();

        assertThat(executor.getCorePoolSize()).isEqualTo(4);
        assertThat(executor.getMaxPoolSize()).isEqualTo(16);
        assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(50);
        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);

        AsyncSupportConfigurer asyncSupport = mock(AsyncSupportConfigurer.class);
        config.configureAsyncSupport(asyncSupport);
        ArgumentCaptor<AsyncTaskExecutor> configuredExecutor = ArgumentCaptor.forClass(AsyncTaskExecutor.class);
        verify(asyncSupport).setTaskExecutor(configuredExecutor.capture());
        verify(asyncSupport).setDefaultTimeout(40_000L);
        executor.shutdown();
        ((ThreadPoolTaskExecutor) configuredExecutor.getValue()).shutdown();
    }

    @Test
    void rejectsWorkAfterBoundedPoolAndQueueAreSaturated() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config().conversationStreamExecutor();
        CountDownLatch release = new CountDownLatch(1);

        try {
            for (int index = 0; index < 66; index++) {
                executor.execute(() -> await(release));
            }
            assertThatThrownBy(() -> executor.execute(() -> { }))
                    .isInstanceOf(TaskRejectedException.class);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void doesNotPropagateCallerSecurityContextIntoStreamWorker() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config().conversationStreamExecutor();
        AtomicReference<Authentication> workerAuthentication = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("student", "token", java.util.List.of()));

        try {
            executor.execute(() -> {
                workerAuthentication.set(SecurityContextHolder.getContext().getAuthentication());
                completed.countDown();
            });
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(workerAuthentication.get()).isNull();
        } finally {
            SecurityContextHolder.clearContext();
            executor.shutdown();
        }
    }

    private AsyncConfig config() {
        AppProperties properties = mock(AppProperties.class);
        when(properties.retrieval()).thenReturn(new AppProperties.Retrieval(
                Duration.ofSeconds(3), Duration.ofSeconds(30),
                1000, 10, 20, 0.35, 0.55, 0.45, 60, true));
        return new AsyncConfig(properties);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
