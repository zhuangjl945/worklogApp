package com.zjl.worklog.ticket;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.UserContext;
import com.zjl.worklog.security.Permission;
import com.zjl.worklog.security.RequireRole;
import com.zjl.worklog.security.Role;
import com.zjl.worklog.ticket.dto.TicketView;
import com.zjl.worklog.ticket.entity.ServiceTicketEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问题受理台（PC 端，需登录）。
 *
 * <p>权限分两层，与全站口径一致：
 * <ol>
 *   <li><b>接口门槛</b>：类级 @RequireRole(USER) 只管「必须登录」，读接口到此为止；
 *       受理/派单/回复/完成/退回/归档/转记录这 7 个写接口各自再挂一道 ticket.manage 权限点。
 *       内置下限是 USER，即维持「谁先看到谁先受理」的互助现状；管理员想把流转动作收回到科室管理员以上，
 *       在「权限设置」里调高 ticket.manage 即可，不用改代码。改造前这里完全没有开关。
 *       <p>门槛刻意只挂在写上：权限点若挂到类级，调严它连同工单列表、详情一起对普通员工消失，
 *       而「能不能看到本科室有哪些问题」和「能不能动手改它」本来就该是两个问题。</li>
 *   <li><b>数据行级</b>：可见与可改范围仍以「登录人所在科室」为边界（requireInDept）。
 *       dept_id 只从登录上下文取，前端传来的同名字段一律忽略；没有科室信息的账号看不到任何工单。</li>
 * </ol>
 *   <li><b>数据行级</b>：可见与可改范围仍以「登录人所在科室」为边界（requireInDept）。
 *       dept_id 只从登录上下文取，前端传来的同名字段一律忽略；没有科室信息的账号看不到任何工单。</li>
 * </ol>
 */
@Validated
@RestController
@RequestMapping("/api/tickets")
@RequireRole(value = Role.USER, message = "登录后才能使用问题受理台")
public class TicketController {

    private final TicketService ticketService;
    private final com.zjl.worklog.oss.OssObjectReader ossObjectReader;

    public TicketController(TicketService ticketService, com.zjl.worklog.oss.OssObjectReader ossObjectReader) {
        this.ticketService = ticketService;
        this.ossObjectReader = ossObjectReader;
    }

