package com.zjl.worklog.security;

import com.zjl.worklog.config.entity.SysConfigEntity;
import com.zjl.worklog.config.mapper.SysConfigMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限点门槛的读取与保存。
 *
 * <p>存储复用 sys_config（config_group = permission，config_value = 角色名），
 * 不再为十来个开关单建一张表；读取全部走内存缓存，鉴权路径上不查库。
 *
 * <p>三件事必须记住：
 * <ol>
 *   <li>缓存只在启动和保存时刷新，所以「改了权限，已打开的页面要刷新才更新菜单」；
 *       但服务端判定用的是同一份缓存，不存在「前端没刷新就还能越权」。</li>
 *   <li>读库失败时全部回落到内置下限（floor），属于「权限收紧」而不是「权限放大」。</li>
 *   <li>所有写入都过 {@link Permission#clamp}，配置永远不可能低于代码里的下限。</li>
 * </ol>
 */
@Service
public class PermissionService {

    /** sys_config 里存放权限的分组名 */
    public static final String CONFIG_GROUP = "permission";

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private final SysConfigMapper sysConfigMapper;

    /** 权限点 key -> 生效的最低角色 */
    private final Map<String, Role> effective = new ConcurrentHashMap<>();

    public PermissionService(SysConfigMapper sysConfigMapper) {
        this.sysConfigMapper = sysConfigMapper;
    }

    @PostConstruct
    public void reload() {
        Map<String, Role> next = new LinkedHashMap<>();
        try {
            for (SysConfigEntity e : sysConfigMapper.selectByGroup(CONFIG_GROUP)) {
                Permission p = Permission.ofKey(e.getConfigKey());
                if (p == null) {
                    // 代码里已删掉的历史权限点：留着不影响判定，只在日志里说一次
                    log.debug("忽略未知权限配置：{}", e.getConfigKey());
                    continue;
                }
                next.put(p.getKey(), p.clamp(Role.of(e.getConfigValue())));
            }
        } catch (Exception e) {
            // 查库失败不能让鉴权失控：清空后统一按内置下限跑
            log.warn("权限配置读取失败，本轮全部按内置下限执行：{}", e.getMessage());
            next.clear();
        }
        for (Permission p : Permission.catalog()) {
            // 没配过的权限点用下限兜底，保证「新增收口点」不需要额外补数据
            next.putIfAbsent(p.getKey(), p.getFloor());
        }
        effective.clear();
        effective.putAll(next);
    }

    /** 生效门槛：调用方拿到的是「不低于内置下限」的角色 */
    public Role minRole(Permission permission) {
        if (permission == null || permission.isPlaceholder()) {
            return Role.USER;
        }
        return effective.getOrDefault(permission.getKey(), permission.getFloor());
    }

    /** 登录用户是否满足某权限点：Controller 里做数据范围判定时用 */
    public boolean allows(CurrentUser cu, Permission permission) {
        return cu != null && cu.getRole().atLeast(minRole(permission));
    }

    /** 某权限点的门槛中文名，拼错误提示用 */
    public String minRoleLabel(Permission permission) {
        return minRole(permission).getLabel();
    }

    /** 当前全部生效门槛（给前端渲染菜单与权限页用） */
    public Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Permission p : Permission.catalog()) {
            out.put(p.getKey(), minRole(p).name());
        }
        return out;
    }

    /**
     * 保存一个权限点的门槛。
     *
     * @return 实际生效的角色（可能被下限抬过，调用方要如实回给前端）
     */
    public Role save(Permission permission, Role requested) {
        Role applied = permission.clamp(requested);
        SysConfigEntity existed = sysConfigMapper.selectByKey(CONFIG_GROUP, permission.getKey());
        if (existed == null) {
            SysConfigEntity entity = new SysConfigEntity();
            entity.setConfigGroup(CONFIG_GROUP);
            entity.setConfigKey(permission.getKey());
            entity.setConfigValue(applied.name());
            entity.setConfigLabel(permission.getLabel());
            entity.setConfigDesc(permission.getDescription());
            entity.setSortOrder(permission.ordinal());
            sysConfigMapper.insert(entity);
        } else if (!applied.name().equals(existed.getConfigValue())) {
            sysConfigMapper.updateValue(existed.getId(), applied.name());
        }
        reload();
        return applied;
    }

    /** 全部恢复内置下限 */
    public void resetAll() {
        for (Permission p : Permission.catalog()) {
            save(p, p.getFloor());
        }
        reload();
    }
}