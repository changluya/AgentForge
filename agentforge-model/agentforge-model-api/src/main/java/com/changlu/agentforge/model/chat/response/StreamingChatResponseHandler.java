package com.changlu.agentforge.model.chat.response;

/**
 * Receives the lifecycle events of a streaming chat request.
 *
 * <p>Implementations should keep callbacks lightweight because providers may invoke them from an
 * HTTP I/O thread.
 *
 * @author changlu
 * @date 2026/09/13
 */
public interface StreamingChatResponseHandler {

    /** Called for each incremental text fragment returned by the model. */
    void onPartialResponse(String partialResponse);

    /**
     * Called for each incremental thinking/reasoning fragment returned by the model.
     *
     * <p>Models with a built-in reasoning phase (for example DeepSeek-style {@code
     * reasoning_content} or Anthropic {@code thinking} blocks) stream their internal
     * chain-of-thought here before any visible text arrives. Middlewares that do not need reasoning
     * deltas may simply ignore it.
     *
     * @param partialThinking incremental thinking fragment, non-empty
     */
    default void onPartialThinking(String partialThinking) {}

    /** Called exactly once after a successful stream completes. */
    void onCompleteResponse(ChatResponse completeResponse);

    /** Called when the stream fails before successful completion. */
    void onError(Throwable error);
}
