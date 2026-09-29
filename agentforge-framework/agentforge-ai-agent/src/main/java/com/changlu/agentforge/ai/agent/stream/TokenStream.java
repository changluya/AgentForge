package com.changlu.agentforge.ai.agent.stream;

import com.changlu.agentforge.llm.chat.response.ChatResponse;
import com.changlu.agentforge.llm.tool.execution.ToolExecution;

import java.util.function.Consumer;

/**
 * @description Agent流式执行的句柄：注册回调后调用start()启动think+act循环
 * @author changlu
 * @date 2026/9/16
 */
public interface TokenStream {

    /** 每次模型产生新的增量文本时回调 */
    TokenStream onPartialResponse(Consumer<String> partialResponseHandler);

    /** 每次模型产生新的增量思考/推理文本时回调 */
    TokenStream onPartialThinking(Consumer<PartialThinking> partialThinkingHandler);

    /** 模型返回中间响应（包含工具调用请求）时回调，工具执行完成后、进入下一轮思考前触发 */
    TokenStream onIntermediateResponse(Consumer<ChatResponse> intermediateResponseHandler);

    /** 每个工具执行完成后回调 */
    TokenStream onToolExecuted(Consumer<ToolExecution> toolExecuteHandler);

    /** 最终回答生成完成后回调 */
    TokenStream onCompleteResponse(Consumer<ChatResponse> completeResponseHandler);

    /** 流式过程中发生错误时回调 */
    TokenStream onError(Consumer<Throwable> errorHandler);

    /** 忽略流式过程中的错误 */
    TokenStream ignoreErrors();

    /** 开始执行 */
    void start();
}
