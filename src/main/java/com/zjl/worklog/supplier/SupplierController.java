package com.zjl.worklog.supplier;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.UserContext;
import com.zjl.worklog.supplier.entity.SupplierContactEntity;
import com.zjl.worklog.supplier.entity.SupplierMainEntity;
import com.zjl.worklog.supplier.mapper.SupplierContactMapper;
import com.zjl.worklog.supplier.mapper.SupplierMainMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;


@Validated
@RestController
@RequestMapping("/api/supplier")
public class SupplierController {

    private static final String SUPPLIER_CODE_PREFIX = "SUP";
    private static final int SUPPLIER_CODE_SEQ_LEN = 6;

    private final SupplierMainMapper supplierMainMapper;
    private final SupplierContactMapper supplierContactMapper;

    public SupplierController(SupplierMainMapper supplierMainMapper, SupplierContactMapper supplierContactMapper) {
        this.supplierMainMapper = supplierMainMapper;
        this.supplierContactMapper = supplierContactMapper;
    }

    @GetMapping("/next-code")
    public ApiResponse<Map<String, String>> nextCode() {
        String max = supplierMainMapper.selectMaxSupplierCode();
        String next = nextSupplierCode(max);
        return ApiResponse.ok(Collections.singletonMap("supplierCode", next));
    }

