package cloud.changlu.agentforge.agent.tool.mcp;

import cloud.changlu.agentforge.agent.tool.mcp.domain.McpTool;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author changlu @date 2026/10/05
 */
public class McpToolFactoryTest {

    private static McpTool weatherTool() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        Map<String, Object> location = new LinkedHashMap<String, Object>();
        location.put("type", "string");
        properties.put("location", location);
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("location"));
        return new McpTool("get_weather", "查询天气", schema);
    }

    private static McpTool echoTool() {
        return new McpTool("echo", "回显", null);
    }

    @Test
    public void shouldMapToolsListToSpecifications() {
        FakeMcpClient client = new FakeMcpClient("demo", Arrays.asList(weatherTool(), echoTool()));

        Map<ToolSpecification, ToolExecutor> tools = McpToolFactory.buildTools(client, "demo");

        assertEquals(2, tools.size());
        List<String> names = new ArrayList<String>();
        for (ToolSpecification spec : tools.keySet()) {
            names.add(spec.name());
        }
        assertTrue(names.contains("demo__get_weather"));
        assertTrue(names.contains("demo__echo"));

        ToolSpecification weatherSpec = specNamed(tools, "demo__get_weather");
        assertNotNull(weatherSpec);
        assertTrue(weatherSpec.parameters().properties().containsKey("location"));
        assertEquals("demo", weatherSpec.metadata().get("mcp.server"));
        assertEquals("get_weather", weatherSpec.metadata().get("mcp.tool"));
    }

    @Test
    public void shouldNotPrefixWhenAliasBlank() {
        FakeMcpClient client = new FakeMcpClient(null, Collections.singletonList(echoTool()));

        Map<ToolSpecification, ToolExecutor> tools = McpToolFactory.buildTools(client, null);

        assertEquals("echo", tools.keySet().iterator().next().name());
    }

    @Test
    public void shouldUseClientAliasWhenNotProvided() {
        FakeMcpClient client = new FakeMcpClient("demo", Collections.singletonList(echoTool()));

        Map<ToolSpecification, ToolExecutor> tools = McpToolFactory.buildTools(client);

        assertEquals("demo__echo", tools.keySet().iterator().next().name());
        assertEquals("demo", tools.keySet().iterator().next().metadata().get("mcp.server"));
    }

    @Test
    public void shouldPreferExplicitAliasOverClientAlias() {
        FakeMcpClient client = new FakeMcpClient("client", Collections.singletonList(echoTool()));

        Map<ToolSpecification, ToolExecutor> tools = McpToolFactory.buildTools(client, "override");

        assertEquals("override__echo", tools.keySet().iterator().next().name());
    }

    @Test
    public void shouldRespectEnabledAndDisabledFilters() {
        FakeMcpClient client = new FakeMcpClient("demo", Arrays.asList(weatherTool(), echoTool()));

        Map<ToolSpecification, ToolExecutor> enabledOnly =
                McpToolFactory.buildTools(
                        client, "demo", new HashSet<String>(Arrays.asList("echo")), null);
        assertEquals(1, enabledOnly.size());
        assertEquals("demo__echo", enabledOnly.keySet().iterator().next().name());

        Map<ToolSpecification, ToolExecutor> disabled =
                McpToolFactory.buildTools(
                        client, "demo", null, new HashSet<String>(Arrays.asList("echo")));
        assertEquals(1, disabled.size());
        assertEquals("demo__get_weather", disabled.keySet().iterator().next().name());
    }

    @Test
    public void shouldProduceEmptySchemaForToolWithoutInputSchema() {
        FakeMcpClient client = new FakeMcpClient("demo", Collections.singletonList(echoTool()));

        Map<ToolSpecification, ToolExecutor> tools = McpToolFactory.buildTools(client, "demo");

        ToolSpecification spec = tools.keySet().iterator().next();
        assertEquals("object", spec.parameters().type());
    }

    private static ToolSpecification specNamed(
            Map<ToolSpecification, ToolExecutor> tools, String name) {
        for (ToolSpecification spec : tools.keySet()) {
            if (name.equals(spec.name())) {
                return spec;
            }
        }
        return null;
    }
}
