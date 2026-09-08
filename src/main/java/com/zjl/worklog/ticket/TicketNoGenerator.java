package com.zjl.worklog.ticket;

import com.zjl.worklog.ticket.mapper.ServiceTicketMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 对外单号生成：ST + yyyyMMdd + 6 位当日序号。
 *
 * <p>用「当天已有条数 + 1」而不是 MAX(id)+1，并在插入撞唯一键时由调用方重试；
 * 报修人只需要记住单号，不需要知道数据库主键，也便于将来按天分表。
 */
@Component
public class TicketNoGenerator {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String PREFIX = "ST";

    private final ServiceTicketMapper ticketMapper;

    public TicketNoGenerator(ServiceTicketMapper ticketMapper) {
        this.ticketMapper = ticketMapper;
    }

    /** 生成一个候选单号；唯一性最终由 uk_ticket_no 保证 */
    public String next() {
        String dayPrefix = PREFIX + LocalDate.now().format(DAY);
        long seq = ticketMapper.countByNoPrefix(dayPrefix) + 1;
        return dayPrefix + String.format("%06d", seq);
    }
}
