package com.changlu.agentforge.llm.chat.request;

import java.util.List;
import java.util.Map;

/**
 * Common request parameters understood by chat model providers.
 *
 * <p>Provider implementations may interpret unsupported fields differently.
 * Provider-specific options can be passed through {@link #customParameters()}.</p>
 */
public interface ChatRequestParameters {

    String modelName();

    Double temperature();

    Integer maxTokens();

    Double topP();

    List<String> stopSequences();

    Map<String, Object> customParameters();
}
