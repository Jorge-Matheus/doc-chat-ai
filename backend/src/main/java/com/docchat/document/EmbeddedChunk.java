package com.docchat.document;

/** A passage of one page with its embedding. Chunks never cross pages, so citations stay exact. */
public record EmbeddedChunk(int pageNumber, int chunkIndex, String content, float[] embedding) {}
