package com.changlu.agentforge.ai.agent.step;

/**
 * @description 单步执行状态：STOP表示整个Agent运行结束，FINISHED/RUNNING表示继续下一步
 * @author changlu
 * @date 2026/9/16
 */
public enum StepState {

    RUNNING, FINISHED, STOP, CANCEL;

}
