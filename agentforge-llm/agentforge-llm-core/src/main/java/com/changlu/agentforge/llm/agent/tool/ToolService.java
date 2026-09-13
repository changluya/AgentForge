package com.changlu.agentforge.llm.agent.tool;

import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import com.changlu.agentforge.llm.chat.ChatModel;
import com.changlu.agentforge.llm.chat.message.AiMessage;
import com.changlu.agentforge.llm.chat.message.ChatMessage;
import com.changlu.agentforge.llm.chat.message.ToolExecutionResultMessage;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.request.ChatRequestParameters;
import com.changlu.agentforge.llm.chat.request.DefaultChatRequestParameters;
import com.changlu.agentforge.llm.chat.response.ChatResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Executes the inference-and-tool loop for a {@link ChatModel}: it repeatedly calls the model
 * with the registered tools, executes any {@link ToolExecutionRequest} the model returns, appends
 * the results back as {@link ToolExecutionResultMessage}s, and re-calls the model until no tool is
 * requested (or the round-trip limit is reached).
 *
 * <p>This is a faithful AgentForge port of LangChain4j's {@code dev.langchain4j.service.tool.ToolService},
 * scoped to the self-contained AgentForge LLM core (no AI-Service reflection/chains, no compensation
 * or async machinery).</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * ToolService toolService = new ToolService();
 * toolService.tools(Arrays.asList(new WeatherTools()));   // auto-register every @Tool method
 * ToolChatResult result = toolService.chat(model, parameters, messages);
 * }</pre>
 *
 * @author changlu
 * @since 2026-09-13
 */
public final class ToolService {

    private static final ToolArgumentsErrorHandler RETHROW_ARGUMENTS_ERROR = (error, context) -> {
        if (error instanceof RuntimeException) {
            throw (RuntimeException) error;
        }
        throw new RuntimeException(error);
    };

    private static final ToolExecutionErrorHandler EXECUTION_ERROR_TO_LLM = (error, context) -> {
        String message = error.getMessage() == null || error.getMessage().trim().isEmpty()
                ? error.getClass().getName()
                : error.getMessage();
        return ToolErrorHandlerResult.text(message);
    };

    private static final Function<ToolExecutionRequest, ToolExecutionResultMessage> THROW_ON_HALLUCINATED =
            request -> {
                throw new IllegalArgumentException(
                        "Model requested a tool '" + request.name() + "' that is not available");
            };

    private final Map<String, ToolSpecification> toolSpecifications = new LinkedHashMap<String, ToolSpecification>();
    private final Map<String, ToolExecutor> toolExecutors = new LinkedHashMap<String, ToolExecutor>();
    private final Map<String, ReturnBehavior> returnBehaviors = new LinkedHashMap<String, ReturnBehavior>();

    private ToolArgumentsErrorHandler argumentsErrorHandler = RETHROW_ARGUMENTS_ERROR;
    private ToolExecutionErrorHandler executionErrorHandler = EXECUTION_ERROR_TO_LLM;
    private Function<ToolExecutionRequest, ToolExecutionResultMessage> hallucinatedToolNameStrategy =
            THROW_ON_HALLUCINATED;
    private int maxToolCallingRoundTrips = 100;

    public ToolService() {
    }

