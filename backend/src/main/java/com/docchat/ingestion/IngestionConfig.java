package com.docchat.ingestion;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestionProperties.class)
class IngestionConfig {

    /** Bounded, so document processing cannot exhaust the resources it shares with the API (ADR 0001). */
    @Bean
    ThreadPoolTaskExecutor documentProcessingExecutor(IngestionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workers());
        executor.setMaxPoolSize(properties.workers());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("document-processing-");
        executor.setTaskDecorator(keepingLoggingContext());
        // Documents still in progress at shutdown are processed again at the next startup.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    /** Keeps the upload's correlation id in the logs of its background processing. */
    private static TaskDecorator keepingLoggingContext() {
        return task -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    task.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }
}
