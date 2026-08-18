package org.agent.permission;

/**
 * 会话权限校验失败统一异常 —— 所有校验都是 fail-closed：不通过即抛异常拒绝，绝不降级继续。
 */
public class SessionAuthException extends RuntimeException {

    public enum Code {
        /** 会话不存在 / 身份不可识别 */
        UNAUTHORIZED,
        /** 会话状态异常(已关闭/已终止) */
        SESSION_NOT_ACTIVE,
        /** 会话已过期 */
        SESSION_EXPIRED,
        /** 会话令牌不匹配 */
        TOKEN_MISMATCH,
        /** 连续失败过多，会话被锁定，转人工 */
        SESSION_LOCKED,
        /** 权限已变更(TOCTOU)，或用户在权限中心已失效 */
        PERMISSION_CHANGED,
        /** 配额超限，熔断，转人工 */
        QUOTA_EXCEEDED,
    }

    private final Code code;

    public SessionAuthException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
