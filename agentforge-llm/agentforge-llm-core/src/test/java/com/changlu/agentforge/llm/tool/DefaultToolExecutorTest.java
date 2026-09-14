package com.changlu.agentforge.llm.tool;

import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import com.changlu.agentforge.llm.tool.execution.DefaultToolExecutor;
import com.changlu.agentforge.llm.tool.spec.ToolSpecification;
import com.changlu.agentforge.llm.tool.spec.ToolParameters;
import com.changlu.agentforge.llm.tool.execution.ToolExecutionResult;
import com.changlu.agentforge.llm.tool.spec.ToolSpecifications;
import com.changlu.agentforge.llm.tool.error.ToolArgumentsException;

/**
 * Tests for {@link DefaultToolExecutor}, {@link ToolSpecifications} and {@link ToolExecutionResult}.
 *
 * @author changlu
 * @since 2026-09-13
 */
public class DefaultToolExecutorTest {

    public static class WeatherTools {

        @Tool(value = "Returns the weather for the given city")
        public String getWeather(String city) {
            return "Weather in " + city + ": sunny";
        }

        @Tool(value = "Adds two integers")
        public int add(int a, int b) {
            return a + b;
        }

        @Tool("Logs a message and returns nothing")
        public void log(String message) {
            System.out.println(message);
        }

        @Tool("Greets a user")
        public String greet(@P("person name") String name) {
            return "Hello, " + name;
        }
    }

    @Test
    public void shouldBindStringArgumentAndInvoke() {
        ToolExecutionRequest request = ToolExecutionRequest.from(
                "call_1", "getWeather", "{\"city\":\"Hangzhou\"}");
        DefaultToolExecutor executor = new DefaultToolExecutor(new WeatherTools(), method("getWeather"));

        ToolExecutionResult result = executor.executeWithResult(request, null);

        assertEquals("Weather in Hangzhou: sunny", result.text());
        assertEquals("Weather in Hangzhou: sunny", result.text());
        assertEquals("Weather in Hangzhou: sunny", result.text());
    }

    @Test
    public void shouldCoerceNumericArguments() {
        ToolExecutionRequest request = ToolExecutionRequest.from("call_2", "add", "{\"a\":2,\"b\":3}");
        DefaultToolExecutor executor = new DefaultToolExecutor(new WeatherTools(), method("add"));

        ToolExecutionResult result = executor.executeWithResult(request, null);

        assertEquals("5", result.text());
        assertEquals(5, result.result());
    }

    @Test
    public void shouldReturnSuccessForVoidMethod() {
        ToolExecutionRequest request = ToolExecutionRequest.from("call_3", "log", "{\"message\":\"hi\"}");
        DefaultToolExecutor executor = new DefaultToolExecutor(new WeatherTools(), method("log"));

        ToolExecutionResult result = executor.executeWithResult(request, null);

        assertEquals("Success", result.text());
    }

    @Test
    public void shouldUsePAnnotationForParameterName() {
        ToolExecutionRequest request = ToolExecutionRequest.from("call_4", "greet", "{\"name\":\"Alice\"}");
        DefaultToolExecutor executor = new DefaultToolExecutor(new WeatherTools(), method("greet"));

        assertEquals("Hello, Alice", executor.executeWithResult(request, null).text());
    }

    @Test
    public void shouldThrowToolArgumentsExceptionForMissingArgument() {
        ToolExecutionRequest request = ToolExecutionRequest.from("call_5", "getWeather", "{}");
        DefaultToolExecutor executor = new DefaultToolExecutor(new WeatherTools(), method("getWeather"));

        assertThrows(ToolArgumentsException.class,
                () -> executor.executeWithResult(request, null));
    }

    @Test
    public void shouldBuildToolSpecificationFromMethod() {
        WeatherTools tools = new WeatherTools();
        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(tools);

        assertEquals(4, specs.size());
        ToolSpecification weather = find(specs, "getWeather");
        assertEquals("Returns the weather for the given city", weather.description());
        assertNotNull(weather.parameters());
        assertTrue(weather.parameters().properties().containsKey("city"));
        assertEquals(Arrays.asList("city"), weather.parameters().required());
    }

    @Test
    public void shouldBuildToolSpecificationFromObjectAndValidateUniqueNames() {
        ToolSpecifications.validateSpecifications(ToolSpecifications.toolSpecificationsFrom(new WeatherTools()));
        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(WeatherTools.class);
        assertEquals(4, specs.size());
    }

    @Test
    public void shouldFailOnDuplicateToolNames() {
        ToolSpecification a = ToolSpecification.builder().name("dup").build();
        ToolSpecification b = ToolSpecification.builder().name("dup").build();
        assertThrows(IllegalArgumentException.class,
                () -> ToolSpecifications.validateSpecifications(Arrays.asList(a, b)));
    }

    private static java.lang.reflect.Method method(String name) {
        for (java.lang.reflect.Method method : WeatherTools.class.getMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalStateException("no method " + name);
    }

    private static ToolSpecification find(List<ToolSpecification> specs, String name) {
        for (ToolSpecification spec : specs) {
            if (spec.name().equals(name)) {
                return spec;
            }
        }
        throw new IllegalStateException("no spec " + name);
    }
}