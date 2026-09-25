package org.agent.permission;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开发期 Mock IAM：内置两名员工模拟"身份 + 权限"，并支持模拟权限变更。
 * <p>
 * 用 {@link #bumpAuthVersion(String)} 触发 auth_version 变化，即可在
 * SessionAuthServiceTest 或线上接口里看到 TOCTOU 重拉逻辑；
 * 用 {@link #downgradeClearance(String, int)} 可以构造"同一会话被降级"的场景
 * （密级记忆过滤的现场，见 RAG-07 §一 表 #2）。
 */
@Component
public class MockUserPermissionProvider implements UserPermissionProvider {

    private static final Map<String, UserPermissionProfile> USERS = new ConcurrentHashMap<>();

    static {
        // 柜员：低密级，只能检索柜员知识库
        USERS.put("10086", new UserPermissionProfile("10086", "TELLER", "T3", "0101", "0100/0101",
                2, List.of("kb-teller"), 0L));
        // 风控/合规：高密级，可检索风控 + 柜员知识库
        USERS.put("10087", new UserPermissionProfile("10087", "RISK", "M2", "0202", "0100/0202",
                4, List.of("kb-risk", "kb-teller"), 0L));
    }

    /** 模拟 IAM 侧的权限版本覆盖值；bump 后代表该用户权限已变更 */
    private final Map<String, Long> versionOverrides = new ConcurrentHashMap<>();

    /** 模拟 IAM 侧的数据密级覆盖值；用于"同一个会话被降级"的场景 */
    private final Map<String, Integer> clearanceOverrides = new ConcurrentHashMap<>();

    @Override
    public UserPermissionProfile loadProfile(String userId) {
        UserPermissionProfile p = USERS.get(userId);
        if (p == null) {
            throw new SessionAuthException(SessionAuthException.Code.UNAUTHORIZED, "身份不可识别: " + userId);
        }
        long v = currentAuthVersion(userId);
        return new UserPermissionProfile(p.userId(), p.roleCode(), p.rankCode(), p.deptCode(), p.orgPath(),
                clearanceOverrides.getOrDefault(userId, p.dataClearanceLevel()), p.kbIds(), v);
    }

    @Override
    public long currentAuthVersion(String userId) {
        UserPermissionProfile p = USERS.get(userId);
        if (p == null) {
            return -1L; // 用户在权限中心已不存在
        }
        return versionOverrides.getOrDefault(userId, p.authVersion());
    }

    /** 模拟权限变更（角色调整/部门调动/权限回收），仅开发/测试用。 */
    public void bumpAuthVersion(String userId) {
        versionOverrides.merge(userId, 1L, Long::sum);
    }

    /**
     * 模拟权限降级：真的把密级调小，并同时 bump 版本，使下一次
     * {@code SessionAuthService.verifyAndLoad} 必定重拉画像、刷新会话快照
     * —— 这正是 {@link #bumpAuthVersion} 注释里说的"权限回收"，区别是它会让
     * 信封里的 {@code dataClearanceLevel} 真的变小。仅开发/测试用。
     * <p>
     * 存在的理由：没有它就无法构造"同一会话先高密级、后被降级"的场景，
     * 而那正是 RAG-07 §一 表 #2 的现场。
     */
    public void downgradeClearance(String userId, int clearanceLevel) {
        clearanceOverrides.put(userId, clearanceLevel);
        bumpAuthVersion(userId);
    }

    /** 恢复默认版本与密级，仅开发/测试用。 */
    public void reset() {
        versionOverrides.clear();
        clearanceOverrides.clear();
    }
}
