package io.github.agentforge.studio.protocol.processor;

import io.github.agentforge.studio.protocol.StreamEventResponse;
import io.github.agentforge.studio.protocol.enums.StreamEventEnums;

/** AI 响应场景处理器。 */
public class AiResponseProcessor implements IStreamSceneProcessor {

    @Override
    public String getSceneCode() {
        return StreamEventEnums.EventTypeEnum.RESP.getCode();
    }

    // AI文本响应
    public static StreamEventResponse respText(String content, Boolean isFinish) {
        return StreamEventResponse.of(StreamEventEnums.EventTypeEnum.RESP.getCode())
                .type(StreamEventEnums.StepTypeEnum.TEXT.getCode())
                .content(content)
                .finish(isFinish);
    }

    // AI响应结束
    public static StreamEventResponse respEnd(Boolean isFinish) {
        return StreamEventResponse.of(StreamEventEnums.EventTypeEnum.RESP_END.getCode())
                .finish(isFinish);
    }
}
