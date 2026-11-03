package com.docchat.chat;

import java.time.Instant;
import java.util.UUID;

record Conversation(UUID id, String title, Instant createdAt, Instant updatedAt) {}
