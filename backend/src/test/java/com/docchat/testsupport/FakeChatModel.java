package com.docchat.testsupport;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Gives a scripted reply and records what the real model would have received. A request to rewrite
 * a follow-up question repeats that question unless a reply was scripted for it.
 */
public class FakeChatModel implements ChatModel {

    public static final String DEFAULT_REPLY = "The answer is in the first source [1].";

    private static final String REWRITE_MARKER = "<conversation>";
    private static final String LATEST_QUESTION = "Latest question: ";

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger rewriteCalls = new AtomicInteger();
    private volatile String reply = DEFAULT_REPLY;
    private volatile String rewriteReply;
    private volatile boolean failing;
    private volatile boolean failingOnRewrite;
    private volatile Runnable duringAnswer;
    private volatile String lastPrompt;
    private volatile String lastRewritePrompt;

    public void setReply(String reply) {
        this.reply = reply;
    }

    public void setRewriteReply(String rewriteReply) {
        this.rewriteReply = rewriteReply;
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public void setFailingOnRewrite(boolean failingOnRewrite) {
        this.failingOnRewrite = failingOnRewrite;
    }

    /** Runs while the model is answering, for what can happen during those seconds. */
    public void setDuringAnswer(Runnable duringAnswer) {
        this.duringAnswer = duringAnswer;
    }

    public void reset() {
        reply = DEFAULT_REPLY;
        rewriteReply = null;
        failing = false;
        failingOnRewrite = false;
        duringAnswer = null;
    }

    public String lastPrompt() {
        return lastPrompt;
    }

    public String lastRewritePrompt() {
        return lastRewritePrompt;
    }

    public int calls() {
        return calls.get();
    }

    public int rewriteCalls() {
        return rewriteCalls.get();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.incrementAndGet();
        String contents = prompt.getContents();
        if (contents.contains(REWRITE_MARKER)) {
            return rewrite(contents);
        }
        lastPrompt = contents;
        if (failing) {
            throw new IllegalStateException("Simulated Ollama outage");
        }
        if (duringAnswer != null) {
            duringAnswer.run();
        }
        return response(reply);
    }

    private ChatResponse rewrite(String contents) {
        rewriteCalls.incrementAndGet();
        lastRewritePrompt = contents;
        if (failing || failingOnRewrite) {
            throw new IllegalStateException("Simulated Ollama outage");
        }
        String latestQuestion = contents.substring(contents.lastIndexOf(LATEST_QUESTION) + LATEST_QUESTION.length());
        return response(rewriteReply != null ? rewriteReply : latestQuestion);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
