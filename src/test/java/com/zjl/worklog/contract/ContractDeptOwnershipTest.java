package com.zjl.worklog.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjl.worklog.contract.entity.ContractMainEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 草稿合同的科室归属。创建时由接口强制写成当前用户科室；
 * 更新时请求体里的 deptId 不能把合同改挂到别的科室。
 */
class ContractDeptOwnershipTest {

    @Test
    @DisplayName("草稿更新丢掉请求里的科室，其他字段照常写入")
    void draftUpdateDropsRequestedDepartment() {
        ContractController controller = new ContractController(null, null, new ObjectMapper());
        ContractMainEntity existed = new ContractMainEntity();
        existed.setDeptId(7L);

        ContractController.UpdateContractRequest req = request(99L);
        controller.applyDraftFields(existed, req);

        assertEquals(7L, existed.getDeptId());
        assertEquals("HT-1", existed.getContractNo());
        assertEquals("维保", existed.getContractName());
    }

    @Test
    @DisplayName("原来没有科室时，也不能靠更新请求补上一个别的科室")
    void draftUpdateDoesNotAdoptDepartmentWhenMissing() {
        ContractController controller = new ContractController(null, null, new ObjectMapper());
        ContractMainEntity existed = new ContractMainEntity();

        controller.applyDraftFields(existed, request(99L));

        assertNull(existed.getDeptId());
    }

    private static ContractController.UpdateContractRequest request(Long deptId) {
        ContractController.UpdateContractRequest req = new ContractController.UpdateContractRequest();
        req.setContractNo("HT-1");
        req.setContractName("维保");
        req.setContractType(1);
        req.setContractDirection(1);
        req.setContractAmount(BigDecimal.ONE);
        req.setStartDate(LocalDate.of(2026, 1, 1));
        req.setEndDate(LocalDate.of(2026, 12, 31));
        req.setDeptId(deptId);
        return req;
    }
}
