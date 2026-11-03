package com.docchat.testsupport;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** Gives a scripted reply and records what the real model would have received. */
public class FakeChatModel implements ChatModel {

    private final AtomicInteger calls = new AtomicInteger();
    private volatile String reply = "The answer is in the first source [1].";
    private volatile boolean failing;
    private volatile String lastPrompt;

    public void setReply(String reply) {
        this.reply = reply;
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public String lastPrompt() {
        return lastPrompt;
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.incrementAndGet();
        lastPrompt = prompt.getContents();
        if (failing) {
            throw new IllegalStateException("Simulated Ollama outage");
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
    }
}