    /** 状态字典：给受理台的状态筛选下拉用，直接来自状态机枚举，保证前端不会出现非法状态 */
    @GetMapping("/statuses")
    public ApiResponse<List<Map<String, Object>>> statuses() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TicketStatus s : TicketStatus.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", s.code());
            item.put("name", s.label());
            item.put("final", s.isFinal());
            list.add(item);
        }
        return ApiResponse.ok(list);
    }

    @GetMapping
    public ApiResponse<PageResponse<TicketView>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) List<Integer> statusIds,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createTimeFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createTimeTo
    ) {
        CurrentUser cu = requireLogin();
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 10;
        }
        if (size > 100) {
            size = 100;
        }
        Long assigneeId = null;
        List<Integer> statuses = statusIds;
        // scope 只允许收窄查询范围，不允许扩大
        if ("mine".equals(scope)) {
            assigneeId = cu.getId();
        } else if ("open".equals(scope)) {
            statuses = (statusIds == null || statusIds.isEmpty()) ? TicketStatus.openCodes() : statusIds;
        }
        return ApiResponse.ok(ticketService.page(cu.getDeptId(), statuses, categoryId, assigneeId,
                keyword, overdue, createTimeFrom, createTimeTo, page, size));
    }

    /** 顶部铃铛角标 */
    @GetMapping("/pending-count")
    public ApiResponse<Map<String, Object>> pendingCount() {
        CurrentUser cu = requireLogin();
        TicketService.PendingCount count = ticketService.pendingCount(cu.getDeptId(), cu.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deptPending", count.getDeptPending());
        data.put("mine", count.getMine());
        return ApiResponse.ok(data);
    }

    @GetMapping("/{id}")
    public ApiResponse<TicketView> detail(@PathVariable Long id) {
        CurrentUser cu = requireLogin();
        ServiceTicketEntity ticket = ticketService.requireInDept(id, cu.getDeptId());
        return ApiResponse.ok(ticketService.detail(ticket));
    }

    /** 受理：认领并生成工作记录 */
    // 流转动作受 ticket.manage 权限点约束（内置下限 USER，可在「权限设置」调严）；读接口不受它影响
    @RequireRole(value = Role.USER, permission = Permission.TICKET_MANAGE)
    @PostMapping("/{id}/accept")
    public ApiResponse<Map<String, Object>> accept(@PathVariable Long id) {
        CurrentUser cu = requireLogin();
        ServiceTicketEntity ticket = ticketService.requireInDept(id, cu.getDeptId());
        Long recordId = ticketService.accept(ticket, cu);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workRecordId", recordId);
        return ApiResponse.ok(data);
    }

    // 流转动作受 ticket.manage 权限点约束（内置下限 USER，可在「权限设置」调严）；读接口不受它影响
    @RequireRole(value = Role.USER, permission = Permission.TICKET_MANAGE)
    @PostMapping("/{id}/assign")
    public ApiResponse<Boolean> assign(@PathVariable Long id, @Valid @RequestBody AssignRequest req) {
        CurrentUser cu = requireLogin();
        ServiceTicketEntity ticket = ticketService.requireInDept(id, cu.getDeptId());
        ticketService.assign(ticket, req.getToUserId(), req.getRemark(), cu);
        return ApiResponse.ok(true);
    }

    /** visibleToReporter=false 时是仅本科室可见的内部备注 */
    // 流转动作受 ticket.manage 权限点约束（内置下限 USER，可在「权限设置」调严）；读接口不受它影响
    @RequireRole(value = Role.USER, permission = Permission.TICKET_MANAGE)
    @PostMapping("/{id}/reply")
    public ApiResponse<Boolean> reply(@PathVariable Long id, @Valid @RequestBody ReplyRequest req) {
        CurrentUser cu = requireLogin();
        ServiceTicketEntity ticket = ticketService.requireInDept(id, cu.getDeptId());
        ticketService.reply(ticket, req.getRemark(), req.getImageKeys(),
                req.getVisibleToReporter() == null || req.getVisibleToReporter(), cu);
        return ApiResponse.ok(true);
    }

    // 流转动作受 ticket.manage 权限点约束（内置下限 USER，可在「权限设置」调严）；读接口不受它影响
    @RequireRole(value = Role.USER, permission = Permission.TICKET_MANAGE)
    @PostMapping("/{id}/done")
    public ApiResponse<Boolean> done(@PathVariable Long id, @Valid @RequestBody RemarkRequest req) {
        CurrentUser cu = requireLogin();
        ServiceTicketEntity ticket = ticketService.requireInDept(id, cu.getDeptId());
        ticketService.done(ticket, req.getRemark(), cu);
        return ApiResponse.ok(true);
    }

    // 流转动作受 ticket.manage 权限点约束（内置下限 USER，可在「权限设置」调严）；读接口不受它影响
    @RequireRole(value = Role.USER, permission = Permission.TICKET_MANAGE)
    @PostMapping("/{id}/reject")
    public ApiResponse<Boolean> reject(@PathVariable Long id, @Valid @RequestBody RejectRequest req) {
        CurrentUser cu = requireLogin();
        ServiceTicketEntity ticket = ticketService.requireInDept(id, cu.getDeptId());
        ticketService.reject(ticket, req.getReason(), cu);
        return ApiResponse.ok(true);
    }

    // 流转动作受 ticket.manage 权限点约束（内置下限 USER，可在「权限设置」调严）；读接口不受它影响
    @RequireRole(value = Role.USER, permission = Permission.TICKET_MANAGE)
    @PostMapping("/{id}/close")
    public ApiResponse<Boolean> close(@PathVariable Long id) {
        CurrentUser cu = requireLogin();
        ServiceTicketEntity ticket = ticketService.requireInDept(id, cu.getDeptId());
        ticketService.close(ticket, cu);
        return ApiResponse.ok(true);
    }

    /** 幂等：已生成过就返回原记录 */
    // 流转动作受 ticket.manage 权限点约束（内置下限 USER，可在「权限设置」调严）；读接口不受它影响
    @RequireRole(value = Role.USER, permission = Permission.TICKET_MANAGE)
    @PostMapping("/{id}/to-record")
    public ApiResponse<Map<String, Object>> toRecord(@PathVariable Long id) {
        CurrentUser cu = requireLogin();
        ServiceTicketEntity ticket = ticketService.requireInDept(id, cu.getDeptId());
        Long recordId = ticketService.toRecord(ticket, cu);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workRecordId", recordId);
        return ApiResponse.ok(data);
    }

    /**
     * 受理台取图：与报修人侧对称，但鉴权方式不同（登录态 + 科室边界）。
     *
     * <p>不走 /api/oss/preview-url 那条路，是因为它要求入参是完整 URL 并会校验 bucket 域名，
     * 而工单里存的是 objectKey；也不依赖 bucket 是否公开读——将来改成私有读这里不用动。
     */
    @GetMapping("/{id}/image")
    public org.springframework.http.ResponseEntity<byte[]> image(@PathVariable Long id,
                                                                @RequestParam String key) {
        CurrentUser cu = requireLogin();
        com.zjl.worklog.ticket.entity.ServiceTicketEntity ticket = ticketService.requireInDept(id, cu.getDeptId());
        if (ticketService.findImage(ticket, key) == null) {
            throw new BizException(40303, "图片不属于该工单");
        }
        byte[] data = ossObjectReader.read(key);
        org.springframework.http.MediaType type = key.toLowerCase().endsWith(".png")
                ? org.springframework.http.MediaType.IMAGE_PNG
                : key.toLowerCase().endsWith(".gif") ? org.springframework.http.MediaType.IMAGE_GIF
                : key.toLowerCase().endsWith(".webp") ? org.springframework.http.MediaType.parseMediaType("image/webp")
                : org.springframework.http.MediaType.IMAGE_JPEG;
        return org.springframework.http.ResponseEntity.ok()
                .contentType(type)
                .header("Cache-Control", "private, max-age=300")
                .body(data);
    }
    private CurrentUser requireLogin() {
        CurrentUser cu = UserContext.get();
        if (cu == null) {
            throw new BizException(401, "未登录或登录已过期");
        }
        return cu;
    }

    public static class AssignRequest {
        private Long toUserId;
        @Size(max = 255)
        private String remark;

        public Long getToUserId() {
            return toUserId;
        }

        public void setToUserId(Long toUserId) {
            this.toUserId = toUserId;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }
    }

    public static class ReplyRequest {
        @NotBlank(message = "请填写回复内容")
        @Size(max = 5000)
        private String remark;
        @Size(max = 9)
        private List<String> imageKeys;
        private Boolean visibleToReporter;

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }

        public List<String> getImageKeys() {
            return imageKeys;
        }

        public void setImageKeys(List<String> imageKeys) {
            this.imageKeys = imageKeys;
        }

        public Boolean getVisibleToReporter() {
            return visibleToReporter;
        }

        public void setVisibleToReporter(Boolean visibleToReporter) {
            this.visibleToReporter = visibleToReporter;
        }
    }

    public static class RemarkRequest {
        @Size(max = 2000)
        private String remark;

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }
    }

    public static class RejectRequest {
        @NotBlank(message = "退回必须填写理由")
        @Size(max = 500)
        private String reason;

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