    @PostMapping
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody CreateSupplierRequest req) {
        String supplierCode = req.getSupplierCode();
        if (supplierCode == null || supplierCode.isBlank()) {
            supplierCode = nextSupplierCode(supplierMainMapper.selectMaxSupplierCode());
        }

        SupplierMainEntity existed = supplierMainMapper.selectBySupplierCode(supplierCode);
        if (existed != null) {
            throw new BizException(40001, "supplierCode已存在");
        }

        CurrentUser cu = UserContext.get();
        if (cu == null) {
            throw new BizException(40100, "未登录");
        }

        SupplierMainEntity entity = new SupplierMainEntity();
        entity.setSupplierCode(supplierCode);
        entity.setSupplierName(req.getSupplierName());
        entity.setSupplierShortName(req.getSupplierShortName());
        entity.setCreditCode(req.getCreditCode());
        entity.setLegalRepresentative(req.getLegalRepresentative());
        entity.setContactAddress(req.getContactAddress());
        entity.setContactPhone(req.getContactPhone());
        entity.setBankName(req.getBankName());
        entity.setBankAccount(req.getBankAccount());
        entity.setAccountName(req.getAccountName());
        entity.setCreatedBy(cu.getId());
        entity.setUpdatedBy(cu.getId());

        supplierMainMapper.insert(entity);
        return ApiResponse.ok(Collections.singletonMap("id", entity.getId()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Boolean> update(@PathVariable Long id, @Valid @RequestBody UpdateSupplierRequest req) {
        SupplierMainEntity existed = supplierMainMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "供应商不存在");
        }

        if (req.getSupplierCode() != null && !req.getSupplierCode().isBlank()) {
            SupplierMainEntity byCode = supplierMainMapper.selectBySupplierCode(req.getSupplierCode());
            if (byCode != null && !byCode.getId().equals(id)) {
                throw new BizException(40001, "supplierCode已存在");
            }
        }

        CurrentUser cu = UserContext.get();
        if (cu == null) {
            throw new BizException(40100, "未登录");
        }

        SupplierMainEntity update = new SupplierMainEntity();
        update.setId(id);
        update.setSupplierCode(req.getSupplierCode());
        update.setSupplierName(req.getSupplierName());
        update.setSupplierShortName(req.getSupplierShortName());
        update.setCreditCode(req.getCreditCode());
        update.setLegalRepresentative(req.getLegalRepresentative());
        update.setContactAddress(req.getContactAddress());
        update.setContactPhone(req.getContactPhone());
        update.setBankName(req.getBankName());
        update.setBankAccount(req.getBankAccount());
        update.setAccountName(req.getAccountName());
        update.setUpdatedBy(cu.getId());

        supplierMainMapper.update(update);
        return ApiResponse.ok(true);
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierMainEntity> detail(@PathVariable Long id) {
        SupplierMainEntity entity = supplierMainMapper.selectById(id);
        if (entity == null) {
            throw new BizException(40001, "供应商不存在");
        }
        return ApiResponse.ok(entity);
    }

    private String nextSupplierCode(String maxCode) {
        long nextSeq = 1;
        if (maxCode != null && maxCode.startsWith(SUPPLIER_CODE_PREFIX)) {
            try {
                String seqStr = maxCode.substring(SUPPLIER_CODE_PREFIX.length());
                nextSeq = Long.parseLong(seqStr) + 1;
            } catch (NumberFormatException e) {
                // 如果格式不对，从1开始
            }
        }
        return String.format("%s%0" + SUPPLIER_CODE_SEQ_LEN + "d", SUPPLIER_CODE_PREFIX, nextSeq);
    }

    @GetMapping("/list")
    public ApiResponse<PageResponse<SupplierMainEntity>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String supplierCode,
            @RequestParam(required = false) String supplierName,
            @RequestParam(required = false) Integer deleted
    ) {
        if (page < 1) page = 1;
        if (size < 1) size = 10;

        long total = supplierMainMapper.count(supplierCode, supplierName, deleted);
        long offset = (page - 1) * size;

        List<SupplierMainEntity> records = total == 0 ? List.of() : supplierMainMapper.selectPage(offset, size, supplierCode, supplierName, deleted);
        return ApiResponse.ok(PageResponse.of(page, size, total, records));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        SupplierMainEntity existed = supplierMainMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "供应商不存在");
        }

        CurrentUser cu = UserContext.get();
        if (cu == null) {
            throw new BizException(40100, "未登录");
        }

        supplierMainMapper.disableById(id, cu.getId());
        return ApiResponse.ok(true);
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<Boolean> enable(@PathVariable Long id) {
        SupplierMainEntity existed = supplierMainMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "供应商不存在");
        }

        CurrentUser cu = UserContext.get();
        if (cu == null) {
            throw new BizException(40100, "未登录");
        }

        supplierMainMapper.enableById(id, cu.getId());
        return ApiResponse.ok(true);
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<Boolean> disable(@PathVariable Long id) {
        SupplierMainEntity existed = supplierMainMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "供应商不存在");
        }

        CurrentUser cu = UserContext.get();
        if (cu == null) {
            throw new BizException(40100, "未登录");
        }

        supplierMainMapper.disableById(id, cu.getId());
        return ApiResponse.ok(true);
    }

    @GetMapping("/{id}/contacts")
    public ApiResponse<List<SupplierContactEntity>> contacts(@PathVariable Long id) {
        SupplierMainEntity existed = supplierMainMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "供应商不存在");
        }
        return ApiResponse.ok(supplierContactMapper.selectBySupplierId(id));
    }

    @PostMapping("/{id}/contacts")
    public ApiResponse<Map<String, Long>> addContact(@PathVariable Long id, @Valid @RequestBody CreateContactRequest req) {
        SupplierMainEntity existed = supplierMainMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "供应商不存在");
        }

        SupplierContactEntity entity = new SupplierContactEntity();
        entity.setSupplierId(id);
        entity.setContactName(req.getContactName());
        entity.setContactPosition(req.getContactPosition());
        entity.setContactPhone(req.getContactPhone());
        entity.setContactEmail(req.getContactEmail());
        entity.setIsPrimary(req.getIsPrimary() == null ? 0 : req.getIsPrimary());

        supplierContactMapper.insert(entity);
        return ApiResponse.ok(Collections.singletonMap("id", entity.getId()));
    }

    @DeleteMapping("/{id}/contacts/{cid}")
    public ApiResponse<Boolean> deleteContact(@PathVariable Long id, @PathVariable Long cid) {
        SupplierMainEntity existed = supplierMainMapper.selectById(id);
        if (existed == null) {
            throw new BizException(40001, "供应商不存在");
        }
        
        List<SupplierContactEntity> contacts = supplierContactMapper.selectBySupplierId(id);
        boolean belongsToSupplier = contacts.stream().anyMatch(c -> c.getId().equals(cid));
        if (!belongsToSupplier) {
            throw new BizException(40001, "联系人不属于该供应商");
        }

        supplierContactMapper.deleteById(cid);
        return ApiResponse.ok(true);
    }

    public static class CreateSupplierRequest {
        @NotBlank
        private String supplierCode;
        @NotBlank
        private String supplierName;

        private String supplierShortName;
        private String creditCode;
        private String legalRepresentative;
        private String contactAddress;
        private String contactPhone;
        private String bankName;
        private String bankAccount;
        private String accountName;

        public String getSupplierCode() {
            return supplierCode;
        }

        public void setSupplierCode(String supplierCode) {
            this.supplierCode = supplierCode;
        }

        public String getSupplierName() {
            return supplierName;
        }

        public void setSupplierName(String supplierName) {
            this.supplierName = supplierName;
        }

        public String getSupplierShortName() {
            return supplierShortName;
        }

        public void setSupplierShortName(String supplierShortName) {
            this.supplierShortName = supplierShortName;
        }

        public String getCreditCode() {
            return creditCode;
        }

        public void setCreditCode(String creditCode) {
            this.creditCode = creditCode;
        }

        public String getLegalRepresentative() {
            return legalRepresentative;
        }

        public void setLegalRepresentative(String legalRepresentative) {
            this.legalRepresentative = legalRepresentative;
        }

        public String getContactAddress() {
            return contactAddress;
        }

        public void setContactAddress(String contactAddress) {
            this.contactAddress = contactAddress;
        }

        public String getContactPhone() {
            return contactPhone;
        }

        public void setContactPhone(String contactPhone) {
            this.contactPhone = contactPhone;
        }

        public String getBankName() {
            return bankName;
        }

        public void setBankName(String bankName) {
            this.bankName = bankName;
        }

        public String getBankAccount() {
            return bankAccount;
        }

        public void setBankAccount(String bankAccount) {
            this.bankAccount = bankAccount;
        }

        public String getAccountName() {
            return accountName;
        }

        public void setAccountName(String accountName) {
            this.accountName = accountName;
        }
    }

    public static class UpdateSupplierRequest {
        private String supplierCode;
        private String supplierName;
        private String supplierShortName;
        private String creditCode;
        private String legalRepresentative;
        private String contactAddress;
        private String contactPhone;
        private String bankName;
        private String bankAccount;
        private String accountName;

        public String getSupplierCode() {
            return supplierCode;
        }

        public void setSupplierCode(String supplierCode) {
            this.supplierCode = supplierCode;
        }

        public String getSupplierName() {
            return supplierName;
        }

        public void setSupplierName(String supplierName) {
            this.supplierName = supplierName;
        }

        public String getSupplierShortName() {
            return supplierShortName;
        }

        public void setSupplierShortName(String supplierShortName) {
            this.supplierShortName = supplierShortName;
        }

        public String getCreditCode() {
            return creditCode;
        }

        public void setCreditCode(String creditCode) {
            this.creditCode = creditCode;
        }

        public String getLegalRepresentative() {
            return legalRepresentative;
        }

        public void setLegalRepresentative(String legalRepresentative) {
            this.legalRepresentative = legalRepresentative;
        }

        public String getContactAddress() {
            return contactAddress;
        }

        public void setContactAddress(String contactAddress) {
            this.contactAddress = contactAddress;
        }

        public String getContactPhone() {
            return contactPhone;
        }

        public void setContactPhone(String contactPhone) {
            this.contactPhone = contactPhone;
        }

        public String getBankName() {
            return bankName;
        }

        public void setBankName(String bankName) {
            this.bankName = bankName;
        }

        public String getBankAccount() {
            return bankAccount;
        }

        public void setBankAccount(String bankAccount) {
            this.bankAccount = bankAccount;
        }

        public String getAccountName() {
            return accountName;
        }

        public void setAccountName(String accountName) {
            this.accountName = accountName;
        }
    }

    public static class CreateContactRequest {
        @NotBlank
        private String contactName;
        private String contactPosition;
        private String contactPhone;
        private String contactEmail;
        private Integer isPrimary;

        public String getContactName() {
            return contactName;
        }

        public void setContactName(String contactName) {
            this.contactName = contactName;
        }

        public String getContactPosition() {
            return contactPosition;
        }

        public void setContactPosition(String contactPosition) {
            this.contactPosition = contactPosition;
        }

        public String getContactPhone() {
            return contactPhone;
        }

        public void setContactPhone(String contactPhone) {
            this.contactPhone = contactPhone;
        }

        public String getContactEmail() {
            return contactEmail;
        }

        public void setContactEmail(String contactEmail) {
            this.contactEmail = contactEmail;
        }

        public Integer getIsPrimary() {
            return isPrimary;
        }

        public void setIsPrimary(Integer isPrimary) {
            this.isPrimary = isPrimary;
        }
    }
}
