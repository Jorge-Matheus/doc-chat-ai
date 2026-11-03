package com.docchat.ai;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

@Component
public class TextEmbedder {

    private static final int BATCH_SIZE = 32;

    private final EmbeddingModel embeddingModel;

    TextEmbedder(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public float[] embed(String text) {
        return embedAll(List.of(text)).getFirst();
    }

    /** Returns one vector per text, in the same order. */
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        try {
            for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
                int end = Math.min(start + BATCH_SIZE, texts.size());
                vectors.addAll(embeddingModel.embed(texts.subList(start, end)));
            }
        } catch (RuntimeException ex) {
            throw new AiUnavailableException(ex);
        }
        return vectors;
    }
}
