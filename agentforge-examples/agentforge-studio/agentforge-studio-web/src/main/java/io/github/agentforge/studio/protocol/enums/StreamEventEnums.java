package io.github.agentforge.studio.protocol.enums;

/** Studio 流式事件枚举。 */
public final class StreamEventEnums {

    /** 事件类型枚举。 */
    public enum EventTypeEnum {
        SESSION("session", "会话事件"),
        THINK("think", "AI思考"),
        RESP("resp", "AI回答事件"),
        TOOL_CALL("tool_call", "工具调用事件"),
        RESP_END("resp_end", "AI回答结束"),
        ERROR("error", "异常事件");

        private final String code;
        private final String msg;

        EventTypeEnum(String code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        public String getCode() {
            return code;
        }

        public String getMsg() {
            return msg;
        }
    }

    /** 会话事件里的类型枚举。 */
    public enum SessionTypeEnum {
        CREATED("created", "会话创建");

        private final String code;
        private final String msg;

        SessionTypeEnum(String code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        public String getCode() {
            return code;
        }

        public String getMsg() {
            return msg;
        }
    }

    /** 事件里的类型枚举。 */
    public enum StepTypeEnum {
        TEXT("text", "文本内容"),
        TOOL_CARD("tool_card", "工具调用卡片");

        private final String code;
        private final String msg;

        StepTypeEnum(String code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        public String getCode() {
            return code;
        }

        public String getMsg() {
            return msg;
        }
    }

    private StreamEventEnums() {}
}
