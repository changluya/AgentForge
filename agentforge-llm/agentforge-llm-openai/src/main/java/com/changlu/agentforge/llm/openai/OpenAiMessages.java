package com.changlu.agentforge.llm.openai;

import com.changlu.agentforge.llm.agent.tool.ToolSpecification;
import com.changlu.agentforge.llm.chat.message.AiMessage;
import com.changlu.agentforge.llm.chat.message.ChatMessage;
import com.changlu.agentforge.llm.chat.message.ChatMessageType;
import com.changlu.agentforge.llm.chat.message.Content;
import com.changlu.agentforge.llm.chat.message.TextContent;
import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import com.changlu.agentforge.llm.chat.message.ToolExecutionResultMessage;
import com.changlu.agentforge.llm.chat.message.UserMessage;
import com.changlu.agentforge.llm.chat.request.ChatRequestParameters;
import com.changlu.agentforge.llm.chat.request.ToolChoice;
import com.changlu.agentforge.llm.internal.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared OpenAI Chat Completions wire-protocol helpers used by both
 * {@link OpenAiChatModel} and {@link OpenAiStreamingChatModel}.
 *
 * <p>Package-private on purpose: it is an implementation detail of the OpenAI adapter,
 * not part of the public AgentForge API.</p>
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>{@link ChatMessage} -> OpenAI {@code messages[]} (assistant {@code tool_calls},
 *       {@code role=tool} results, multi-{@link Content} user messages)</li>
 *   <li>{@link ToolSpecification} -> OpenAI {@code tools[]}</li>
 *   <li>{@link ToolChoice} -> OpenAI {@code tool_choice}</li>
 *   <li>OpenAI {@code tool_calls[]} -> {@link ToolExecutionRequest}</li>
 * </ul>
 *
 * @author changlu
 * @since 2026-09-13
 */
final class OpenAiMessages {

    private OpenAiMessages() {
    }

    static List<Map<String, Object>> serialize(List<ChatMessage> messages) {
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>(messages.size());
        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage) {
                result.add(toolResultMessage((ToolExecutionResultMessage) message));
            } else if (message instanceof AiMessage && ((AiMessage) message).hasToolExecutionRequests()) {
                result.add(assistantMessageWithToolCalls((AiMessage) message));
            } else if (message instanceof UserMessage && !((UserMessage) message).hasSingleText()) {
                result.add(multimodalUserMessage((UserMessage) message));
            } else {
                LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("role", role(message.type()));
                item.put("content", message.text());
                result.add(item);
            }
        }
        return result;
    }

    static Map<String, Object> assistantMessageWithToolCalls(AiMessage aiMessage) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("role", "assistant");
        item.put("content", aiMessage.text());
        List<Map<String, Object>> toolCalls = new ArrayList<Map<String, Object>>();
        for (ToolExecutionRequest toolExecutionRequest : aiMessage.toolExecutionRequests()) {
            LinkedHashMap<String, Object> toolCall = new LinkedHashMap<String, Object>();
            toolCall.put("id", toolExecutionRequest.id());
            toolCall.put("type", "function");
            LinkedHashMap<String, Object> function = new LinkedHashMap<String, Object>();
            function.put("name", toolExecutionRequest.name());
            function.put("arguments", toolExecutionRequest.arguments());
            toolCall.put("function", function);
            toolCalls.add(toolCall);
        }
        item.put("tool_calls", toolCalls);
        return item;
    }

    static Map<String, Object> toolResultMessage(ToolExecutionResultMessage message) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("role", "tool");
        item.put("tool_call_id", message.id());
        item.put("content", message.text());
        return item;
    }

    static Map<String, Object> multimodalUserMessage(UserMessage message) {
        List<Content> contents = message.contents();
        ArrayList<Object> blocks = new ArrayList<Object>(contents.size());
        for (Content content : contents) {
            if (content instanceof TextContent) {
                LinkedHashMap<String, Object> block = new LinkedHashMap<String, Object>();
                block.put("type", "text");
                block.put("text", ((TextContent) content).text());
                blocks.add(block);
            }
        }
        LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("role", "user");
        if (message.name() != null) {
            item.put("name", message.name());
        }
        item.put("content", blocks);
        return item;
    }

    static String role(ChatMessageType type) {
        if (type == ChatMessageType.SYSTEM) return "system";
        if (type == ChatMessageType.USER) return "user";
        if (type == ChatMessageType.AI) return "assistant";
        throw new IllegalArgumentException("Unsupported message type: " + type);
    }

    static List<Map<String, Object>> serializeTools(List<ToolSpecification> tools) {
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>(tools.size());
        for (ToolSpecification tool : tools) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("type", "function");
            LinkedHashMap<String, Object> function = new LinkedHashMap<String, Object>();
            function.put("name", tool.name());
            if (tool.description() != null) {
                function.put("description", tool.description());
            }
            if (tool.parameters() != null) {
                function.put("parameters", tool.parameters().toMap());
            }
            if (tool.strict() != null) {
                function.put("strict", tool.strict());
            }
            item.put("function", function);
            result.add(item);
        }
        return result;
    }

    static Object toolChoice(ChatRequestParameters parameters) {
        ToolChoice toolChoice = parameters.toolChoice();
        if (toolChoice == null) {
            return null;
        }
        switch (toolChoice) {
            case NONE:
                return "none";
            case REQUIRED:
                return "required";
            case AUTO:
                return "auto";
            case SPECIFIC:
                LinkedHashMap<String, Object> specific = new LinkedHashMap<String, Object>();
                specific.put("type", "function");
                LinkedHashMap<String, Object> function = new LinkedHashMap<String, Object>();
                function.put("name", parameters.toolChoiceName());
                specific.put("function", function);
                return specific;
            default:
                return null;
        }
    }

    /**
     * Parses a {@code tool_calls} array (blocking message or streaming delta) into
     * normalized {@link ToolExecutionRequest}s.
     *
     * @param toolCalls raw JSON array, may be {@code null}
     * @return parsed requests, never {@code null}
     */
    static List<ToolExecutionRequest> parseToolCalls(List<Object> toolCalls) {
        ArrayList<ToolExecutionRequest> result = new ArrayList<ToolExecutionRequest>();
        if (toolCalls == null) {
            return result;
        }
        for (Object toolCallValue : toolCalls) {
            Map<String, Object> toolCall = Json.object(toolCallValue);
            if (toolCall == null) {
                continue;
            }
            Map<String, Object> function = Json.object(toolCall.get("function"));
            if (function == null) {
                continue;
            }
            result.add(ToolExecutionRequest.builder()
                    .id(Json.string(toolCall.get("id")))
                    .name(Json.string(function.get("name")))
                    .arguments(Json.string(function.get("arguments")))
                    .build());
        }
        return result;
    }

    /**
     * Extracts text from a String content or a content-block array.
     *
     * @param content raw {@code content} value
     * @return concatenated text, or {@code ""} when absent
     */
    static String extractContent(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        List<Object> blocks = Json.array(content);
        if (blocks == null) return String.valueOf(content);
        StringBuilder result = new StringBuilder();
        for (Object blockValue : blocks) {
            Map<String, Object> block = Json.object(blockValue);
            if (block == null) continue;
            Object text = block.get("text");
            if (text != null) result.append(String.valueOf(text));
        }
        return result.toString();
    }

}
