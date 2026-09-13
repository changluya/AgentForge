package com.changlu.agentforge.llm.http;

import java.util.List;
import java.util.Map;

/**
 * Low-level line-oriented streaming HTTP callback used by provider adapters.
 *
 * <p>This SPI intentionally does not understand SSE. It exposes status/headers and
 * response lines so each provider can parse its own streaming protocol.</p>
 *
 * @author changlu
 * @date 2026/09/13
 */
public interface StreamingHttpResponseHandler {

    /**
     * Called once after the HTTP response status and headers are available.
     */
    void onOpen(int statusCode, Map<String, List<String>> headers);

    /**
     * Called for each UTF-8 line read from the response body.
     */
    void onLine(String line);

    /**
     * Called after the response body reaches EOF.
     */
    void onComplete();

    /**
     * Called if opening or consuming the stream fails.
     */
    void onError(Throwable error);
}
