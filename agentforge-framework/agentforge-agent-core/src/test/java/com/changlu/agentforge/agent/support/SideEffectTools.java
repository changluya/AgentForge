package com.changlu.agentforge.agent.support;

import com.changlu.agentforge.model.tool.P;
import com.changlu.agentforge.model.tool.Tool;

/**
 * @description 执行时触发一个回调的工具，用于在act阶段注入取消等副作用
 * @author changlu
 * @date 2026/9/16
 */
public class SideEffectTools {

    private Runnable onExecute;

    @Tool(name = "sideEffect", value = "Runs a test side effect and returns")
    public String sideEffect(@P("why the tool is called") String reason) {
        if (onExecute != null) {
            onExecute.run();
        }
        return "side effect done: " + reason;
    }

    public void onExecute(Runnable onExecute) {
        this.onExecute = onExecute;
    }
}
