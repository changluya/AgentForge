package io.github.agentforge.studio.protocol.processor;

import io.github.agentforge.studio.protocol.StreamEventResponse;
import io.github.agentforge.studio.protocol.enums.StreamEventEnums;

/** 异常场景处理器。 */
public class AiErrorProcessor implements IStreamSceneProcessor {

    @Override
    public String getSceneCode() {
        return StreamEventEnums.EventTypeEnum.ERROR.getCode();
    }

    // 异常信息
    public static StreamEventResponse respError(String content) {
        return StreamEventResponse.of(StreamEventEnums.EventTypeEnum.ERROR.getCode())
                .type(StreamEventEnums.StepTypeEnum.TEXT.getCode())
                .content(content)
                .finish(true);
    }
}
