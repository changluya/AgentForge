package cloud.changlu.agentforge.agent.tool.local;

import cloud.changlu.agentforge.agent.tool.local.support.LocalToolArgumentConverter;
import cloud.changlu.agentforge.agent.tool.local.support.LocalToolExecutionRequestUtil;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.internal.json.Json;
import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.error.ToolArgumentsException;
import cloud.changlu.agentforge.model.tool.error.ToolExecutionException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * @description 本地方法工具执行器（AgentForge版）：负责把模型的工具参数绑定到方法入参并反射调用。 相较于{@code
 *     DefaultToolExecutor}，本执行器支持更丰富的参数类型转换（枚举、数值边界、BigDecimal、
 *     BigInteger、UUID、Collection/Map的JSON字符串容错等）。
 * @author changlu
 * @date 2026/10/04
 */
public class LocalToolExecutor implements ToolExecutor {

    private final Object object;
    private final Method originalMethod;
    private final Method methodToInvoke;
    private final boolean wrapToolArgumentsExceptions;
    private final boolean propagateToolExecutionExceptions;

    public LocalToolExecutor(LocalToolExecutor.Builder builder) {
        this.object = Objects.requireNonNull(builder.object, "object");
        this.originalMethod = Objects.requireNonNull(builder.originalMethod, "originalMethod");
        this.methodToInvoke = Objects.requireNonNull(builder.methodToInvoke, "methodToInvoke");
        this.wrapToolArgumentsExceptions =
                builder.wrapToolArgumentsExceptions != null && builder.wrapToolArgumentsExceptions;
        this.propagateToolExecutionExceptions =
                builder.propagateToolExecutionExceptions != null
                        && builder.propagateToolExecutionExceptions;
    }

    public LocalToolExecutor(Object object, Method method) {
        this.object = Objects.requireNonNull(object, "object");
        this.originalMethod = Objects.requireNonNull(method, "method");
        this.methodToInvoke = this.originalMethod;
        this.wrapToolArgumentsExceptions = false;
        this.propagateToolExecutionExceptions = false;
    }

    public LocalToolExecutor(Object object, ToolExecutionRequest toolExecutionRequest) {
        this.object = Objects.requireNonNull(object, "object");
        Objects.requireNonNull(toolExecutionRequest, "toolExecutionRequest");
        this.originalMethod = findMethod(object, toolExecutionRequest);
        this.methodToInvoke = this.originalMethod;
        this.wrapToolArgumentsExceptions = false;
        this.propagateToolExecutionExceptions = false;
    }

    /**
     * When methods annotated with @Tool are wrapped into proxies (AOP), the parameters of the
     * proxied method do not retain their original names. Therefore, access to the original method
     * is required to retrieve those names.
     *
     * @param object the object on which the method should be invoked
     * @param originalMethod the original method, used to retrieve parameter names and prepare
     *     arguments
     * @param methodToInvoke the method that should actually be invoked
     */
    public LocalToolExecutor(Object object, Method originalMethod, Method methodToInvoke) {
        this.object = Objects.requireNonNull(object, "object");
        this.originalMethod = Objects.requireNonNull(originalMethod, "originalMethod");
        this.methodToInvoke = Objects.requireNonNull(methodToInvoke, "methodToInvoke");
        this.wrapToolArgumentsExceptions = false;
        this.propagateToolExecutionExceptions = false;
    }

    private Method findMethod(Object object, ToolExecutionRequest toolExecutionRequest) {
        String requestedMethodName = toolExecutionRequest.name();

        for (Method method : object.getClass().getDeclaredMethods()) {
            if (method.getName().equals(requestedMethodName)) {
                return method;
            }
        }

        throw new IllegalArgumentException(
                String.format(
                        "Method '%s' is not found in object '%s'",
                        requestedMethodName, object.getClass().getName()));
    }

    @Override
    public String execute(ToolExecutionRequest toolExecutionRequest, Object memoryId) {
        Object[] arguments = prepareArguments(toolExecutionRequest, memoryId);

        try {
            return execute(arguments);
        } catch (IllegalAccessException e) {
            try {
                methodToInvoke.setAccessible(true);
                return execute(arguments);
            } catch (IllegalAccessException e2) {
                throw new RuntimeException(e2);
            } catch (InvocationTargetException e2) {
                if (propagateToolExecutionExceptions) {
                    throw new ToolExecutionException(e2.getCause());
                } else {
                    return e2.getCause().getMessage();
                }
            }
        } catch (InvocationTargetException e) {
            if (propagateToolExecutionExceptions) {
                throw new ToolExecutionException(e.getCause());
            } else {
                return e.getCause().getMessage();
            }
        }
    }

