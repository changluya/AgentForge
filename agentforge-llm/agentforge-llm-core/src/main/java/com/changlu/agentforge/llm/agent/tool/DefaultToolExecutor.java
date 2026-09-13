package com.changlu.agentforge.llm.agent.tool;

import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import com.changlu.agentforge.llm.internal.json.Json;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

/**
 * A {@link ToolExecutor} that executes a {@link Tool}-annotated method reflectively.
 *
 * <p>Mirrors LangChain4j's {@code dev.langchain4j.service.tool.DefaultToolExecutor}: it binds the JSON
 * arguments of a {@link ToolExecutionRequest} to method parameters (performing light type coercion)
 * and invokes the target method. The return value is turned into the text sent to the LLM:</p>
 * <ul>
 *   <li>{@code String} — returned as-is;</li>
 *   <li>{@code void} — the literal {@code "Success"};</li>
 *   <li>anything else — serialized into a JSON string.</li>
 * </ul>
 *
 * @author changlu
 * @since 2026-09-13
 */
public class DefaultToolExecutor implements ToolExecutor {

    private final Object object;
    private final Method method;

    public DefaultToolExecutor(Object object, Method method) {
        this.object = object;
        this.method = method;
    }

    public Method method() {
        return method;
    }

    @Override
    public ToolExecutionResult executeWithResult(ToolExecutionRequest request, Object memoryId) {
        Object result = invoke(request);
        return ToolExecutionResult.builder()
                .result(result)
                .text(text(result))
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        Object result = invoke(request);
        return text(result);
    }

    private Object invoke(ToolExecutionRequest request) {
        Object[] arguments = prepareArguments(request);
        try {
            method.setAccessible(true);
            return method.invoke(object, arguments);
        } catch (IllegalAccessException e) {
            throw new ToolExecutionException("Cannot access tool method '" + method.getName() + "'", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ToolArgumentsException) {
                throw (ToolArgumentsException) cause;
            }
            throw new ToolExecutionException("Tool '" + request.name() + "' execution failed", cause);
        }
    }

    private static String text(Object result) {
        if (result == null) {
            return "Success";
        }
        if (result instanceof String) {
            return (String) result;
        }
        return Json.stringify(result);
    }

    private Object[] prepareArguments(ToolExecutionRequest request) {
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            return new Object[0];
        }
        Map<String, Object> argumentsMap = ToolExecutionRequestUtil.argumentsAsMap(request);
        Object[] bound = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            String parameterName = parameterName(parameter);
            Object value = argumentsMap.get(parameterName);
            bound[i] = coerce(value, parameter.getType(), parameterName, request.name());
        }
        return bound;
    }

    private static String parameterName(Parameter parameter) {
        P pAnnotation = parameter.getAnnotation(P.class);
        if (pAnnotation != null && pAnnotation.name() != null && !pAnnotation.name().trim().isEmpty()) {
            return pAnnotation.name();
        }
        return parameter.getName();
    }

    private static Object coerce(Object value, Class<?> targetType, String parameterName, String toolName) {
        if (value == null) {
            throw new ToolArgumentsException(
                    "Missing required argument '" + parameterName + "' for tool '" + toolName + "'");
        }
        if (targetType == String.class) {
            return String.valueOf(value);
        }
        if (targetType == int.class || targetType == Integer.class) {
            return toNumber(value, parameterName, toolName).intValue();
        }
        if (targetType == long.class || targetType == Long.class) {
            return toNumber(value, parameterName, toolName).longValue();
        }
        if (targetType == double.class || targetType == Double.class) {
            return toNumber(value, parameterName, toolName).doubleValue();
        }
        if (targetType == float.class || targetType == Float.class) {
            return toNumber(value, parameterName, toolName).floatValue();
        }
        if (targetType == short.class || targetType == Short.class) {
            return toNumber(value, parameterName, toolName).shortValue();
        }
        if (targetType == byte.class || targetType == Byte.class) {
            return toNumber(value, parameterName, toolName).byteValue();
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            if (value instanceof Boolean) {
                return value;
            }
            return Boolean.valueOf(String.valueOf(value));
        }
        if (targetType.isEnum()) {
            return enumValue(targetType, String.valueOf(value), parameterName, toolName);
        }
        if (targetType == Map.class || value instanceof Map) {
            return value;
        }
        if (targetType.isAssignableFrom(value.getClass())) {
            return value;
        }
        // Unknown POJO / collection: fall back to the raw JSON value (Map/List), else string.
        return value;
    }

    private static Number toNumber(Object value, String parameterName, String toolName) {
        if (value instanceof Number) {
            return (Number) value;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new ToolArgumentsException(
                    "Argument '" + parameterName + "' of tool '" + toolName + "' must be a number, but got: " + value, e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> enumClass, String raw, String parameterName, String toolName) {
        try {
            return Enum.valueOf((Class<? extends Enum>) enumClass, raw);
        } catch (IllegalArgumentException e) {
            throw new ToolArgumentsException(
                    "Argument '" + parameterName + "' of tool '" + toolName + "' must be one of "
                            + java.util.Arrays.toString(enumClass.getEnumConstants()) + ", but got: " + raw, e);
        }
    }
}