package com.changlu.agentforge.llm.tool.spec;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.changlu.agentforge.llm.tool.Tool;
import com.changlu.agentforge.llm.tool.P;

/**
 * Builds {@link ToolSpecification}s from {@link Tool}-annotated methods.
 *
 * @author changlu
 * @since 2026-09-13
 */
public final class ToolSpecifications {

    private ToolSpecifications() {
    }

    /**
     * Returns a {@link ToolSpecification} for a {@link Tool}-annotated method.
     *
     * @param method the method
     * @return the tool specification
     */
    public static ToolSpecification toolSpecificationFrom(Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        if (tool == null) {
            throw new IllegalArgumentException("Method '" + method + "' is not annotated with @Tool");
        }
        return ToolSpecification.builder()
                .name(toolNameFrom(method))
                .description(joinAndNullIfBlank(tool.value()))
                .parameters(parametersFrom(method))
                .build();
    }

    /**
     * Returns the name under which the given method is exposed to the LLM: the {@link Tool#name()}
     * attribute when set, otherwise the Java method name.
     *
     * @param method the method
     * @return the tool name
     */
    public static String toolNameFrom(Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        String explicitName = tool == null ? "" : tool.name();
        return isNullOrBlank(explicitName) ? method.getName() : explicitName;
    }

    /**
     * Scans the object (and its class hierarchy) for {@link Tool}-annotated methods and returns their
     * {@link ToolSpecification}s.
     *
     * @param objectWithTools the object
     * @return tool specifications
     */
    public static List<ToolSpecification> toolSpecificationsFrom(Object objectWithTools) {
        return toolSpecificationsFrom(objectWithTools.getClass());
    }

    public static List<ToolSpecification> toolSpecificationsFrom(Class<?> classWithTools) {
        List<ToolSpecification> specifications = new ArrayList<ToolSpecification>();
        for (Method method : allConcreteMethods(classWithTools)) {
            if (method.isAnnotationPresent(Tool.class)) {
                specifications.add(toolSpecificationFrom(method));
            }
        }
        return specifications;
    }

    /**
     * Validates that all tool names are unique; throws {@link IllegalArgumentException} otherwise.
     *
     * @param toolSpecifications the tool specifications
     */
    public static void validateSpecifications(List<ToolSpecification> toolSpecifications) {
        Set<String> names = new LinkedHashSet<String>();
        for (ToolSpecification toolSpecification : toolSpecifications) {
            if (!names.add(toolSpecification.name())) {
                throw new IllegalArgumentException(
                        "Tool names must be unique. The tool '" + toolSpecification.name() + "' appears several times");
            }
        }
    }

    private static ToolParameters parametersFrom(Method method) {
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            return null;
        }
        ToolParameters.Builder builder = ToolParameters.builder();
        for (Parameter parameter : parameters) {
            P pAnnotation = parameter.getAnnotation(P.class);
            String parameterName = pAnnotation != null && !isNullOrBlank(pAnnotation.name())
                    ? pAnnotation.name()
                    : parameter.getName();
            String description = null;
            if (pAnnotation != null) {
                description = !isNullOrBlank(pAnnotation.description())
                        ? pAnnotation.description()
                        : (!isNullOrBlank(pAnnotation.value()) ? pAnnotation.value() : null);
            }
            String jsonType = jsonType(parameter.getType());
            builder.addProperty(parameterName, jsonType, description, true);
        }
        return builder.build();
    }

    private static String jsonType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class) return "integer";
        if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type.isEnum()) return "string";
        if (type.isArray() || List.class.isAssignableFrom(type) || Set.class.isAssignableFrom(type)) return "array";
        if (Map.class.isAssignableFrom(type)) return "object";
        return "object";
    }

    private static String joinAndNullIfBlank(String[] values) {
        if (values == null || values.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(value);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static boolean isNullOrBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static List<Method> allConcreteMethods(Class<?> clazz) {
        List<Method> methods = new ArrayList<Method>();
        List<Method> result = new ArrayList<Method>();
        collectConcreteMethods(clazz, methods);
        Map<String, Method> bySignature = new LinkedHashMap<String, Method>();
        for (Method method : methods) {
            if (method.isAnnotationPresent(Tool.class)) {
                bySignature.put(methodSignature(method), method);
            }
        }
        result.addAll(bySignature.values());
        return result;
    }

    private static void collectConcreteMethods(Class<?> clazz, List<Method> methods) {
        if (clazz == null || clazz == Object.class) {
            return;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                methods.add(method);
            }
        }
        collectConcreteMethods(clazz.getSuperclass(), methods);
    }

    private static String methodSignature(Method method) {
        StringBuilder sb = new StringBuilder(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(types[i].getName());
        }
        return sb.append(')').toString();
    }
}