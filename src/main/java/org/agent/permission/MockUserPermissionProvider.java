package org.agent.permission;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开发期 Mock IAM：内置两名员工模拟"身份 + 权限"，并支持模拟权限变更。
 * <p>
 * 用 {@link #bumpAuthVersion(String)} 触发 auth_version 变化，即可在
 * SessionAuthServiceTest 或线上接口里看到 TOCTOU 重拉逻辑。
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

    @Override
    public UserPermissionProfile loadProfile(String userId) {
        UserPermissionProfile p = USERS.get(userId);
        if (p == null) {
            throw new SessionAuthException(SessionAuthException.Code.UNAUTHORIZED, "身份不可识别: " + userId);
        }
        long v = currentAuthVersion(userId);
        return new UserPermissionProfile(p.userId(), p.roleCode(), p.rankCode(), p.deptCode(), p.orgPath(),
                p.dataClearanceLevel(), p.kbIds(), v);
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

    /** 恢复默认版本，仅开发/测试用。 */
    public void reset() {
        versionOverrides.clear();
    }
}
