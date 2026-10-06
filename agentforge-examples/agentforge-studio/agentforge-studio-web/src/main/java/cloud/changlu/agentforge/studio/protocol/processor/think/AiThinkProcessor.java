package cloud.changlu.agentforge.studio.protocol.processor.think;

import cloud.changlu.agentforge.studio.protocol.StreamEventResponse;
import cloud.changlu.agentforge.studio.protocol.enums.StreamEventEnums;
import cloud.changlu.agentforge.studio.protocol.processor.IStreamSceneProcessor;

/** Think 场景处理器。 */
public class AiThinkProcessor implements IStreamSceneProcessor {

    @Override
    public String getSceneCode() {
        return StreamEventEnums.EventTypeEnum.THINK.getCode();
    }

    // 思考内容
    public static StreamEventResponse respThink(String content, Boolean isFinish) {
        return StreamEventResponse.of(StreamEventEnums.EventTypeEnum.THINK.getCode())
                .type(StreamEventEnums.StepTypeEnum.TEXT.getCode())
                .content(content)
                .finish(isFinish);
    }
}
