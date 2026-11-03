package com.docchat.ai;

import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Reports whether Ollama is running and has both models. It does not load them. */
@Component
public class AiAvailability {

    private static final Logger log = LoggerFactory.getLogger(AiAvailability.class);

    private final OllamaApi ollamaApi;
    private final String chatModel;
    private final String embeddingModel;

    AiAvailability(
            OllamaApi ollamaApi,
            @Value("${spring.ai.ollama.chat.model}") String chatModel,
            @Value("${spring.ai.ollama.embedding.model}") String embeddingModel) {
        this.ollamaApi = ollamaApi;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }

    public boolean isAvailable() {
        try {
            Set<String> installed = ollamaApi.listModels().models().stream()
                    .map(OllamaApi.Model::name)
                    .collect(Collectors.toSet());
            return installed.contains(withTag(chatModel)) && installed.contains(withTag(embeddingModel));
        } catch (RuntimeException ex) {
            // Reporting the failure is this method's purpose.
            log.warn("AI status check failed: {}", ex.getClass().getSimpleName());
            return false;
        }
    }

    /** Ollama lists a model pulled without a tag under the name "latest". */
    private static String withTag(String model) {
        return model.contains(":") ? model : model + ":latest";
    }
}
