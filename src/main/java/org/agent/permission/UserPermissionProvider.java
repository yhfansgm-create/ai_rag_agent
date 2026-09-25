package org.agent.permission;

/**
 * 用户权限数据源(即 IAM/权限中心)。这是唯一持有用户权限的主数据方。
 * <p>
 * 应用侧策略：会话创建时拉一次画像做快照；每次请求核对 authVersion，
 * 变了就重新拉画像刷新快照（防 TOCTOU 越权扩大）。
 * <p>
 * 开发期用 {@link MockUserPermissionProvider} 模拟；接真实 IAM 时实现本接口即可，
 * 业务代码(Service/Advisor)零改动。
 */
public interface UserPermissionProvider {

    /**
     * 拉取用户完整权限画像。
     *
     * @throws SessionAuthException 用户不存在/被禁用（{@link SessionAuthException.Code#UNAUTHORIZED}）
     */
    UserPermissionProfile loadProfile(String userId);

    /**
     * 当前权限版本号。每次请求用它和会话表里的 auth_version 比对：
     * <ul>
     *   <li>比快照大 → 权限已变更，需要重新拉取</li>
     *   <li>返回负数 → 用户在权限中心已不存在，会话应强制失效</li>
     * </ul>
     */
    long currentAuthVersion(String userId);
}
