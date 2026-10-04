package com.changlu.agentforge.model.exception;

/** Base runtime exception for model provider/transport failures. */
public class ModelException extends RuntimeException {

    private final Integer statusCode;
    private final String responseBody;

    public ModelException(String message) {
        this(message, null, null, null);
    }

    public ModelException(String message, Throwable cause) {
        this(message, null, null, cause);
    }

    public ModelException(String message, Integer statusCode, String responseBody) {
        this(message, statusCode, responseBody, null);
    }

    private ModelException(
            String message, Integer statusCode, String responseBody, Throwable cause) {
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
