package cloud.changlu.agentforge.agent.tool.local;

import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @description local模式端到端闭环测试：对齐文档 4.1/4.2 的 queryCustomer 示例，从 @Tool 扫描 → ToolService 注册 → 模型
 *     function call → 命中执行器 → 参数绑定与类型转换 → 反射调用 → 回灌返回值
 * @author changlu
 * @date 2026/10/05
 */
public class LocalToolLoopTest {

    public static class CrmTools {

        @Tool(name = "queryCustomer", value = "按客户名查询客户信息")
        public String queryCustomer(
                @P(name = "name", description = "客户名称") String name,
                @P(name = "limit", description = "返回条数") int limit) {
            return "customer:" + name + ",limit:" + limit;
        }
    }

    @Test
    public void shouldCloseTheCrmLoopThroughToolService() {
        // 步骤①：扫描 @Tool → spec + executor
        Map<ToolSpecification, ToolExecutor> tools =
                LocalToolFactory.buildLocalTools(Collections.singletonList(new CrmTools()));

        ToolService toolService = new ToolService();
        toolService.tools(tools);
        assertTrue(toolService.toolExecutors().containsKey("queryCustomer"));

        // 步骤③~⑥：function call → 参数绑定/类型转换 → 反射调用 → 返回值回灌
        String result =
                toolService
                        .toolExecutors()
                        .get("queryCustomer")
                        .execute(
                                ToolExecutionRequest.from(
                                        "call_1", "queryCustomer", "{\"name\":\"长路\",\"limit\":5}"),
                                null);

        assertEquals("customer:长路,limit:5", result);
    }
}
