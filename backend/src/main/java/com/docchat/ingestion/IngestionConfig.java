package com.docchat.ingestion;

import com.docchat.common.logging.KeepLoggingContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IngestionProperties.class)
@EnableScheduling
class IngestionConfig {

    /** Bounded, so document processing cannot exhaust the resources it shares with the API (ADR 0001). */
    @Bean
    ThreadPoolTaskExecutor documentProcessingExecutor(IngestionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workers());
        executor.setMaxPoolSize(properties.workers());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("document-processing-");
        executor.setTaskDecorator(new KeepLoggingContext());
        // Documents still in progress at shutdown are processed again at the next startup.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
