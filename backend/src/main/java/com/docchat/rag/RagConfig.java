package com.docchat.rag;

import com.docchat.common.logging.KeepLoggingContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
class RagConfig {

    /**
     * Has no queue, so a question beyond the limit is refused at once. Waiting would hold a request
     * while the single GPU works through the others, until the question's time limit gave up on it.
     */
    @Bean
    ThreadPoolTaskExecutor questionExecutor(RagProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.maxConcurrentQuestions());
        executor.setMaxPoolSize(properties.maxConcurrentQuestions());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("question-");
        executor.setTaskDecorator(new KeepLoggingContext());
        return executor;
    }
}
