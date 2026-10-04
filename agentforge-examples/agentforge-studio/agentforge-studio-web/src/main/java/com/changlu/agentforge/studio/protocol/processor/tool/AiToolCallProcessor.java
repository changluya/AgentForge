package com.changlu.agentforge.studio.protocol.processor.tool;

import com.changlu.agentforge.studio.protocol.StreamEventResponse;
import com.changlu.agentforge.studio.protocol.ToolCallResult;
import com.changlu.agentforge.studio.protocol.enums.StreamEventEnums;
import com.changlu.agentforge.studio.protocol.processor.IStreamSceneProcessor;

/** 工具调用场景处理器。 */
public class AiToolCallProcessor implements IStreamSceneProcessor {

    @Override
    public String getSceneCode() {
        return StreamEventEnums.EventTypeEnum.TOOL_CALL.getCode();
    }

    // 工具调用卡片
    public static StreamEventResponse toolCallCard(
            ToolCallResult toolCallResult, Boolean isFinish) {
        return StreamEventResponse.of(StreamEventEnums.EventTypeEnum.TOOL_CALL.getCode())
                .type(StreamEventEnums.StepTypeEnum.TOOL_CARD.getCode())
                .content(toolCallResult)
                .finish(isFinish);
    }
}
