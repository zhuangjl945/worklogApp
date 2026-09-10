package com.zjl.worklog.ticket;

import com.zjl.worklog.security.Permission;
import com.zjl.worklog.security.RequireRole;
import com.zjl.worklog.security.Role;
import com.zjl.worklog.security.DataScope;
import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.UserContext;
import com.zjl.worklog.dept.entity.DeptEntity;
import com.zjl.worklog.dept.mapper.DeptMapper;
import com.zjl.worklog.ticket.entity.TicketChannelEntity;
import com.zjl.worklog.ticket.mapper.TicketChannelMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 登记渠道维护（二维码）。
 *
 * <p>可见范围默认是本科室：渠道码本身不是秘密（就印在二维码上），但「本科室有哪些渠道」属于组织信息。
 * 唯一的例外是系统管理员——和「工作量报表的跨科室筛选」同一口径，他可以带 deptId 查某个科室的渠道，
 * 不传则看全部。否则 admin 账号绑在信息科之外的科室时，这个页面永远是一张空白表，
 * 连「别的科室到底配了什么」都无从核对，出了故障只能让各科科长自己翻。
 * 写操作（新建/修改/停用/二维码重置以外的变更）仍然锁本科室，跨科室写需要单独设计目标科室校验。
 */
@Validated
@RestController
@RequestMapping("/api/ticket-channels")
public class TicketChannelController {

    private final TicketChannelMapper channelMapper;
    private final DeptMapper deptMapper;

    public TicketChannelController(TicketChannelMapper channelMapper, DeptMapper deptMapper) {
        this.channelMapper = channelMapper;
        this.deptMapper = deptMapper;
    }

