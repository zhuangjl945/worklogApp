package com.zjl.worklog.contract;

import com.zjl.worklog.contract.mapper.ContractMainMapper;
import com.zjl.worklog.contract.mapper.ContractPaymentPlanMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class ContractScheduler {

    private final ContractMainMapper contractMainMapper;
    private final ContractPaymentPlanMapper contractPaymentPlanMapper;

    public ContractScheduler(ContractMainMapper contractMainMapper, ContractPaymentPlanMapper contractPaymentPlanMapper) {
        this.contractMainMapper = contractMainMapper;
        this.contractPaymentPlanMapper = contractPaymentPlanMapper;
    }

    @Scheduled(cron = "0 0 1 * * ?")
    public void markExpiredContracts() {
        // 系统任务：updated_by 使用 0
        contractMainMapper.markExpiredBefore(LocalDate.now(), 0L);
    }

    @Scheduled(cron = "0 30 1 * * ?")
    public void markOverduePaymentPlans() {
        contractPaymentPlanMapper.markOverdueBefore(LocalDate.now());
    }
}