    /**
     * Registers a set of tools keyed by their {@link ToolSpecification}.
     */
    public void tools(Map<ToolSpecification, ToolExecutor> tools) {
        if (tools == null) {
            return;
        }
        for (Map.Entry<ToolSpecification, ToolExecutor> entry : tools.entrySet()) {
            registerTool(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Registers a single tool.
     */
    public void tool(ToolSpecification specification, ToolExecutor executor) {
        registerTool(specification, executor);
    }

    /**
     * Registers a single tool with an explicit {@link ReturnBehavior}.
     */
    public void tool(ToolSpecification specification, ToolExecutor executor, ReturnBehavior returnBehavior) {
        registerTool(specification, executor, returnBehavior);
    }

    /**
     * Scans each provided object for {@link Tool}-annotated methods, derives their
     * {@link ToolSpecification}s and registers a reflective {@link DefaultToolExecutor}.
     */
    public void tools(Collection<Object> objectsWithTools) {
        if (objectsWithTools == null) {
            return;
        }
        for (Object objectWithTools : objectsWithTools) {
            for (addTool object : findTools(objectWithTools)) {
                registerTool(object.specification(), object.executor(), object.returnBehavior());
            }
        }
    }

    /**
     * Registers a single {@link Tool} method of an object.
     */
    public void tool(Object objectWithTool, String methodName) {
        java.lang.reflect.Method method = findAnnotatedMethod(objectWithTool.getClass(), methodName);
        if (method == null) {
            throw new IllegalArgumentException(
                    "Method '" + methodName + "' annotated with @Tool not found on " + objectWithTool.getClass());
        }
        registerMethod(objectWithTool, method);
    }

    private void registerMethod(Object objectWithTool, java.lang.reflect.Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
        DefaultToolExecutor executor = new DefaultToolExecutor(objectWithTool, method);
        registerTool(specification, executor, tool.returnBehavior());
    }

    /**
     * Registers every {@link Tool}-annotated method of an object.
     */
    public void tools(Object objectWithTools) {
        List<addTool> found = findTools(objectWithTools);
        if (found.isEmpty()) {
            throw new IllegalArgumentException(
                    "Object '" + objectWithTools.getClass().getName() + "' has no methods annotated with @Tool");
        }
        for (addTool tool : found) {
            registerTool(tool.specification(), tool.executor(), tool.returnBehavior());
        }
    }

    public ToolArgumentsErrorHandler argumentsErrorHandler() {
        return argumentsErrorHandler;
    }

    public void argumentsErrorHandler(ToolArgumentsErrorHandler handler) {
        this.argumentsErrorHandler = handler == null ? RETHROW_ARGUMENTS_ERROR : handler;
    }

    public ToolExecutionErrorHandler executionErrorHandler() {
        return executionErrorHandler;
    }

    public void executionErrorHandler(ToolExecutionErrorHandler handler) {
        this.executionErrorHandler = handler == null ? EXECUTION_ERROR_TO_LLM : handler;
    }

    public void hallucinatedToolNameStrategy(
            Function<ToolExecutionRequest, ToolExecutionResultMessage> strategy) {
        this.hallucinatedToolNameStrategy = strategy == null ? THROW_ON_HALLUCINATED : strategy;
    }

    public int maxToolCallingRoundTrips() {
        return maxToolCallingRoundTrips;
    }

    public void maxToolCallingRoundTrips(int maxToolCallingRoundTrips) {
        this.maxToolCallingRoundTrips = maxToolCallingRoundTrips;
    }

    /**
     * @return the registered tool specifications (what is sent to the LLM)
     */
    public List<ToolSpecification> toolSpecifications() {
        return Collections.unmodifiableList(new ArrayList<ToolSpecification>(toolSpecifications.values()));
    }

    public Map<String, ToolExecutor> toolExecutors() {
        return Collections.unmodifiableMap(toolExecutors);
    }

    /**
     * Runs the inference-and-tools loop starting from the given messages.
     *
     * @param model       the chat model
     * @param parameters  user-provided request parameters; the registered tools are merged into them
     * @param messages    the conversation so far (will be appended to during tool rounds)
     * @return the tool-service result
     */
    public ToolChatResult chat(ChatModel model, ChatRequestParameters parameters, List<ChatMessage> messages) {
        List<ChatMessage> working = new ArrayList<ChatMessage>(messages);
        List<ToolExecution> executions = new ArrayList<ToolExecution>();
        List<ChatResponse> intermediateResponses = new ArrayList<ChatResponse>();

        int roundTripsLeft = maxToolCallingRoundTrips;
        ChatResponse response;
        while (true) {
            if (roundTripsLeft-- == 0) {
                throw new IllegalStateException(
                        "Exceeded " + maxToolCallingRoundTrips + " tool calling round trips (maxToolCallingRoundTrips)");
            }
            ChatRequestParameters requestParameters = requestParameters(parameters);
            response = model.chat(ChatRequest.builder().messages(working).parameters(requestParameters).build());
            AiMessage aiMessage = response.aiMessage();
            working.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                break;
            }

            intermediateResponses.add(response);

            boolean anyToolErrored = false;
            List<ReturnBehavior> behaviors = new ArrayList<ReturnBehavior>();
            Map<ToolExecutionRequest, ToolExecutionResult> results =
                    new LinkedHashMap<ToolExecutionRequest, ToolExecutionResult>();

            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                ToolExecution execution = execute(request, working, executions);
                results.put(request, execution.result());
                anyToolErrored = anyToolErrored || execution.hasFailed();
                ReturnBehavior behavior = returnBehaviors.get(request.name());
                behaviors.add(behavior == null ? ReturnBehavior.TO_LLM : behavior);
            }

            List<ToolExecutionResultMessage> resultMessages = toResultMessages(aiMessage.toolExecutionRequests(), results);
            working.addAll(resultMessages);

            if (shouldReturnImmediately(anyToolErrored, behaviors)) {
                return ToolChatResult.of(response, executions, intermediateResponses);
            }
        }

        return ToolChatResult.of(response, executions, intermediateResponses);
    }

    private ToolExecution execute(ToolExecutionRequest request, List<ChatMessage> working,
                                  List<ToolExecution> executions) {
        ToolExecutor executor = toolExecutors.get(request.name());
        if (executor == null) {
            ToolExecutionResultMessage message = hallucinatedToolNameStrategy.apply(request);
            working.add(message);
            ToolExecution execution = ToolExecution.builder()
                    .request(request)
                    .result(ToolExecutionResult.builder().isError(true).text(message.text()).build())
                    .startTime(LocalDateTime.now())
                    .finishTime(LocalDateTime.now())
                    .build();
            executions.add(execution);
            return execution;
        }

        LocalDateTime start = LocalDateTime.now();
        ToolExecutionResult result;
        try {
            result = executeWithErrorHandling(executor, request);
        } catch (RuntimeException error) {
            result = ToolExecutionResult.failure(errorText(error), error);
        }
        ToolExecution execution = ToolExecution.builder()
                .request(request)
                .result(result)
                .startTime(start)
                .finishTime(LocalDateTime.now())
                .build();
        executions.add(execution);
        return execution;
    }

    private ToolExecutionResult executeWithErrorHandling(ToolExecutor executor, ToolExecutionRequest request) {
        try {
            return executor.executeWithResult(request, null);
        } catch (ToolArgumentsException e) {
            ToolErrorHandlerResult handled = argumentsErrorHandler.handle(e,
                    ToolErrorContext.builder().toolExecutionRequest(request).rawError(e).build());
            if (handled != null) {
                return ToolExecutionResult.builder()
                        .isError(true)
                        .text(handled.text())
                        .result(e)
                        .build();
            }
            throw e;
        } catch (RuntimeException e) {
            ToolErrorHandlerResult handled = executionErrorHandler.handle(e,
                    ToolErrorContext.builder().toolExecutionRequest(request).rawError(e).build());
            if (handled != null) {
                return ToolExecutionResult.builder()
                        .isError(true)
                        .text(handled.text())
                        .result(e)
                        .build();
            }
            throw e;
        }
    }

    private List<ToolExecutionResultMessage> toResultMessages(
            List<ToolExecutionRequest> requests, Map<ToolExecutionRequest, ToolExecutionResult> results) {
        List<ToolExecutionResultMessage> messages = new ArrayList<ToolExecutionResultMessage>();
        for (ToolExecutionRequest request : requests) {
            ToolExecutionResult result = results.get(request);
            ToolExecutionResultMessage message = new ToolExecutionResultMessage(
                    request.id(), request.name(), result.text());
            messages.add(message);
        }
        return messages;
    }

    private ChatRequestParameters requestParameters(ChatRequestParameters userParameters) {
        if (toolSpecifications.isEmpty()) {
            return userParameters;
        }
        DefaultChatRequestParameters toolParameters = DefaultChatRequestParameters.builder()
                .tools(new ArrayList<ToolSpecification>(toolSpecifications.values()))
                .build();
        // User parameters override non-tool fields; registered tools are always included.
        return DefaultChatRequestParameters.merge(toolParameters, userParameters);
    }

    private void registerTool(ToolSpecification specification, ToolExecutor executor) {
        registerTool(specification, executor, ReturnBehavior.TO_LLM);
    }

    private void registerTool(ToolSpecification specification, ToolExecutor executor, ReturnBehavior returnBehavior) {
        if (specification == null || executor == null) {
            return;
        }
        if (toolSpecifications.containsKey(specification.name())) {
            throw new IllegalArgumentException(
                    "Tool name '" + specification.name() + "' is already registered");
        }
        toolSpecifications.put(specification.name(), specification);
        toolExecutors.put(specification.name(), executor);
        if (returnBehavior != null) {
            returnBehaviors.put(specification.name(), returnBehavior);
        }
    }

    private static boolean shouldReturnImmediately(boolean anyToolErrored, List<ReturnBehavior> behaviors) {
        if (anyToolErrored) {
            return false;
        }
        if (behaviors.isEmpty()) {
            return false;
        }
        for (ReturnBehavior behavior : behaviors) {
            if (behavior == ReturnBehavior.TO_LLM) {
                return false;
            }
        }
        // All IMMEDIATE, or the last one is IMMEDIATE_IF_LAST (already excluded TO_LLM).
        ReturnBehavior last = behaviors.get(behaviors.size() - 1);
        if (last == ReturnBehavior.TO_LLM) {
            return false; // defensive; all-TO_LLM already returned false above
        }
        return true;
    }

    private static String errorText(Throwable error) {
        return error.getMessage() == null || error.getMessage().trim().isEmpty()
                ? error.getClass().getName()
                : error.getMessage();
    }

    // ---- Reflection helpers ----

    private static List<addTool> findTools(Object objectWithTools) {
        if (objectWithTools == null) {
            throw new IllegalArgumentException("objectWithTools must not be null");
        }
        List<addTool> result = new ArrayList<addTool>();
        for (java.lang.reflect.Method method : allConcreteMethods(objectWithTools.getClass())) {
            if (method.isAnnotationPresent(Tool.class)) {
                Tool tool = method.getAnnotation(Tool.class);
                ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
                DefaultToolExecutor executor = new DefaultToolExecutor(objectWithTools, method);
                result.add(new addTool(specification, executor, tool.returnBehavior()));
            }
        }
        return result;
    }

    private static java.lang.reflect.Method findAnnotatedMethod(Class<?> clazz, String methodName) {
        for (java.lang.reflect.Method method : allConcreteMethods(clazz)) {
            if (method.isAnnotationPresent(Tool.class) && method.getName().equals(methodName)) {
                return method;
            }
        }
        return null;
    }

    private static List<java.lang.reflect.Method> allConcreteMethods(Class<?> clazz) {
        Map<String, java.lang.reflect.Method> bySignature = new LinkedHashMap<String, java.lang.reflect.Method>();
        collectConcreteMethods(clazz, bySignature);
        return new ArrayList<java.lang.reflect.Method>(bySignature.values());
    }

    private static void collectConcreteMethods(Class<?> clazz,
                                               Map<String, java.lang.reflect.Method> bySignature) {
        if (clazz == null || clazz == Object.class) {
            return;
        }
        for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                bySignature.put(methodSignature(method), method);
            }
        }
        collectConcreteMethods(clazz.getSuperclass(), bySignature);
    }

    private static String methodSignature(java.lang.reflect.Method method) {
        StringBuilder sb = new StringBuilder(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(types[i].getName());
        }
        return sb.append(')').toString();
    }

    /**
     * Holder used during reflection scanning.
     */
    private static final class addTool {
        private final ToolSpecification specification;
        private final ToolExecutor executor;
        private final ReturnBehavior returnBehavior;

        private addTool(ToolSpecification specification, ToolExecutor executor, ReturnBehavior returnBehavior) {
            this.specification = specification;
            this.executor = executor;
            this.returnBehavior = returnBehavior;
        }

        ToolSpecification specification() {
            return specification;
        }

        ToolExecutor executor() {
            return executor;
        }

        ReturnBehavior returnBehavior() {
            return returnBehavior;
        }
    }

    /**
     * Result of a {@link ToolService} inference-and-tools loop.
     */
    public static final class ToolChatResult {

        private final ChatResponse finalResponse;
        private final List<ToolExecution> toolExecutions;
        private final List<ChatResponse> intermediateResponses;

        private ToolChatResult(ChatResponse finalResponse, List<ToolExecution> toolExecutions,
                               List<ChatResponse> intermediateResponses) {
            this.finalResponse = finalResponse;
            this.toolExecutions = Collections.unmodifiableList(new ArrayList<ToolExecution>(toolExecutions));
            this.intermediateResponses =
                    Collections.unmodifiableList(new ArrayList<ChatResponse>(intermediateResponses));
        }

        static ToolChatResult of(ChatResponse finalResponse, List<ToolExecution> toolExecutions,
                                 List<ChatResponse> intermediateResponses) {
            return new ToolChatResult(finalResponse, toolExecutions, intermediateResponses);
        }

        public ChatResponse finalResponse() {
            return finalResponse;
        }

        public List<ToolExecution> toolExecutions() {
            return toolExecutions;
        }

        public List<ChatResponse> intermediateResponses() {
            return intermediateResponses;
        }
    }
}