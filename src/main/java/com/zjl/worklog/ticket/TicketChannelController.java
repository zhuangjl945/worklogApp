package com.zjl.worklog.ticket;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.UserContext;
import com.zjl.worklog.ticket.entity.TicketChannelEntity;
import com.zjl.worklog.ticket.mapper.TicketChannelMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 登记渠道维护（二维码）。
 *
 * <p>可见范围同样是本科室；渠道码本身不是秘密（就印在二维码上），
 * 但「本科室有哪些渠道」属于组织信息，不做跨科室可见。
 */
@Validated
@RestController
@RequestMapping("/api/ticket-channels")
public class TicketChannelController {

    private final TicketChannelMapper channelMapper;

    public TicketChannelController(TicketChannelMapper channelMapper) {
        this.channelMapper = channelMapper;
    }

    @GetMapping
    public ApiResponse<PageResponse<ChannelView>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Integer status
    ) {
        CurrentUser cu = requireLogin();
        if (page < 1) {
            page = 1;
        }
        if (size < 1 || size > 100) {
            size = 20;
        }
        Long deptId = requireDept(cu);
        long total = channelMapper.count(deptId, status);
        List<TicketChannelEntity> rows = total == 0
                ? List.of()
                : channelMapper.selectPage((page - 1) * size, size, deptId, status);
        List<ChannelView> views = new ArrayList<>(rows.size());
        for (TicketChannelEntity e : rows) {
            views.add(ChannelView.of(e));
        }
        return ApiResponse.ok(PageResponse.of(page, size, total, views));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateChannelRequest req) {
        CurrentUser cu = requireLogin();
        Long deptId = requireDept(cu);
        if (channelMapper.selectByName(deptId, req.getChannelName().trim()) != null) {
            throw new BizException(40001, "同科室下已存在同名渠道");
        }

        TicketChannelEntity entity = new TicketChannelEntity();
        entity.setDeptId(deptId);
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
    public ApiResponse<Boolean> update(@PathVariable Long id, @Valid @RequestBody UpdateChannelRequest req) {
        CurrentUser cu = requireLogin();
        Long deptId = requireDept(cu);
        TicketChannelEntity existed = channelMapper.selectById(id);
        if (existed == null || !deptId.equals(existed.getDeptId())) {
            throw new BizException(40401, "渠道不存在或无权访问");
        }
        TicketChannelEntity update = new TicketChannelEntity();
        update.setId(id);
        update.setChannelName(req.getChannelName() == null ? null : req.getChannelName().trim());
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
        Long deptId = requireDept(cu);
        TicketChannelEntity existed = channelMapper.selectById(id);
        if (existed == null || !deptId.equals(existed.getDeptId())) {
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

    private Long requireDept(CurrentUser cu) {
        if (cu.getDeptId() == null) {
            throw new BizException(40001, "当前账号未绑定科室，无法维护登记渠道");
        }
        return cu.getDeptId();
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
