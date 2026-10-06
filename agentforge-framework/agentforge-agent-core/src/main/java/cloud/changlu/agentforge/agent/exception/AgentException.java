package cloud.changlu.agentforge.agent.exception;

/**
 * @description Agent运行期异常
 * @author changlu
 * @date 2026/9/16
 */
public class AgentException extends RuntimeException {

    public AgentException(String message) {
        super(message);
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
