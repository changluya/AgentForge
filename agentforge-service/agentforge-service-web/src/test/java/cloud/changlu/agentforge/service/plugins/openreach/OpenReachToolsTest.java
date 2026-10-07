package cloud.changlu.agentforge.service.plugins.openreach;

import cloud.changlu.agentforge.agent.tool.local.LocalToolFactory;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/**
 * @description OpenReach 工具类 local 模式扫描单测
 * @author changlu
 * @date 2026/10/07
 */
public class OpenReachToolsTest {

    @Test
    public void localFactoryScansAllWebTools() {
        OpenReachTools tools =
                new OpenReachTools(new OpenReachClient("http://openreach.local:8089"));

        Map<ToolSpecification, ToolExecutor> built =
                LocalToolFactory.buildLocalTools(Collections.<Object>singletonList(tools));

        Set<String> names = new HashSet<String>();
        for (ToolSpecification specification : built.keySet()) {
            names.add(specification.name());
        }
        assertEquals(
                new HashSet<String>(
                        Arrays.asList("webSearch", "webImageSearch", "webRead", "webCurl")),
                names);
        assertEquals(4, built.size());
    }
}
