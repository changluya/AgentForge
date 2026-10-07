package cloud.changlu.agentforge.service.protocol.processor.tool;

import cloud.changlu.agentforge.service.protocol.StreamEventResponse;
import cloud.changlu.agentforge.service.protocol.ToolCallResult;
import cloud.changlu.agentforge.service.protocol.enums.StreamEventEnums;
import cloud.changlu.agentforge.service.protocol.processor.IStreamSceneProcessor;

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
