package cloud.changlu.agentforge.agent.tool.local;

import cloud.changlu.agentforge.agent.tool.local.support.LocalToolArgumentConverter;
import cloud.changlu.agentforge.agent.tool.local.support.LocalToolExecutionRequestUtil;
import cloud.changlu.agentforge.agent.tool.local.support.LoggingToolExecutor;
import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @description local模式的LocalToolFactory、LocalToolArgumentConverter与请求参数工具测试
 * @author changlu
 * @date 2026/10/04
 */
public class LocalToolFactoryTest {

    public static class FactoryTools {

        @Tool(name = "greet", value = "greet")
        public String greet(@P("name") String name) {
            return "hi " + name;
        }

        @Tool(name = "square", value = "square")
        public int square(@P("value") int value) {
            return value * value;
        }
    }

    @Test
    public void shouldBuildToolsFromAnnotatedObject() {
        Map<ToolSpecification, ToolExecutor> tools =
                LocalToolFactory.buildLocalTools(Collections.singletonList(new FactoryTools()));

        assertEquals(2, tools.size());
        ToolSpecification greet = find(tools, "greet");
        assertNotNull(greet.parameters());
        assertTrue(greet.parameters().properties().containsKey("name"));
        assertEquals(Collections.singletonList("name"), greet.parameters().required());
        assertTrue(executorFor(tools, "greet") instanceof LocalToolExecutor);
    }

    @Test
    public void shouldDecorateExecutorWithLogging() {
        Map<ToolSpecification, ToolExecutor> tools =
                LocalToolFactory.buildLocalToolsWithLogging(
                        Collections.singletonList(new FactoryTools()), true);

        assertTrue(executorFor(tools, "square") instanceof LoggingToolExecutor);
    }

    @Test
    public void shouldReturnEmptyMapForNullOrEmptyInput() {
        assertTrue(LocalToolFactory.buildLocalTools(null).isEmpty());
        assertTrue(LocalToolFactory.buildLocalTools(Collections.emptyList()).isEmpty());
    }

    @Test
    public void shouldConvertJsonStringIntoMap() {
        Map<?, ?> map = LocalToolArgumentConverter.convert("{\"a\":1}", Map.class);

        assertEquals(1, ((Number) map.get("a")).intValue());
    }

    @Test
    public void shouldConvertDoubleEncodedJsonString() {
        Map<?, ?> map = LocalToolArgumentConverter.convert("\"{\\\"a\\\":2}\"", Map.class);

        assertEquals(2, ((Number) map.get("a")).intValue());
    }

    @Test
    public void shouldConvertJsonStringIntoList() {
        List<?> list = LocalToolArgumentConverter.convert("[\"x\",\"y\"]", List.class);

        assertEquals(2, list.size());
        assertEquals("x", list.get(0));
    }

    @Test
    public void shouldTolerateTrailingCommaWhenParsingArguments() {
        Map<String, Object> arguments = LocalToolExecutionRequestUtil.argumentsAsMap("{\"a\":1,}");

        assertEquals(1, ((Number) arguments.get("a")).intValue());
    }

    private static ToolSpecification find(Map<ToolSpecification, ToolExecutor> tools, String name) {
        for (ToolSpecification spec : tools.keySet()) {
            if (spec.name().equals(name)) {
                return spec;
            }
        }
        throw new IllegalStateException("no spec: " + name);
    }

    private static ToolExecutor executorFor(
            Map<ToolSpecification, ToolExecutor> tools, String name) {
        return tools.get(find(tools, name));
    }
}
