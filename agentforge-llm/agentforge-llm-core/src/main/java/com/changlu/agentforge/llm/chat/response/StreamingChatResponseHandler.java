package com.changlu.agentforge.llm.chat.response;

/**
 * Receives the lifecycle events of a streaming chat request.
 *
 * <p>Implementations should keep callbacks lightweight because providers may invoke them
 * from an HTTP I/O thread.</p>
 *
 * @author changlu
 * @date 2026/09/13
 */
public interface StreamingChatResponseHandler {

    /**
     * Called for each incremental text fragment returned by the model.
     */
    void onPartialResponse(String partialResponse);

    /**
     * Called exactly once after a successful stream completes.
     */
    void onCompleteResponse(ChatResponse completeResponse);

    /**
     * Called when the stream fails before successful completion.
     */
    void onError(Throwable error);
}
