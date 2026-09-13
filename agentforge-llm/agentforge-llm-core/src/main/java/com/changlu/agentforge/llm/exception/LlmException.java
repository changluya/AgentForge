package com.changlu.agentforge.llm.exception;

/**
 * Base runtime exception for LLM provider/transport failures.
 */
public class LlmException extends RuntimeException {

    private final Integer statusCode;
    private final String responseBody;

    public LlmException(String message) {
        this(message, null, null, null);
    }

    public LlmException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    public LlmException(String message, Integer statusCode, String responseBody) {
        this(message, statusCode, responseBody, null);
    }

    private LlmException(String message, Integer statusCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
