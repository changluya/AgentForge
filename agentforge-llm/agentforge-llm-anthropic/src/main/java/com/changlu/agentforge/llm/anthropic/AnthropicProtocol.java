package com.changlu.agentforge.llm.anthropic;

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared Anthropic Messages wire-protocol helpers used by both
 * {@link AnthropicChatModel} and {@link AnthropicStreamingChatModel}.
 *
 * <p>Package-private on purpose: it is an implementation detail of the Anthropic adapter,
 * not part of the public AgentForge API.</p>
 *
 * @author changlu
 * @since 2026-09-13
 */
final class AnthropicProtocol {

    private AnthropicProtocol() {
    }

    static String collectSystemMessages(List<ChatMessage> messages) {
        StringBuilder system = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.type() == ChatMessageType.SYSTEM) {
                if (system.length() > 0) system.append("\n\n");
                system.append(message.text());
            }
        }
        return system.toString();
    }

    static List<Map<String, Object>> serializeMessages(List<ChatMessage> messages) {
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ChatMessage message : messages) {
            if (message.type() == ChatMessageType.SYSTEM) {
                continue;
            }
            LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
            if (message instanceof ToolExecutionResultMessage) {
                item.put("role", "user");
                item.put("content", toolResultContent((ToolExecutionResultMessage) message));
            } else if (message instanceof AiMessage && ((AiMessage) message).hasToolExecutionRequests()) {
                item.put("role", "assistant");
                item.put("content", assistantContent((AiMessage) message));
            } else if (message instanceof UserMessage && !((UserMessage) message).hasSingleText()) {
                item.put("role", "user");
                item.put("content", userContent((UserMessage) message));
            } else {
                item.put("role", message.type() == ChatMessageType.AI ? "assistant" : "user");
                item.put("content", message.text());
            }
            result.add(item);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Anthropic request requires at least one user/assistant message");
        }
        return result;
    }

    static Object userContent(UserMessage message) {
        if (message.hasSingleText()) {
            return message.text();
        }
        List<Content> contents = message.contents();
        ArrayList<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>(contents.size());
        for (Content content : contents) {
            if (content instanceof TextContent) {
                LinkedHashMap<String, Object> block = new LinkedHashMap<String, Object>();
                block.put("type", "text");
                block.put("text", ((TextContent) content).text());
                blocks.add(block);
            }
        }
        return blocks;
    }

    static List<Map<String, Object>> toolResultContent(ToolExecutionResultMessage message) {
        ArrayList<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        LinkedHashMap<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "tool_result");
        block.put("tool_use_id", message.id());
        block.put("content", message.text());
        if (Boolean.TRUE.equals(message.isError())) {
            block.put("is_error", Boolean.TRUE);
        }
        content.add(block);
        return content;
    }

    static List<Map<String, Object>> assistantContent(AiMessage aiMessage) {
        ArrayList<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        if (aiMessage.text() != null && !aiMessage.text().isEmpty()) {
            LinkedHashMap<String, Object> textBlock = new LinkedHashMap<String, Object>();
            textBlock.put("type", "text");
            textBlock.put("text", aiMessage.text());
            content.add(textBlock);
        }
        for (ToolExecutionRequest toolExecutionRequest : aiMessage.toolExecutionRequests()) {
            LinkedHashMap<String, Object> toolUseBlock = new LinkedHashMap<String, Object>();
            toolUseBlock.put("type", "tool_use");
            toolUseBlock.put("id", toolExecutionRequest.id());
            toolUseBlock.put("name", toolExecutionRequest.name());
            Object input = toolExecutionRequest.arguments() == null
                    ? Collections.emptyMap()
                    : Json.parse(toolExecutionRequest.arguments());
            toolUseBlock.put("input", input == null ? Collections.emptyMap() : input);
            content.add(toolUseBlock);
        }
        return content;
    }

    static List<Map<String, Object>> serializeTools(List<ToolSpecification> tools) {
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>(tools.size());
        for (ToolSpecification tool : tools) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", tool.name());
            if (tool.description() != null) {
                item.put("description", tool.description());
            }
            if (tool.parameters() != null) {
                item.put("input_schema", tool.parameters().toMap());
            } else {
                LinkedHashMap<String, Object> emptySchema = new LinkedHashMap<String, Object>();
                emptySchema.put("type", "object");
                emptySchema.put("properties", Collections.emptyMap());
                item.put("input_schema", emptySchema);
            }
            result.add(item);
        }
        return result;
    }

    static Object toolChoice(ChatRequestParameters parameters) {
        ToolChoice toolChoice = parameters.toolChoice();
        if (toolChoice == null) {
            return null;
        }
        LinkedHashMap<String, Object> value = new LinkedHashMap<String, Object>();
        switch (toolChoice) {
            case NONE:
                value.put("type", "none");
                return value;
            case REQUIRED:
                value.put("type", "any");
                return value;
            case AUTO:
                value.put("type", "auto");
                return value;
            case SPECIFIC:
                value.put("type", "tool");
                value.put("name", parameters.toolChoiceName());
                return value;
            default:
                return null;
        }
    }

    static List<ToolExecutionRequest> extractToolUses(List<Object> contentBlocks) {
        ArrayList<ToolExecutionRequest> requests = new ArrayList<ToolExecutionRequest>();
        if (contentBlocks == null) {
            return requests;
        }
        for (Object blockValue : contentBlocks) {
            Map<String, Object> block = Json.object(blockValue);
            if (block == null) continue;
            if ("tool_use".equals(Json.string(block.get("type")))) {
                String argumentsJson = Json.stringify(block.get("input") == null
                        ? Collections.emptyMap()
                        : block.get("input"));
                requests.add(ToolExecutionRequest.builder()
                        .id(Json.string(block.get("id")))
                        .name(Json.string(block.get("name")))
                        .arguments(argumentsJson)
                        .build());
            }
        }
        return requests;
    }

    static String extractText(List<Object> contentBlocks) {
        if (contentBlocks == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Object blockValue : contentBlocks) {
            Map<String, Object> block = Json.object(blockValue);
            if (block == null) continue;
            if ("text".equals(Json.string(block.get("type"))) && block.get("text") != null) {
                text.append(String.valueOf(block.get("text")));
            }
        }
        return text.toString();
    }
}