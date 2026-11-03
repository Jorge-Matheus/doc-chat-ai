package com.docchat.ai;

import com.docchat.common.web.ApiException;
import org.springframework.http.HttpStatus;

/** The local model could not answer, because Ollama is down or a model is missing. */
public class AiUnavailableException extends ApiException {

    public AiUnavailableException(Throwable cause) {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI_UNAVAILABLE",
                "The AI service is not available right now. Try again in a moment.");
        initCause(cause);
    }
}
