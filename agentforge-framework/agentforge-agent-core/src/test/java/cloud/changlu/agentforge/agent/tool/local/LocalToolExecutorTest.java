package cloud.changlu.agentforge.agent.tool.local;

import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;
import cloud.changlu.agentforge.model.tool.error.ToolArgumentsException;
import cloud.changlu.agentforge.model.tool.error.ToolExecutionException;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * @description local模式的LocalToolExecutor单元测试：覆盖参数绑定、类型转换、默认值与异常策略
 * @author changlu
 * @date 2026/10/04
 */
public class LocalToolExecutorTest {

    public enum Mode {
        FAST,
        SLOW
    }

    public static class SampleTools {

        @Tool(name = "echo", value = "echo a string")
        public String echo(@P("text") String text) {
            return "echo:" + text;
        }

        @Tool(name = "add", value = "add two integers")
        public int add(@P("a") int a, @P("b") int b) {
            return a + b;
        }

        @Tool(name = "pick", value = "pick a mode")
        public String pick(@P("mode") Mode mode) {
            return mode.name();
        }

        @Tool(name = "sum", value = "sum a numeric list")
        public long sum(@P("values") List<Number> values) {
            long total = 0L;
            for (Number value : values) {
                total += value.longValue();
            }
            return total;
        }

        @Tool(name = "merge", value = "merge a map")
        public String merge(@P("data") Map<String, Object> data) {
            return "size=" + data.size();
        }

        @Tool(name = "noop", value = "do nothing")
        public void noop(@P("msg") String msg) {
            // intentionally empty
        }

        @Tool(name = "count", value = "return a constant")
        public int count() {
            return 42;
        }

        @Tool(name = "boom", value = "always throws")
        public String boom(@P("msg") String msg) {
            throw new IllegalStateException("boom:" + msg);
        }
    }

    @Test
    public void shouldBindStringArgumentUsingPAnnotation() {
        ToolExecutionRequest request = ToolExecutionRequest.from("1", "echo", "{\"text\":\"hi\"}");

        String result = execute(request);

        assertEquals("echo:hi", result);
    }

    @Test
    public void shouldCoerceNumericArguments() {
        ToolExecutionRequest request = ToolExecutionRequest.from("1", "add", "{\"a\":2,\"b\":3}");

        assertEquals("5", execute(request));
    }

    @Test
    public void shouldUsePrimitiveDefaultWhenArgumentMissing() {
        ToolExecutionRequest request = ToolExecutionRequest.from("1", "add", "{}");

        assertEquals("0", execute(request));
    }

    @Test
    public void shouldCoerceEnumNameIgnoringCase() {
        assertEquals(
                "FAST", execute(ToolExecutionRequest.from("1", "pick", "{\"mode\":\"fast\"}")));
        assertEquals(
                "SLOW", execute(ToolExecutionRequest.from("1", "pick", "{\"mode\":\"SLOW\"}")));
    }

    @Test
    public void shouldConvertJsonStringIntoListCollection() {
        ToolExecutionRequest request =
                ToolExecutionRequest.from("1", "sum", "{\"values\":\"[1,2,3]\"}");

        assertEquals("6", execute(request));
    }

    @Test
    public void shouldPassThroughNativeList() {
        ToolExecutionRequest request =
                ToolExecutionRequest.from("1", "sum", "{\"values\":[4,5,6]}");

        assertEquals("15", execute(request));
    }

    @Test
    public void shouldConvertJsonStringIntoMap() {
        ToolExecutionRequest request =
                ToolExecutionRequest.from("1", "merge", "{\"data\":\"{\\\"k\\\":\\\"v\\\"}\"}");

        assertEquals("size=1", execute(request));
    }

    @Test
    public void shouldReturnSuccessForVoidMethod() {
        ToolExecutionRequest request =
                ToolExecutionRequest.from("1", "noop", "{\"msg\":\"hello\"}");

        assertEquals("Success", execute(request));
    }

    @Test
    public void shouldSerializeNonStringReturnValueAsJson() {
        ToolExecutionRequest request = ToolExecutionRequest.from("1", "count", "{}");

        assertEquals("42", execute(request));
    }

    @Test
    public void shouldReturnThrownMessageByDefault() {
        ToolExecutionRequest request = ToolExecutionRequest.from("1", "boom", "{\"msg\":\"x\"}");

        assertEquals("boom:x", execute(request));
    }

    @Test
    public void shouldThrowWrappedExecutionExceptionWhenPropagating() {
        SampleTools tools = new SampleTools();
        Method method = method("boom");
        LocalToolExecutor executor =
                LocalToolExecutor.builder()
                        .object(tools)
                        .originalMethod(method)
                        .methodToInvoke(method)
                        .propagateToolExecutionExceptions(true)
                        .build();

        assertThrows(
                ToolExecutionException.class,
                () ->
                        executor.execute(
                                ToolExecutionRequest.from("1", "boom", "{\"msg\":\"x\"}"), null));
    }

    @Test
    public void shouldWrapArgumentsExceptionWhenConfigured() {
        SampleTools tools = new SampleTools();
        Method method = method("echo");
        LocalToolExecutor executor =
                LocalToolExecutor.builder()
                        .object(tools)
                        .originalMethod(method)
                        .methodToInvoke(method)
                        .wrapToolArgumentsExceptions(true)
                        .build();

        assertThrows(
                ToolArgumentsException.class,
                () -> executor.execute(ToolExecutionRequest.from("1", "echo", "not-a-json"), null));
    }

    private static String execute(ToolExecutionRequest request) {
        SampleTools tools = new SampleTools();
        LocalToolExecutor executor = new LocalToolExecutor(tools, method(request.name()));
        return executor.execute(request, null);
    }

    private static Method method(String name) {
        for (Method method : SampleTools.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalStateException("no method: " + name);
    }
}
