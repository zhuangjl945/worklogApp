package com.zjl.worklog.work;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.UserContext;
import com.zjl.worklog.user.entity.UserEntity;
import com.zjl.worklog.user.mapper.UserMapper;
import com.zjl.worklog.work.entity.WorkRecord;
import com.zjl.worklog.work.entity.WorkRecordTransfer;
import com.zjl.worklog.work.mapper.WorkRecordMapper;
import com.zjl.worklog.work.mapper.WorkRecordTransferMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/work/transfers")
public class WorkRecordTransferController {

    private final WorkRecordTransferMapper transferMapper;
    private final WorkRecordMapper recordMapper;
    private final UserMapper userMapper;

    public WorkRecordTransferController(WorkRecordTransferMapper transferMapper, WorkRecordMapper recordMapper, UserMapper userMapper) {
        this.transferMapper = transferMapper;
        this.recordMapper = recordMapper;
        this.userMapper = userMapper;
    }

    @PostMapping("/records/{recordId}")
    public ApiResponse<Map<String, Long>> create(@PathVariable Long recordId, @Valid @RequestBody CreateTransferRequest req) {
        CurrentUser cu = requireLogin();

        if (req.getToUserId().equals(cu.getId())) {
            throw new BizException(40001, "不能转移给自己");
        }

        WorkRecord record = recordMapper.selectById(recordId, cu.getId());
        if (record == null) {
            throw new BizException(40001, "记录不存在或无权访问");
        }

        UserEntity toUser = userMapper.selectById(req.getToUserId());
        if (toUser == null || toUser.getStatus() == null || toUser.getStatus() != 1) {
            throw new BizException(40001, "接收人不存在或已禁用");
        }

        WorkRecordTransfer pending = transferMapper.selectPendingByRecordId(recordId);
        if (pending != null) {
            throw new BizException(40001, "该任务已有待处理的转移申请");
        }

        WorkRecordTransfer entity = new WorkRecordTransfer();
        entity.setWorkRecordId(recordId);
        entity.setFromUserId(cu.getId());
        entity.setToUserId(req.getToUserId());
        entity.setStatus(0);
        entity.setReason(req.getReason());
        transferMapper.insert(entity);

        return ApiResponse.ok(Map.of("id", entity.getId()));
    }

    @GetMapping("/pending")
    public ApiResponse<Map<String, Object>> pending(@RequestParam(required = false) Integer limit) {
        CurrentUser cu = requireLogin();
        Integer l = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
        long count = transferMapper.countPendingByToUserId(cu.getId());
        List<WorkRecordTransfer> records = transferMapper.selectPendingByToUserId(cu.getId(), l);
        return ApiResponse.ok(Map.of("count", count, "records", records));
    }

    @GetMapping("/initiated")
    public ApiResponse<List<WorkRecordTransfer>> initiated(@RequestParam(required = false) Integer limit) {
        CurrentUser cu = requireLogin();
        Integer l = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        return ApiResponse.ok(transferMapper.selectByFromUserId(cu.getId(), l));
    }

    @PostMapping("/{id}/accept")
    public ApiResponse<Boolean> accept(@PathVariable Long id) {
        CurrentUser cu = requireLogin();

        WorkRecordTransfer transfer = transferMapper.selectById(id);
        if (transfer == null) {
            throw new BizException(40001, "转移申请不存在");
        }
        if (!cu.getId().equals(transfer.getToUserId())) {
            throw new BizException(403, "无权处理该转移申请");
        }
        if (transfer.getStatus() == null || transfer.getStatus() != 0) {
            throw new BizException(40001, "转移申请已处理");
        }

        UserEntity toUser = userMapper.selectById(cu.getId());
        if (toUser == null || toUser.getDeptId() == null) {
            throw new BizException(40001, "接收人科室信息异常");
        }

        int updatedTransfer = transferMapper.updateStatus(id, cu.getId(), null, 0, 1, null);
        if (updatedTransfer == 0) {
            throw new BizException(40001, "转移申请状态已变化，请刷新后重试");
        }

        int updatedRecord = recordMapper.transferOwner(transfer.getWorkRecordId(), transfer.getFromUserId(), transfer.getToUserId(), toUser.getDeptId());
        if (updatedRecord == 0) {
            throw new BizException(40001, "任务转移失败：任务可能已被转移或删除");
        }

        return ApiResponse.ok(true);
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Boolean> reject(@PathVariable Long id, @Valid @RequestBody RejectTransferRequest req) {
        CurrentUser cu = requireLogin();

        WorkRecordTransfer transfer = transferMapper.selectById(id);
        if (transfer == null) {
            throw new BizException(40001, "转移申请不存在");
        }
        if (!cu.getId().equals(transfer.getToUserId())) {
            throw new BizException(403, "无权处理该转移申请");
        }
        if (transfer.getStatus() == null || transfer.getStatus() != 0) {
            throw new BizException(40001, "转移申请已处理");
        }

        int updatedTransfer = transferMapper.updateStatus(id, cu.getId(), null, 0, 2, req.getReply());
        if (updatedTransfer == 0) {
            throw new BizException(40001, "转移申请状态已变化，请刷新后重试");
        }

        return ApiResponse.ok(true);
    }

    private CurrentUser requireLogin() {
        CurrentUser cu = UserContext.get();
        if (cu == null) {
            throw new BizException(401, "未登录或登录已过期");
        }
        return cu;
    }

    public static class CreateTransferRequest {
        @NotNull
        private Long toUserId;
        private String reason;

        public Long getToUserId() {
            return toUserId;
        }

        public void setToUserId(Long toUserId) {
            this.toUserId = toUserId;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    public static class RejectTransferRequest {
        private String reply;

        public String getReply() {
            return reply;
        }

        public void setReply(String reply) {
            this.reply = reply;
        }
    }
}