    private Object[] prepareArguments(ToolExecutionRequest toolExecutionRequest, Object memoryId) {
        try {
            Map<String, Object> argumentsMap =
                    LocalToolExecutionRequestUtil.argumentsAsMap(toolExecutionRequest.arguments());
            return prepareArguments(originalMethod, argumentsMap, memoryId);
        } catch (RuntimeException e) {
            if (wrapToolArgumentsExceptions) {
                throw new ToolArgumentsException(e);
            }
            throw e;
        } catch (Exception e) {
            throw new ToolArgumentsException(e);
        }
    }

    private String execute(Object[] arguments)
            throws IllegalAccessException, InvocationTargetException {
        Object result = methodToInvoke.invoke(object, arguments);
        Class<?> returnType = methodToInvoke.getReturnType();
        if (returnType == void.class) {
            return "Success";
        } else if (returnType == String.class) {
            return (String) result;
        } else {
            return Json.stringify(result);
        }
    }

    static Object[] prepareArguments(
            Method method, Map<String, Object> argumentsMap, Object memoryId) {
        Parameter[] parameters = method.getParameters();
        Object[] arguments = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {

            Parameter parameter = parameters[i];

            String parameterName = parameterName(parameter);
            Object argument = argumentsMap.get(parameterName);

            Class<?> parameterClass = parameter.getType();
            Type parameterType = parameter.getParameterizedType();

            // 如果参数为 null，尝试使用默认值
            if (argument == null) {
                // 对于基本类型，使用默认值
                arguments[i] = getDefaultValue(parameterClass);
            } else {
                arguments[i] =
                        coerceArgument(argument, parameterName, parameterClass, parameterType);
            }
        }

        return arguments;
    }

    private static String parameterName(Parameter parameter) {
        P pAnnotation = parameter.getAnnotation(P.class);
        if (pAnnotation != null
                && pAnnotation.name() != null
                && !pAnnotation.name().trim().isEmpty()) {
            return pAnnotation.name();
        }
        return parameter.getName();
    }

