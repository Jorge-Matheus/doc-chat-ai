package com.docchat.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Hashes the words of a text into a vector, so texts sharing words are similar and unrelated texts
 * are not. It has the dimension of the database column.
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 1024;

    private volatile boolean failing;

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (failing) {
            throw new IllegalStateException("Simulated Ollama outage");
        }
        List<Embedding> embeddings = new ArrayList<>();
        for (String text : request.getInstructions()) {
            embeddings.add(new Embedding(vectorOf(text), embeddings.size()));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vectorOf(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private static float[] vectorOf(String text) {
        float[] vector = new float[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3) {
                vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1;
            }
        }
        double length = 0;
        for (float value : vector) {
            length += value * value;
        }
        if (length == 0) {
            vector[0] = 1;
            return vector;
        }
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] /= (float) Math.sqrt(length);
        }
        return vector;
    }
}
