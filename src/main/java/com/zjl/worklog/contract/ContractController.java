package com.zjl.worklog.contract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.contract.entity.ContractMainEntity;
import com.zjl.worklog.contract.entity.ContractPaymentPlanEntity;
import com.zjl.worklog.contract.mapper.ContractMainMapper;
import com.zjl.worklog.contract.mapper.ContractPaymentPlanMapper;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Validated
@RestController
@RequestMapping("/api")
public class ContractController {

    private final ContractMainMapper contractMainMapper;
    private final ContractPaymentPlanMapper contractPaymentPlanMapper;
    private final ObjectMapper objectMapper;

    public ContractController(ContractMainMapper contractMainMapper,
                              ContractPaymentPlanMapper contractPaymentPlanMapper,
                              ObjectMapper objectMapper) {
        this.contractMainMapper = contractMainMapper;
        this.contractPaymentPlanMapper = contractPaymentPlanMapper;
        this.objectMapper = objectMapper;
    }

    // --- 合同接口 ---

    @PostMapping("/contract")
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody CreateContractRequest req) {
        checkContractNoUnique(req.getContractNo(), null);
        CurrentUser cu = getCurrentUser();

        // 后端二次校验：合同科室强制归属当前用户科室，不信任前端传入
        if (cu.getDeptId() == null) {
            throw new BizException(40300, "当前用户未分配科室，无法创建合同");
        }

        ContractMainEntity entity = new ContractMainEntity();
        copyProperties(req, entity);
        entity.setDeptId(cu.getDeptId());
        entity.setStatus(10); // 草稿
        entity.setCreatedBy(cu.getId());
        entity.setUpdatedBy(cu.getId());

        contractMainMapper.insert(entity);
        return ApiResponse.ok(Collections.singletonMap("id", entity.getId()));
    }

    @PutMapping("/contract/{id}")
    public ApiResponse<Boolean> update(@PathVariable Long id, @Valid @RequestBody UpdateContractRequest req) {
        ContractMainEntity existed = getAndCheckContract(id);
        if (existed.getStatus() != 10) {
            throw new BizException(40001, "仅草稿状态合同可编辑");
        }
        checkContractNoUnique(req.getContractNo(), id);
        CurrentUser cu = getCurrentUser();

        copyProperties(req, existed);
        existed.setUpdatedBy(cu.getId());

        contractMainMapper.update(existed);
        return ApiResponse.ok(true);
    }

    @PutMapping("/contract/{id}/attachments")
    public ApiResponse<Boolean> updateAttachments(@PathVariable Long id, @Valid @RequestBody UpdateAttachmentsRequest req) {
        ContractMainEntity existed = getAndCheckContract(id);
        if (existed.getStatus() != 30) {
            throw new BizException(40001, "仅执行中合同可调整附件");
        }
        CurrentUser cu = getCurrentUser();

        existed.setContractFileUrl(req.getContractFileUrl());
        existed.setUpdatedBy(cu.getId());

        contractMainMapper.update(existed);
        return ApiResponse.ok(true);
    }

    @GetMapping("/contract/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        ContractMainEntity contract = getAndCheckContract(id);
        List<ContractPaymentPlanEntity> plans = contractPaymentPlanMapper.selectByContractId(id);
        
        Map<String, Object> res = new HashMap<>();
        res.put("contract", contract);
        res.put("paymentPlans", plans);

        // 如果是续签合同，补充来源合同编号
        if (contract.getRenewFromId() != null) {
            ContractMainEntity fromContract = contractMainMapper.selectById(contract.getRenewFromId());
            if (fromContract != null) {
                res.put("renewFromNo", fromContract.getContractNo());
            }
        }
        return ApiResponse.ok(res);
    }

    @GetMapping("/contract/list")
    public ApiResponse<PageResponse<ContractMainEntity>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String contractNo,
            @RequestParam(required = false) String contractName,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer contractDirection,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Long managerId,
            @RequestParam(required = false) Long deptId
    ) {
        if (page < 1) page = 1;
        if (size < 1) size = 10;

        // 后端二次校验：强制使用当前用户的科室ID
        CurrentUser cu = getCurrentUser();
        Long effectiveDeptId = cu.getDeptId() != null ? cu.getDeptId() : deptId;

        long total = contractMainMapper.count(contractNo, contractName, status, contractDirection, supplierId, managerId, effectiveDeptId);
        long offset = (page - 1) * size;

        List<ContractMainEntity> records = total == 0 ? List.of() : 
            contractMainMapper.selectPage(offset, size, contractNo, contractName, status, contractDirection, supplierId, managerId, effectiveDeptId);
        
        return ApiResponse.ok(PageResponse.of(page, size, total, records));
    }

    @DeleteMapping("/contract/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        ContractMainEntity existed = getAndCheckContract(id);
        if (existed.getStatus() != 10) {
            throw new BizException(40001, "仅草稿状态合同可删除");
        }
        contractMainMapper.markDeleted(id, getCurrentUser().getId());
        return ApiResponse.ok(true);
    }

    @Transactional
    @PostMapping("/contract/{id}/start")
    public ApiResponse<Boolean> start(@PathVariable Long id) {
        ContractMainEntity contract = getAndCheckContract(id);
        if (contract.getStatus() != 10) {
            throw new BizException(40001, "合同状态非草稿，无法启动");
        }

        // 更新合同状态
        contractMainMapper.updateStatus(id, 30, getCurrentUser().getId());

        // 解析 paymentSchedule 生成付款计划
        if (contract.getPaymentSchedule() != null && !contract.getPaymentSchedule().isBlank()) {
            try {
                List<PaymentPlanItem> items = objectMapper.readValue(contract.getPaymentSchedule(), new TypeReference<>() {});
                if (!items.isEmpty()) {
                    List<ContractPaymentPlanEntity> plans = new ArrayList<>();
                    for (PaymentPlanItem item : items) {
                        ContractPaymentPlanEntity p = new ContractPaymentPlanEntity();
                        p.setContractId(id);
                        p.setPlanNo(item.getPlanNo());
                        p.setPlanType(contract.getContractDirection()); // 合同方向即款项类型
                        p.setPlanAmount(item.getPlanAmount());
                        p.setPlanDate(item.getPlanDate());
                        p.setStatus(10); // 待处理
                        plans.add(p);
                    }
                    contractPaymentPlanMapper.insertBatch(plans);
                }
            } catch (Exception e) {
                throw new BizException(50000, "合同付款计划解析失败");
            }
        }

        return ApiResponse.ok(true);
    }

    @PostMapping("/contract/{id}/complete")
    public ApiResponse<Boolean> complete(@PathVariable Long id) {
        getAndCheckContract(id);
        contractMainMapper.updateStatus(id, 40, getCurrentUser().getId());
        return ApiResponse.ok(true);
    }

    @PostMapping("/contract/{id}/terminate")
    public ApiResponse<Boolean> terminate(@PathVariable Long id) {
        getAndCheckContract(id);
        contractMainMapper.updateStatus(id, 50, getCurrentUser().getId());
        return ApiResponse.ok(true);
    }

    /**
     * 合同续签：基于原合同复制生成一份新合同（草稿），合同编号自动加后缀。
     * 规则：复制全部字段后可编辑；任何状态都允许续签入口。
     */
    @Transactional
    @PostMapping("/contract/{id}/renew")
    public ApiResponse<Map<String, Long>> renew(@PathVariable Long id) {
        ContractMainEntity origin = getAndCheckContract(id);
        CurrentUser cu = getCurrentUser();

        String newContractNo = generateRenewContractNo(origin);
        checkContractNoUnique(newContractNo, null);

        ContractMainEntity renewed = new ContractMainEntity();
        // 复制全部业务字段
        renewed.setContractNo(newContractNo);
        renewed.setContractName(origin.getContractName());
        renewed.setContractType(origin.getContractType());
        renewed.setContractDirection(origin.getContractDirection());
        renewed.setCustomerId(origin.getCustomerId());
        renewed.setCustomerName(origin.getCustomerName());
        renewed.setSupplierId(origin.getSupplierId());
        renewed.setSupplierName(origin.getSupplierName());
        renewed.setContractAmount(origin.getContractAmount());
        renewed.setStartDate(origin.getStartDate());
        renewed.setEndDate(origin.getEndDate());
        renewed.setSignDate(origin.getSignDate());
        renewed.setPaymentTerms(origin.getPaymentTerms());
        renewed.setPaymentSchedule(origin.getPaymentSchedule());
        renewed.setContractFileUrl(origin.getContractFileUrl());
        renewed.setDeptId(origin.getDeptId());
        renewed.setManagerId(origin.getManagerId());

        // 续签信息
        renewed.setRenewFromId(origin.getId());
        int originCount = origin.getRenewCount() == null ? 0 : origin.getRenewCount();
        renewed.setRenewCount(originCount + 1);

        // 新合同状态与审计字段
        renewed.setStatus(10); // 草稿
        renewed.setCreatedBy(cu.getId());
        renewed.setUpdatedBy(cu.getId());

        contractMainMapper.insert(renewed);

        // 可选：更新来源合同续签次数（便于列表展示）
        origin.setRenewCount(originCount + 1);
        origin.setUpdatedBy(cu.getId());
        contractMainMapper.update(origin);

        return ApiResponse.ok(Collections.singletonMap("id", renewed.getId()));
    }

    @GetMapping("/contract/expiring")
    public ApiResponse<List<ContractMainEntity>> expiring(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "30") Integer status) {
        // 后端二次校验：强制使用当前用户的科室ID
        CurrentUser cu = getCurrentUser();
        return ApiResponse.ok(contractMainMapper.selectExpiringWithDept(days, status, cu.getDeptId()));
    }

    @GetMapping("/payment-plan/expiring")
    public ApiResponse<List<ContractPaymentPlanEntity>> expiringPayments(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "10") Integer status) {
        // 后端二次校验：强制使用当前用户的科室ID
        CurrentUser cu = getCurrentUser();
        return ApiResponse.ok(contractPaymentPlanMapper.selectExpiringWithDept(days, status, cu.getDeptId()));
    }

    // --- 付款接口 ---

    @GetMapping("/contract/{id}/payment-plans")
    public ApiResponse<List<ContractPaymentPlanEntity>> getPaymentPlans(@PathVariable Long id) {
        return ApiResponse.ok(contractPaymentPlanMapper.selectByContractId(id));
    }

    @Transactional
    @PostMapping("/payment-plan/{id}/record")
    public ApiResponse<Boolean> recordPayment(@PathVariable Long id, @Valid @RequestBody RecordPaymentRequest req) {
        ContractPaymentPlanEntity plan = contractPaymentPlanMapper.selectById(id);
        if (plan == null) throw new BizException(40001, "付款计划不存在");
        if (plan.getStatus() == 20) throw new BizException(40001, "该款项已登记，请勿重复操作");

        // 后端二次校验：校验付款计划对应合同的科室权限
        ContractMainEntity contract = getAndCheckContract(plan.getContractId());

        // 校验累计金额
        BigDecimal currentSum = contractPaymentPlanMapper.sumActualAmountByContractId(plan.getContractId());
        if (currentSum.add(req.getActualAmount()).compareTo(contract.getContractAmount()) > 0) {
            throw new BizException(40001, "累计付款金额超过合同总金额");
        }

        plan.setActualDate(req.getActualDate());
        plan.setActualAmount(req.getActualAmount());
        plan.setVoucherNo(req.getVoucherNo());
        plan.setPayMethod(req.getPayMethod());
        plan.setRemark(req.getRemark());
        plan.setRecordedBy(getCurrentUser().getId());
        plan.setRecordedTime(LocalDateTime.now());
        plan.setStatus(20); // 已付款/已收款

        contractPaymentPlanMapper.updateRecord(plan);
        return ApiResponse.ok(true);
    }

    @PutMapping("/payment-plan/{id}")
    public ApiResponse<Boolean> updatePaymentRecord(@PathVariable Long id, @Valid @RequestBody RecordPaymentRequest req) {
        ContractPaymentPlanEntity plan = contractPaymentPlanMapper.selectById(id);
        if (plan == null) throw new BizException(40001, "付款计划不存在");

        // 后端二次校验：校验付款计划对应合同的科室权限
        getAndCheckContract(plan.getContractId());
        
        plan.setActualDate(req.getActualDate());
        plan.setActualAmount(req.getActualAmount());
        plan.setVoucherNo(req.getVoucherNo());
        plan.setPayMethod(req.getPayMethod());
        plan.setRemark(req.getRemark());
        plan.setRecordedBy(getCurrentUser().getId());
        plan.setRecordedTime(LocalDateTime.now());

        contractPaymentPlanMapper.updateRecord(plan);
        return ApiResponse.ok(true);
    }

    // --- 私有辅助方法 ---

    private CurrentUser getCurrentUser() {
        CurrentUser cu = UserContext.get();
        if (cu == null) throw new BizException(40100, "未登录");
        return cu;
    }

    private ContractMainEntity getAndCheckContract(Long id) {
        ContractMainEntity entity = contractMainMapper.selectById(id);
        if (entity == null) throw new BizException(40001, "合同不存在");

        CurrentUser cu = getCurrentUser();
        if (cu.getDeptId() != null && entity.getDeptId() != null && !cu.getDeptId().equals(entity.getDeptId())) {
            throw new BizException(40300, "无权操作其他科室的合同");
        }

        return entity;
    }

    private void checkContractNoUnique(String contractNo, Long excludeId) {
        ContractMainEntity existed = contractMainMapper.selectByContractNo(contractNo);
        if (existed != null && !existed.getId().equals(excludeId)) {
            throw new BizException(40001, "合同编号 " + contractNo + " 已存在");
        }
    }

    private String generateRenewContractNo(ContractMainEntity origin) {
        String baseNo = origin.getContractNo();
        // 尝试匹配已有后缀如 -01, -02 等，如果匹配成功则移除它，取前面的作为 baseNo
        if (baseNo.matches(".*-\\d{2}$")) {
            baseNo = baseNo.substring(0, baseNo.length() - 3);
        }
        
        int next = (origin.getRenewCount() == null ? 0 : origin.getRenewCount()) + 1;
        String newNo;
        while (true) {
            newNo = String.format("%s-%02d", baseNo, next);
            // 查询数据库确认该编号是否已存在
            if (contractMainMapper.selectByContractNo(newNo) == null) {
                break;
            }
            next++; // 如果已存在，序号继续往后递增
        }
        return newNo;
    }

    private void copyProperties(BaseContractRequest src, ContractMainEntity dest) {
        dest.setContractNo(src.getContractNo());
        dest.setContractName(src.getContractName());
        dest.setContractType(src.getContractType());
        dest.setContractDirection(src.getContractDirection());
        dest.setCustomerId(src.getCustomerId());
        dest.setCustomerName(src.getCustomerName());
        dest.setSupplierId(src.getSupplierId());
        dest.setSupplierName(src.getSupplierName());
        dest.setContractAmount(src.getContractAmount());
        dest.setStartDate(src.getStartDate());
        dest.setEndDate(src.getEndDate());
        dest.setSignDate(src.getSignDate());
        dest.setPaymentTerms(src.getPaymentTerms());
        dest.setContractFileUrl(src.getContractFileUrl());
        dest.setDeptId(src.getDeptId());
        dest.setManagerId(src.getManagerId());
        try {
            dest.setPaymentSchedule(objectMapper.writeValueAsString(src.getPaymentSchedule()));
        } catch (Exception e) {
            dest.setPaymentSchedule("[]");
        }
    }

    // --- DTO ---

    @Data
    public static class UpdateAttachmentsRequest {
        private String contractFileUrl;
    }

    @Data
    public static class BaseContractRequest {
        @NotBlank(message = "合同编号不能为空")
        private String contractNo;
        @NotBlank(message = "合同名称不能为空")
        private String contractName;
        @NotNull
        private Integer contractType;
        @NotNull
        private Integer contractDirection;
        private Long customerId;
        private String customerName;
        private Long supplierId;
        private String supplierName;
        @NotNull
        private BigDecimal contractAmount;
        @NotNull
        private LocalDate startDate;
        @NotNull
        private LocalDate endDate;
        private LocalDate signDate;
        private Integer paymentTerms;
        private List<PaymentPlanItem> paymentSchedule;
        private String contractFileUrl;
        private Long deptId;
        private Long managerId;

        public String getContractFileUrl() {
            return contractFileUrl;
        }

        public void setContractFileUrl(String contractFileUrl) {
            this.contractFileUrl = contractFileUrl;
        }
    }

    public static class CreateContractRequest extends BaseContractRequest {}
    public static class UpdateContractRequest extends BaseContractRequest {}

    @Data
    public static class PaymentPlanItem {
        private String planNo;
        private BigDecimal planAmount;
        private LocalDate planDate;
    }

    @Data
    public static class RecordPaymentRequest {
        @NotNull
        private LocalDate actualDate;
        @NotNull
        private BigDecimal actualAmount;
        private String voucherNo;
        private String payMethod;
        private String remark;
    }
}