    /**
     * 获取指定类型的默认值
     *
     * @param type 参数类型
     * @return 默认值，对于非基本类型返回 null
     */
    private static Object getDefaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        } else if (type == char.class) {
            return '\0';
        } else if (type == byte.class) {
            return (byte) 0;
        } else if (type == short.class) {
            return (short) 0;
        } else if (type == int.class) {
            return 0;
        } else if (type == long.class) {
            return 0L;
        } else if (type == float.class) {
            return 0.0f;
        } else if (type == double.class) {
            return 0.0d;
        } else {
            // 对于对象类型，返回 null
            return null;
        }
    }

    static Object coerceArgument(
            Object argument, String parameterName, Class<?> parameterClass, Type parameterType) {
        if (parameterClass == String.class) {
            return argument.toString();
        }

        if (parameterClass.isEnum()) {
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Class<Enum> enumClass = (Class<Enum>) parameterClass;
                try {
                    return Enum.valueOf(enumClass, Objects.requireNonNull(argument).toString());
                } catch (IllegalArgumentException e) {
                    // try to convert to uppercase as a last resort
                    return Enum.valueOf(
                            enumClass, Objects.requireNonNull(argument).toString().toUpperCase());
                }
            } catch (Exception | Error e) {
                throw new IllegalArgumentException(
                        String.format(
                                "Argument \"%s\" is not a valid enum value for %s: <%s>",
                                parameterName, parameterClass.getName(), argument),
                        e);
            }
        }

        if (parameterClass == Boolean.class || parameterClass == boolean.class) {
            if (argument instanceof Boolean) {
                return argument;
            }
            throw new IllegalArgumentException(
                    String.format(
                            "Argument \"%s\" is not convertable to %s, got %s: <%s>",
                            parameterName,
                            parameterClass.getName(),
                            argument.getClass().getName(),
                            argument));
        }

        if (parameterClass == Double.class || parameterClass == double.class) {
            return getDoubleValue(argument, parameterName, parameterClass);
        }

        if (parameterClass == Float.class || parameterClass == float.class) {
            double doubleValue = getDoubleValue(argument, parameterName, parameterClass);
            checkBounds(
                    doubleValue, parameterName, parameterClass, -Float.MIN_VALUE, Float.MAX_VALUE);
            return (float) doubleValue;
        }

        if (parameterClass == BigDecimal.class) {
            return BigDecimal.valueOf(getDoubleValue(argument, parameterName, parameterClass));
        }

        if (parameterClass == Integer.class || parameterClass == int.class) {
            return (int)
                    getBoundedLongValue(
                            argument,
                            parameterName,
                            parameterClass,
                            Integer.MIN_VALUE,
                            Integer.MAX_VALUE);
        }

        if (parameterClass == Long.class || parameterClass == long.class) {
            return getBoundedLongValue(
                    argument, parameterName, parameterClass, Long.MIN_VALUE, Long.MAX_VALUE);
        }

        if (parameterClass == Short.class || parameterClass == short.class) {
            return (short)
                    getBoundedLongValue(
                            argument,
                            parameterName,
                            parameterClass,
                            Short.MIN_VALUE,
                            Short.MAX_VALUE);
        }

        if (parameterClass == Byte.class || parameterClass == byte.class) {
            return (byte)
                    getBoundedLongValue(
                            argument,
                            parameterName,
                            parameterClass,
                            Byte.MIN_VALUE,
                            Byte.MAX_VALUE);
        }

        if (parameterClass == BigInteger.class) {
            return BigDecimal.valueOf(
                            getNonFractionalDoubleValue(argument, parameterName, parameterClass))
                    .toBigInteger();
        }

        if (Collection.class.isAssignableFrom(parameterClass)
                || Map.class.isAssignableFrom(parameterClass)) {
            return LocalToolArgumentConverter.convert(argument, parameterType);
        }

        if (parameterClass == UUID.class) {
            return UUID.fromString(argument.toString());
        }

        if (argument instanceof String) {
            try {
                return Json.parse(argument.toString());
            } catch (Exception e) {
                return argument;
            }
        }
        return argument;
    }

    private static double getDoubleValue(
            Object argument, String parameterName, Class<?> parameterType) {
        if (argument instanceof String) {
            try {
                return Double.parseDouble(argument.toString());
            } catch (Exception e) {
                // nothing, will be handled with bellow code
            }
        }
        if (!(argument instanceof Number)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Argument \"%s\" is not convertable to %s, got %s: <%s>",
                            parameterName,
                            parameterType.getName(),
                            argument.getClass().getName(),
                            argument));
        }
        return ((Number) argument).doubleValue();
    }

    private static double getNonFractionalDoubleValue(
            Object argument, String parameterName, Class<?> parameterType) {
        double doubleValue = getDoubleValue(argument, parameterName, parameterType);
        if (!hasNoFractionalPart(doubleValue)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Argument \"%s\" has non-integer value for %s: <%s>",
                            parameterName, parameterType.getName(), argument));
        }
        return doubleValue;
    }

    private static void checkBounds(
            double doubleValue,
            String parameterName,
            Class<?> parameterType,
            double minValue,
            double maxValue) {
        if (doubleValue < minValue || doubleValue > maxValue) {
            throw new IllegalArgumentException(
                    String.format(
                            "Argument \"%s\" is out of range for %s: <%s>",
                            parameterName, parameterType.getName(), doubleValue));
        }
    }

    public static long getBoundedLongValue(
            Object argument,
            String parameterName,
            Class<?> parameterType,
            long minValue,
            long maxValue) {
        double doubleValue = getNonFractionalDoubleValue(argument, parameterName, parameterType);
        checkBounds(doubleValue, parameterName, parameterType, minValue, maxValue);
        return (long) doubleValue;
    }

    static boolean hasNoFractionalPart(Double doubleValue) {
        return doubleValue.equals(Math.floor(doubleValue));
    }

    public static LocalToolExecutor.Builder builder() {
        return new LocalToolExecutor.Builder();
    }

    public static class Builder {

        private Object object;
        private Method originalMethod;
        private Method methodToInvoke;
        private Boolean wrapToolArgumentsExceptions;
        private Boolean propagateToolExecutionExceptions;

        public LocalToolExecutor.Builder object(Object object) {
            this.object = object;
            return this;
        }

        public LocalToolExecutor.Builder originalMethod(Method originalMethod) {
            this.originalMethod = originalMethod;
            return this;
        }

        public LocalToolExecutor.Builder methodToInvoke(Method methodToInvoke) {
            this.methodToInvoke = methodToInvoke;
            return this;
        }

        /**
         * If set to {@code true}, exceptions that occur during tool argument parsing or preparation
         * will be wrapped in a {@link ToolArgumentsException}.
         *
         * <p>The default value is {@code false}.
         */
        public LocalToolExecutor.Builder wrapToolArgumentsExceptions(
                Boolean wrapToolArgumentsExceptions) {
            this.wrapToolArgumentsExceptions = wrapToolArgumentsExceptions;
            return this;
        }

        /**
         * If set to {@code true}, exceptions that occur during tool execution will be thrown
         * instead of being returned as an exception message string. These exceptions will be
         * wrapped in a {@link ToolExecutionException}.
         *
         * <p>The default value is {@code false}.
         */
        public LocalToolExecutor.Builder propagateToolExecutionExceptions(
                Boolean propagateToolExecutionExceptions) {
            this.propagateToolExecutionExceptions = propagateToolExecutionExceptions;
            return this;
        }

        public LocalToolExecutor build() {
            return new LocalToolExecutor(this);
        }
    }
}
