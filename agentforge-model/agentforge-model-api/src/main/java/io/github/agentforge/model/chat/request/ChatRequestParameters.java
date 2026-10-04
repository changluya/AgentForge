package io.github.agentforge.model.chat.request;

import io.github.agentforge.model.tool.spec.ToolSpecification;

import java.util.List;
import java.util.Map;

/**
 * Common request parameters understood by chat model providers.
 *
 * <p>Provider implementations may interpret unsupported fields differently. Provider-specific
 * options can be passed through {@link #customParameters()}.
 *
 * <p>Tool calling is described by {@link #tools()} plus {@link #toolChoice()}. Both accessors have
 * default implementations so existing parameter implementations remain source compatible.
 */
public interface ChatRequestParameters {

    String modelName();

    Double temperature();

    Integer maxTokens();

    Double topP();

    List<String> stopSequences();

    Map<String, Object> customParameters();

    /**
     * Returns the tools the model may call, or {@code null}/{@code empty} when none are set.
     *
     * @return list of tool specifications
     */
    default List<ToolSpecification> tools() {
        return null;
    }

    /**
     * Returns how the model should use tools, or {@code null} when unset.
     *
     * @return the tool choice
     */
    default ToolChoice toolChoice() {
        return null;
    }

    /**
     * Returns the tool name required when {@link #toolChoice()} is {@link ToolChoice#SPECIFIC}.
     *
     * @return the specific tool name, or {@code null}
     */
    default String toolChoiceName() {
        return null;
    }
}
