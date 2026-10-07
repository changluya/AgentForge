package cloud.changlu.agentforge.service.plugins.openreach;

/**
 * @description OpenReach 调用失败异常
 * @author changlu
 * @date 2026/10/07
 */
public class OpenReachException extends RuntimeException {

    public OpenReachException(String message) {
        super(message);
    }

    public OpenReachException(String message, Throwable cause) {
        super(message, cause);
    }
}
