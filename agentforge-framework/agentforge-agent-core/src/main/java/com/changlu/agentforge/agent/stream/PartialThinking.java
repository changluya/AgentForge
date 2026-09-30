package com.changlu.agentforge.agent.stream;

import java.util.Objects;

/**
 * @description 模型流式输出的思考/推理内容片段（每次生成一个思考 token 时回调一帧）
 * @author changlu
 * @date 2026/9/16
 */
public final class PartialThinking {

    private final String text;

    public PartialThinking(String text) {
        this.text = text;
    }

    /** 返回本次增量思考文本片段 */
    public String text() {
        return text;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        PartialThinking other = (PartialThinking) obj;
        return Objects.equals(text, other.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text);
    }

    @Override
    public String toString() {
        return "PartialThinking{text='" + text + "'}";
    }
}