    /**
     * 本机局域网 origin，给管理端在 localhost 下生成手机能打开的二维码。
     * 走 Vite 代理时端口会是后端端口，开发服务器另有 /__dev/public-origin，前端会优先用那个。
     */
    @GetMapping("/lan-origins")
    public ApiResponse<List<String>> lanOrigins(HttpServletRequest req) {
        requireLogin();
        String scheme = req.getScheme();
        int port = req.getLocalPort();
        List<String> origins = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.getHostAddress().contains(":")) {
                        continue;
                    }
                    String origin = scheme + "://" + addr.getHostAddress()
                            + ((("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443))
                            ? "" : ":" + port);
                    if (!origins.contains(origin)) {
                        origins.add(origin);
                    }
                }
            }
        } catch (Exception ignored) {
            // 枚举失败时前端仍可手填 IP
        }
        origins.sort(Comparator.comparingInt(TicketChannelController::lanOriginRank).thenComparing(String::compareTo));
        return ApiResponse.ok(origins);
    }

    private static int lanOriginRank(String origin) {
        String host = origin.replaceFirst("^https?://", "").replaceFirst(":\\d+$", "");
        if (host.startsWith("192.168.")) {
            return 0;
        }
        if (host.startsWith("10.")) {
            return 1;
        }
        if (host.matches("172\\.(1[6-9]|2\\d|3[0-1])\\..*")) {
            return 2;
        }
        return 3;
    }

    @GetMapping
    public ApiResponse<PageResponse<ChannelView>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Integer status,
            // 系统管理员可按科室筛选；其他角色忽略此参数，一律锁本科室
            @RequestParam(required = false) Long deptId
    ) {
        CurrentUser cu = requireLogin();
        if (page < 1) {
            page = 1;
        }
        if (size < 1 || size > 100) {
            size = 20;
        }
        Long scopeDeptId = resolveListScope(cu, deptId);
        long total = channelMapper.count(scopeDeptId, status);
        List<TicketChannelEntity> rows = total == 0
                ? List.of()
                : channelMapper.selectPage((page - 1) * size, size, scopeDeptId, status);
        List<ChannelView> views = new ArrayList<>(rows.size());
        for (TicketChannelEntity e : rows) {
            views.add(toView(e));
        }
        return ApiResponse.ok(PageResponse.of(page, size, total, views));
    }

    @PostMapping
    @RequireRole(value = Role.DEPT_ADMIN, permission = Permission.TICKET_CHANNEL_MANAGE)
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateChannelRequest req) {
        CurrentUser cu = requireLogin();
        Long deptId = requireDept(cu);
        requireEnabledBizDept(req.getBizDeptId());
        assertUniqueBizDept(deptId, req.getBizDeptId(), null);
        if (channelMapper.selectByName(deptId, req.getChannelName().trim()) != null) {
            throw new BizException(40001, "同科室下已存在同名渠道");
        }

        TicketChannelEntity entity = new TicketChannelEntity();
        entity.setDeptId(deptId);
        entity.setBizDeptId(req.getBizDeptId());
        entity.setChannelName(req.getChannelName().trim());
        entity.setDefaultCategoryId(req.getDefaultCategoryId());
        entity.setNeedPhone(req.getNeedPhone() == null ? 1 : req.getNeedPhone());
        entity.setDailyLimit(req.getDailyLimit() == null ? 200 : req.getDailyLimit());
        entity.setRemark(req.getRemark());
        entity.setStatus(1);
        // 渠道码随机生成，避免管理员起的名被枚举（DEPT1/DEPT2 这种）
        entity.setChannelCode(uniqueChannelCode());
        channelMapper.insert(entity);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", entity.getId());
        data.put("channelCode", entity.getChannelCode());
        data.put("mobilePath", mobilePath(entity.getChannelCode()));
        return ApiResponse.ok(data);
    }

    @PutMapping("/{id}")
    @RequireRole(value = Role.DEPT_ADMIN, permission = Permission.TICKET_CHANNEL_MANAGE)
    public ApiResponse<Boolean> update(@PathVariable Long id, @Valid @RequestBody UpdateChannelRequest req) {
        CurrentUser cu = requireLogin();
        Long deptId = requireDept(cu);
        TicketChannelEntity existed = channelMapper.selectById(id);
        if (existed == null || !deptId.equals(existed.getDeptId())) {
            throw new BizException(40401, "渠道不存在或无权访问");
        }
        if (req.getBizDeptId() != null) {
            requireEnabledBizDept(req.getBizDeptId());
            int willBeEnabled = req.getStatus() != null ? req.getStatus() : existed.getStatus();
            if (willBeEnabled == 1) {
                assertUniqueBizDept(deptId, req.getBizDeptId(), id);
            }
        }
        TicketChannelEntity update = new TicketChannelEntity();
        update.setId(id);
        update.setChannelName(req.getChannelName() == null ? null : req.getChannelName().trim());
        update.setBizDeptId(req.getBizDeptId());
        update.setDefaultCategoryId(req.getDefaultCategoryId());
        update.setNeedPhone(req.getNeedPhone());
        update.setDailyLimit(req.getDailyLimit());
        update.setRemark(req.getRemark());
        update.setStatus(req.getStatus());
        channelMapper.update(update);
        return ApiResponse.ok(true);
    }

    /** 启用/停用：停用后该渠道的二维码立刻扫不出可提交的入口，历史工单不受影响 */
    @PostMapping("/{id}/status")
    @RequireRole(value = Role.DEPT_ADMIN, permission = Permission.TICKET_CHANNEL_MANAGE)
    public ApiResponse<Boolean> toggle(@PathVariable Long id, @RequestParam Integer status) {
        CurrentUser cu = requireLogin();
        Long deptId = requireDept(cu);
        TicketChannelEntity existed = channelMapper.selectById(id);
        if (existed == null || !deptId.equals(existed.getDeptId())) {
            throw new BizException(40401, "渠道不存在或无权访问");
        }
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(40001, "状态值只能是 0 或 1");
        }
        if (status == 1 && existed.getBizDeptId() != null) {
            assertUniqueBizDept(deptId, existed.getBizDeptId(), id);
        }
        TicketChannelEntity update = new TicketChannelEntity();
        update.setId(id);
        update.setStatus(status);
        channelMapper.update(update);
        return ApiResponse.ok(true);
    }

    /**
     * 二维码内容。
     *
     * <p>这里只给相对路径，绝对域名由前端用 location.origin 拼接：
     * 后端不知道用户是从内网 IP 还是从域名访问的，写死配置项反而容易在生产漏改。
     */
    @GetMapping("/{id}/qrcode")
    public ApiResponse<Map<String, Object>> qrcode(@PathVariable Long id) {
        CurrentUser cu = requireLogin();
        TicketChannelEntity existed = channelMapper.selectById(id);
        if (existed == null || !canReadChannel(cu, existed)) {
            throw new BizException(40401, "渠道不存在或无权访问");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channelCode", existed.getChannelCode());
        data.put("mobilePath", mobilePath(existed.getChannelCode()));
        data.put("queryPath", "/m/query");
        return ApiResponse.ok(data);
    }

    private static String mobilePath(String channelCode) {
        return "/m/" + channelCode;
    }

    private String uniqueChannelCode() {
        for (int i = 0; i < 5; i++) {
            String code = TicketTokens.randomChannelCode();
            if (channelMapper.selectByCode(code) == null) {
                return code;
            }
        }
        throw new BizException(50001, "渠道编码生成失败，请重试");
    }

    /**
     * 列表的科室范围：系统管理员可自由指定（含不指定=全部），其他角色强制本科室。
     *
     * <p>外部传参只允许收窄、不允许扩大，和看板 scope 的处理方式保持一致。
     */
    private Long resolveListScope(CurrentUser cu, Long requestedDeptId) {
        if (DataScope.canCrossDept(cu)) {
            return requestedDeptId;
        }
        return requireDept(cu);
    }

    /** 单条渠道可读：本科室，或具备跨科室能力的系统管理员 */
    private boolean canReadChannel(CurrentUser cu, TicketChannelEntity channel) {
        if (DataScope.canCrossDept(cu)) {
            return true;
        }
        Long deptId = cu.getDeptId();
        return deptId != null && deptId.equals(channel.getDeptId());
    }

    private Long requireDept(CurrentUser cu) {
        if (cu.getDeptId() == null) {
            throw new BizException(40001, "当前账号未绑定科室，无法维护登记渠道");
        }
        return cu.getDeptId();
    }

    /** 绑定对象必须是科室管理里已启用的科室 */
    private void requireEnabledBizDept(Long bizDeptId) {
        if (bizDeptId == null) {
            throw new BizException(40001, "请选择问题所在科室");
        }
        DeptEntity dept = deptMapper.selectById(bizDeptId);
        if (dept == null || dept.getStatus() == null || dept.getStatus() != 1) {
            throw new BizException(40001, "所选科室不存在或已停用");
        }
    }

    /** 同一受理科室下，一个业务科室只允许一条启用中的渠道 */
    private void assertUniqueBizDept(Long acceptDeptId, Long bizDeptId, Long excludeChannelId) {
        TicketChannelEntity other = channelMapper.selectEnabledByBizDept(acceptDeptId, bizDeptId);
        if (other != null && (excludeChannelId == null || !excludeChannelId.equals(other.getId()))) {
            throw new BizException(40001, "该科室已有启用中的登记码，请停用旧入口或改绑其他科室");
        }
    }

    private ChannelView toView(TicketChannelEntity e) {
        ChannelView v = ChannelView.of(e);
        if (e.getBizDeptId() != null) {
            DeptEntity dept = deptMapper.selectById(e.getBizDeptId());
            v.bizDeptName = dept == null ? null : dept.getDeptName();
        }
        return v;
    }

    private CurrentUser requireLogin() {
        CurrentUser cu = UserContext.get();
        if (cu == null) {
            throw new BizException(401, "未登录或登录已过期");
        }
        return cu;
    }

    /** 对外视图：不含任何内部凭据字段 */
    public static class ChannelView {
        private Long id;
        private String channelCode;
        private String channelName;
        private Long bizDeptId;
        private String bizDeptName;
        private Long defaultCategoryId;
        private Integer needPhone;
        private Integer dailyLimit;
        private String remark;
        private Integer status;
        private String mobilePath;

        static ChannelView of(TicketChannelEntity e) {
            ChannelView v = new ChannelView();
            v.id = e.getId();
            v.channelCode = e.getChannelCode();
            v.channelName = e.getChannelName();
            v.bizDeptId = e.getBizDeptId();
            v.defaultCategoryId = e.getDefaultCategoryId();
            v.needPhone = e.getNeedPhone();
            v.dailyLimit = e.getDailyLimit();
            v.remark = e.getRemark();
            v.status = e.getStatus();
            v.mobilePath = mobilePath(e.getChannelCode());
            return v;
        }

        public Long getId() {
            return id;
        }

        public String getChannelCode() {
            return channelCode;
        }

        public String getChannelName() {
            return channelName;
        }

        public Long getBizDeptId() {
            return bizDeptId;
        }

        public String getBizDeptName() {
            return bizDeptName;
        }

        public Long getDefaultCategoryId() {
            return defaultCategoryId;
        }

        public Integer getNeedPhone() {
            return needPhone;
        }

        public Integer getDailyLimit() {
            return dailyLimit;
        }

        public String getRemark() {
            return remark;
        }

        public Integer getStatus() {
            return status;
        }

        public String getMobilePath() {
            return mobilePath;
        }
    }

    public static class CreateChannelRequest {
        @NotBlank(message = "请填写渠道名称")
        @Size(max = 100)
        private String channelName;
        @NotNull(message = "请选择问题所在科室")
        private Long bizDeptId;
        private Long defaultCategoryId;
        private Integer needPhone;
        @Size(max = 255)
        private String remark;
        private Integer dailyLimit;

        public String getChannelName() {
            return channelName;
        }

        public void setChannelName(String channelName) {
            this.channelName = channelName;
        }

        public Long getBizDeptId() {
            return bizDeptId;
        }

        public void setBizDeptId(Long bizDeptId) {
            this.bizDeptId = bizDeptId;
        }

        public Long getDefaultCategoryId() {
            return defaultCategoryId;
        }

        public void setDefaultCategoryId(Long defaultCategoryId) {
            this.defaultCategoryId = defaultCategoryId;
        }

        public Integer getNeedPhone() {
            return needPhone;
        }

        public void setNeedPhone(Integer needPhone) {
            this.needPhone = needPhone;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }

        public Integer getDailyLimit() {
            return dailyLimit;
        }

        public void setDailyLimit(Integer dailyLimit) {
            this.dailyLimit = dailyLimit;
        }
    }

    public static class UpdateChannelRequest {
        @Size(max = 100)
        private String channelName;
        private Long bizDeptId;
        private Long defaultCategoryId;
        private Integer needPhone;
        private Integer dailyLimit;
        @Size(max = 255)
        private String remark;
        private Integer status;

        public String getChannelName() {
            return channelName;
        }

        public void setChannelName(String channelName) {
            this.channelName = channelName;
        }

        public Long getBizDeptId() {
            return bizDeptId;
        }

        public void setBizDeptId(Long bizDeptId) {
            this.bizDeptId = bizDeptId;
        }

        public Long getDefaultCategoryId() {
            return defaultCategoryId;
        }

        public void setDefaultCategoryId(Long defaultCategoryId) {
            this.defaultCategoryId = defaultCategoryId;
        }

        public Integer getNeedPhone() {
            return needPhone;
        }

        public void setNeedPhone(Integer needPhone) {
            this.needPhone = needPhone;
        }

        public Integer getDailyLimit() {
            return dailyLimit;
        }

        public void setDailyLimit(Integer dailyLimit) {
            this.dailyLimit = dailyLimit;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }
    }
}
