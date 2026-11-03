package com.docchat.rag;

import java.util.UUID;

record RetrievedChunk(
        UUID documentId, String filename, int pageNumber, int chunkIndex, String content, double similarity) {}
