package com.zjl.worklog.config;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.config.entity.SysConfigEntity;
import com.zjl.worklog.config.mapper.SysConfigMapper;
import com.zjl.worklog.security.Permission;
import com.zjl.worklog.security.PermissionService;
import com.zjl.worklog.security.RequireRole;
import com.zjl.worklog.security.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 系统参数配置接口
 * <p>
 * 提供参数的分组查询、批量保存、单条增删改功能。
 * 前端「参数配置」页面通过 /api/sys-configs 与本控制器交互。
 *
 * <p>这里要守住一条原则：页面上能改的，就得是真的能生效。
 * 哪些键满足这个条件由 {@link #RUNTIME_EFFECTIVE} 说了算，清单外的键一律拒绝从本页面写入。
 * 理由是它们的实际取值来自 application.yml（上传总大小、OSS 连接信息、系统名称），
 * 改完界面提示「已保存」而行为一点没变，比不让改恶劣得多——管理员会以为自己已经把限流调过了。
 */
@Validated
@RestController
@RequestMapping("/api/sys-configs")
// 参数配置直接影响鉴权与限流行为，收给系统管理员
@RequireRole(value = Role.ADMIN, permission = Permission.SYS_CONFIG_MANAGE)
public class SysConfigController {

    /**
     * 运行期真正生效的参数键，格式 group|key，支持「组|*」放行整组。
     *
     * <p>每一项都必须能指出读它的代码，新增消费方时记得同步登记：
     * <ul>
     *   <li>rate_limit.* → TicketService 的提交/渠道/手机号限流（走 SysConfigService 的 30 秒快照）</li>
     *   <li>system.jwt_expire_seconds → JwtTokenService 签发时读取（只允许缩短）</li>
     *   <li>upload.max_file_size_mb → OssController#policy 的直传大小上限</li>
     *   <li>urgency.* / ticket_flow.* / ticket_form.* → TicketService 的 SLA、自动确认、登记表单必填规则</li>
     *   <li>logging.* → LoggingLevelService 立刻改 Logback 级别</li>
     * </ul>
     *
     * <p>刻意留在清单外的：upload.max_request_size_mb（Spring multipart 在启动期就固定了）、
     * oss.*（endpoint/bucket/AK 属于部署配置，页面上改错一次全站附件就废）、system.site_name（目前没有消费方）。
     */
    private static final Set<String> RUNTIME_EFFECTIVE = Set.of(
            "rate_limit|ip_count",
            "rate_limit|ip_window_seconds",
            "rate_limit|channel_qpm",
            "rate_limit|phone_hour",
            "system|jwt_expire_seconds",
            "upload|max_file_size_mb",
            "urgency|*",
            "ticket_flow|*",
            "ticket_form|*",
            "logging|*"
    );

    private final SysConfigMapper sysConfigMapper;
    private final SysConfigService sysConfigService;
    private final LoggingLevelService loggingLevelService;

    public SysConfigController(SysConfigMapper sysConfigMapper,
                               SysConfigService sysConfigService,
                               LoggingLevelService loggingLevelService) {
        this.sysConfigMapper = sysConfigMapper;
        this.sysConfigService = sysConfigService;
        this.loggingLevelService = loggingLevelService;
    }

    // ── 分组元数据：前端用来渲染 Tab 标签和图标 ────────────────────

    /** 分组显示名与排序（硬编码在前端更灵活，但后端提供一份兜底顺序） */
    private static final Map<String, String> GROUP_LABELS = new LinkedHashMap<>();
    static {
        GROUP_LABELS.put("system",     "系统基础");
        GROUP_LABELS.put("upload",     "文件上传");
        GROUP_LABELS.put("rate_limit", "限流防护");
        GROUP_LABELS.put("oss",        "OSS 存储");
        GROUP_LABELS.put("urgency",    "紧急程度");
        GROUP_LABELS.put("ticket_flow", "工单流转");
        GROUP_LABELS.put("ticket_form", "登记表单");
        GROUP_LABELS.put("logging", "日志级别");
    }

    // ── 查询 ──────────────────────────────────────────────────

    /**
     * 查询所有参数，按分组归类返回
     * <p>
     * 响应结构：{ groups: [ { group, label, items: [...] }, ... ], runtimeEffective: ["rate_limit|ip_count", ...] }
     *
     * <p>runtimeEffective 是给前端用的：清单里的键正常渲染成可编辑，清单外的渲染成只读并说明
     * 「实际取值来自配置文件」。后端已经会拒绝写入清单外的键，但只靠报错的话，
     * 管理员得点一次保存才知道这项改不动，体验上等于陷阱。
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> listAll() {
        // 权限点也存在 sys_config（group=permission），但这里必须排掉：
        // 一是有专门的「权限设置」页做双入口，两边改的不是同一套语义；
        // 二是参数页改的是裸字符串，绕过了 Permission.clamp 的下限校验和缓存刷新。
        List<SysConfigEntity> all = sysConfigMapper.selectAll().stream()
                .filter(e -> !PermissionService.CONFIG_GROUP.equals(e.getConfigGroup()))
                .toList();

        // 按 configGroup 分组，保持 LinkedHashMap 的插入顺序
        Map<String, List<SysConfigEntity>> byGroup = all.stream()
                .collect(Collectors.groupingBy(
                        SysConfigEntity::getConfigGroup,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<SysConfigEntity>> entry : byGroup.entrySet()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("group", entry.getKey());
            g.put("label", GROUP_LABELS.getOrDefault(entry.getKey(), entry.getKey()));
            // 整组都不生效时给前端一个明确的说明，别让它逐条去比对清单
            boolean anyEditable = entry.getValue().stream()
                    .anyMatch(e -> isRuntimeEffective(e.getConfigGroup(), e.getConfigKey()));
            g.put("editable", anyEditable);
            g.put("items", entry.getValue());
            groups.add(g);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", groups);
        result.put("runtimeEffective", new ArrayList<>(RUNTIME_EFFECTIVE));
        return ApiResponse.ok(result);
    }

    /**
     * 按分组键名获取单个参数的值（供后端业务代码调用，也暴露给前端按需取用）
     */
    @GetMapping("/value")
    public ApiResponse<String> getValue(@RequestParam String group, @RequestParam String key) {
        SysConfigEntity entity = sysConfigMapper.selectByKey(group, key);
        return ApiResponse.ok(entity != null ? entity.getConfigValue() : null);
    }

    // ── 批量保存（前端整组提交） ────────────────────────────────

    /**
     * 批量更新参数值
     * <p>
     * 请求体：[ { id, configValue }, ... ]
     *
     * <p>先整体校验再落库：半批写入半批拒绝，页面上会呈现成「有的保存了有的没有」，最难解释。
     */
    @PutMapping("/batch")
    public ApiResponse<Boolean> batchUpdate(@RequestBody @Valid List<BatchItem> items) {
        if (items == null || items.isEmpty()) {
            return ApiResponse.ok(true);
        }
        for (BatchItem item : items) {
            SysConfigEntity existed = requireEditable(item.getId());
            requireValidValue(existed, item.getConfigValue());
        }
        for (BatchItem item : items) {
            sysConfigMapper.updateValue(item.getId(), item.getConfigValue());
        }
        // 让运行期快照立刻跟上，不用等 30 秒，也不用重启
        sysConfigService.invalidate();
        loggingLevelService.applyNow();
        return ApiResponse.ok(true);
    }

    // ── 单条操作 ──────────────────────────────────────────────

    /**
     * 新增参数
     * <p>
     * 允许新建清单外的键（给业务代码将来接管用），但会如实回 effective=false，
     * 让界面能当场说明「这条目前还没有读取方，改了不会影响系统行为」。
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody @Valid CreateConfigRequest req) {
        assertNotPermissionGroup(req.getConfigGroup());
        // 检查同组同键是否已存在
        SysConfigEntity existed = sysConfigMapper.selectByKey(req.getConfigGroup(), req.getConfigKey());
        if (existed != null) {
            throw new BizException(40001, "同组下已存在相同键名的参数");
        }

        SysConfigEntity entity = new SysConfigEntity();
        entity.setConfigGroup(req.getConfigGroup());
        entity.setConfigKey(req.getConfigKey());
        entity.setConfigValue(req.getConfigValue());
        entity.setConfigLabel(req.getConfigLabel());
        entity.setConfigDesc(req.getConfigDesc() != null ? req.getConfigDesc() : "");
        entity.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        requireValidValue(entity, entity.getConfigValue());
        sysConfigMapper.insert(entity);
        sysConfigService.invalidate();
        loggingLevelService.applyNow();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entity.getId());
        out.put("effective", isRuntimeEffective(entity.getConfigGroup(), entity.getConfigKey()));
        return ApiResponse.ok(out);
    }

    /**
     * 删除参数
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        SysConfigEntity existed = id == null ? null : sysConfigMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "参数不存在");
        }
        // 权限门槛的行只能由「权限设置」页维护：从这儿删掉，会留下一条界面看不到、
        // 又随时可能被 PermissionService 重新 clamp 出来的影子配置
        assertNotPermissionGroup(existed.getConfigGroup());
        sysConfigMapper.deleteById(id);
        sysConfigService.invalidate();
        loggingLevelService.applyNow();
        return ApiResponse.ok(true);
    }

    // ── 内部 ──────────────────────────────────────────────────

    /** 取出一条可编辑的参数行，顺带把「存在性 / 权限组 / 运行期生效」三重校验做完 */
    private SysConfigEntity requireEditable(Long id) {
        if (id == null) {
            throw new BizException(40001, "参数ID不能为空");
        }
        SysConfigEntity existed = sysConfigMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "参数不存在，请刷新后重试");
        }
        assertNotPermissionGroup(existed.getConfigGroup());
        if (!isRuntimeEffective(existed.getConfigGroup(), existed.getConfigKey())) {
            throw new BizException(40003, "「" + existed.getConfigLabel()
                    + "」的实际取值来自服务端配置文件，在本页修改不会生效，已拒绝保存");
        }
        return existed;
    }

    /** 日志级别只认 Logback 那几个单词，手填 FOO 会让运行期悄悄回落到 INFO，界面却显示已保存 */
    private static void requireValidValue(SysConfigEntity existed, String value) {
        if (LoggingLevelService.GROUP.equals(existed.getConfigGroup())
                && !LoggingLevelService.isValidLevel(value)) {
            throw new BizException(40001, "「" + existed.getConfigLabel()
                    + "」只能是 TRACE、DEBUG、INFO、WARN、ERROR、OFF");
        }
    }

    private static void assertNotPermissionGroup(String group) {
        if (PermissionService.CONFIG_GROUP.equals(group)) {
            throw new BizException(40003, "权限门槛请到 系统管理-权限设置 调整，参数配置页不维护它");
        }
    }

    /** 该键在运行期是否真有读取方；支持「组|*」整组放行 */
    static boolean isRuntimeEffective(String group, String key) {
        if (group == null) {
            return false;
        }
        if (RUNTIME_EFFECTIVE.contains(group + "|*")) {
            return true;
        }
        return key != null && RUNTIME_EFFECTIVE.contains(group + "|" + key.trim());
    }

    // ── 请求 DTO ──────────────────────────────────────────────

    public static class BatchItem {
        private Long id;
        private String configValue;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getConfigValue() { return configValue; }
        public void setConfigValue(String configValue) { this.configValue = configValue; }
    }

    public static class CreateConfigRequest {
        @NotBlank
        private String configGroup;
        @NotBlank
        private String configKey;
        private String configValue;
        @NotBlank
        private String configLabel;
        private String configDesc;
        private Integer sortOrder;

        public String getConfigGroup() { return configGroup; }
        public void setConfigGroup(String configGroup) { this.configGroup = configGroup; }
        public String getConfigKey() { return configKey; }
        public void setConfigKey(String configKey) { this.configKey = configKey; }
        public String getConfigValue() { return configValue; }
        public void setConfigValue(String configValue) { this.configValue = configValue; }
        public String getConfigLabel() { return configLabel; }
        public void setConfigLabel(String configLabel) { this.configLabel = configLabel; }
        public String getConfigDesc() { return configDesc; }
        public void setConfigDesc(String configDesc) { this.configDesc = configDesc; }
        public Integer getSortOrder() { return sortOrder; }
        public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    }
}
