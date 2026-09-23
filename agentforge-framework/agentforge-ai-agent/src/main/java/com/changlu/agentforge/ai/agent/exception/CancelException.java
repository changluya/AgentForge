package com.changlu.agentforge.ai.agent.exception;

/**
 * @description 用户取消任务时抛出的异常，用于打断think/act循环
 * @author changlu
 * @date 2026/9/16
 */
public class CancelException extends RuntimeException {

    public CancelException(String message) {
        super(message);
    }

    public CancelException(String message, Throwable cause) {
        super(message, cause);
    }
}
