package com.docchat.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docchat.testsupport.FakeChatModel;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

class AiClientsTest {

    @Test
    void embedsInBatchesAndKeepsTheOrder() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenAnswer(call -> {
            List<String> texts = call.getArgument(0);
            return texts.stream().map(text -> new float[] {Float.parseFloat(text)}).toList();
        });
        List<String> texts = IntStream.range(0, 70).mapToObj(String::valueOf).toList();

        List<float[]> vectors = new TextEmbedder(model).embedAll(texts);

        verify(model, times(3)).embed(anyList());
        assertThat(vectors).hasSize(70);
        assertThat(vectors.get(69)[0]).isEqualTo(69f);
    }

    @Test
    void anEmptyListNeedsNoCall() {
        EmbeddingModel model = mock(EmbeddingModel.class);

        assertThat(new TextEmbedder(model).embedAll(Collections.emptyList())).isEmpty();

        verify(model, times(0)).embed(anyList());
    }

    @Test
    void embeddingFailuresBecomeAClearError() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenThrow(new ResourceAccessException("connection refused"));

        assertThatThrownBy(() -> new TextEmbedder(model).embed("text"))
                .isInstanceOfSatisfying(AiUnavailableException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getMessage()).doesNotContain("connection refused");
                });
    }

    @Test
    void generationReturnsTheModelsText() {
        FakeChatModel model = new FakeChatModel();
        model.setReply("Hello");

        assertThat(new TextGenerator(model).generate("system", "user")).isEqualTo("Hello");
        assertThat(model.lastPrompt()).contains("system").contains("user");
    }

    @Test
    void generationFailuresBecomeAClearError() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> new TextGenerator(model).generate("system", "user"))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void isAvailableOnlyWithBothModelsInstalled() {
        assertThat(availability("chat:9b", "embed:latest").isAvailable()).isTrue();
        assertThat(availability("chat:9b").isAvailable()).isFalse();
        assertThat(availability("embed:latest").isAvailable()).isFalse();
        assertThat(availability().isAvailable()).isFalse();
    }

    @Test
    void isUnavailableWhenOllamaCannotBeReached() {
        OllamaApi api = mock(OllamaApi.class);
        when(api.listModels()).thenThrow(new ResourceAccessException("connection refused"));

        assertThat(new AiAvailability(api, "chat:9b", "embed").isAvailable()).isFalse();
    }

    private static AiAvailability availability(String... installed) {
        OllamaApi api = mock(OllamaApi.class);
        List<OllamaApi.Model> models = java.util.Arrays.stream(installed)
                .map(name -> {
                    OllamaApi.Model model = mock(OllamaApi.Model.class);
                    when(model.name()).thenReturn(name);
                    return model;
                })
                .toList();
        OllamaApi.ListModelResponse response = mock(OllamaApi.ListModelResponse.class);
        when(response.models()).thenReturn(models);
        when(api.listModels()).thenReturn(response);
        return new AiAvailability(api, "chat:9b", "embed");
    }
}
