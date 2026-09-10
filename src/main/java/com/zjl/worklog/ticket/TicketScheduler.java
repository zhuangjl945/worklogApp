package com.zjl.worklog.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 工单侧的定时任务。
 *
 * <p>与 ContractScheduler 一样靠 {@code @EnableScheduling} 驱动，不需要额外开关。
 * 参数（是否自动确认、时限几小时）每轮都从 sys_config 现读，所以管理员在
 * 「参数配置 → 工单流转」页改完，最迟下一轮就生效，不用重启服务。
 *
 * <p>多实例部署可以同时开着：跃迁是带前置状态的 CAS，第二个实例只会拿到 0 影响行数然后跳过，
 * 既不会重复确认，也不会写出第二条 AUTO_CONFIRM 日志。
 */
@Component
public class TicketScheduler {

    private static final Logger log = LoggerFactory.getLogger(TicketScheduler.class);

    private final TicketService ticketService;

    public TicketScheduler(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /**
     * 报修人超时未确认的工单，由系统代为确认（20 -> 30）。
     *
     * <p>5 分钟一轮：业务时限以小时为单位，几分钟的误差不影响任何人的判断；
     * 再密只是白白扫库，服务重启后最多 5 分钟也能把积压补上。
     * 异常在这里吞掉并记日志：调度线程池默认单线程，让它往外抛没有额外收益，
     * 反而会把这轮之后该说的话淹没在堆栈里。
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void autoConfirmTimeoutTickets() {
        try {
            ticketService.autoConfirmExpiredTickets();
        } catch (Exception ex) {
            log.error("工单自动确认任务执行失败，等待下一轮重试", ex);
        }
    }
}
