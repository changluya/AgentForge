package com.changlu.agentforge.llm.http;

import java.io.IOException;

/**
 * Small transport SPI so providers are independent from a particular HTTP library.
 */
public interface HttpTransport {

    HttpResponse execute(HttpRequest request) throws IOException;

    /**
     * Starts a streaming HTTP request.
     *
     * <p>The default implementation keeps existing custom transports source-compatible
     * and reports that streaming is unsupported. Transports that support streaming should
     * override this method.</p>
     *
     * @author changlu
     * @date 2026/09/13
     */
    default void executeStreaming(HttpRequest request, StreamingHttpResponseHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        handler.onError(new UnsupportedOperationException(
                "Streaming HTTP is not supported by " + getClass().getName()));
    }
}
