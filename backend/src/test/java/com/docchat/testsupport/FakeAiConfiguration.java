package com.docchat.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the local models with fast, predictable fakes, so tests need neither a GPU nor Ollama. */
@TestConfiguration(proxyBeanMethods = false)
public class FakeAiConfiguration {

    @Bean
    @Primary
    public FakeEmbeddingModel fakeEmbeddingModel() {
        return new FakeEmbeddingModel();
    }

    @Bean
    @Primary
    public FakeChatModel fakeChatModel() {
        return new FakeChatModel();
    }
}
