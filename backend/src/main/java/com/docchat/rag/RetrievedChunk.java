package com.docchat.rag;

import java.util.UUID;

record RetrievedChunk(UUID documentId, String filename, int pageNumber, String content, double similarity) {}
