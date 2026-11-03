package com.docchat.ai;

import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

@Component
public class TextGenerator {

    private final ChatModel chatModel;

    TextGenerator(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String generate(String systemPrompt, String userPrompt) {
        try {
            Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)));
            return chatModel.call(prompt).getResult().getOutput().getText();
        } catch (RuntimeException ex) {
            throw new AiUnavailableException(ex);
        }
    }
}
