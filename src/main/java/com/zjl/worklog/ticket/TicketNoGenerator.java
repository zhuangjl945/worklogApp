package com.zjl.worklog.ticket;

import org.springframework.stereotype.Component;

/**
 * 对外单号生成：ST + 定长流水号，从 ST100001 开始往上加。
 *
 * <p>为什么不再用「ST + yyyyMMdd + 当日序号」：报修人要把单号念给同事、抄在纸上、
 * 在手机键盘上一个字符一个字符敲，14 位的日期号既难背又容易抄错一位。
 * 短流水号 8 个字符就够，纯数字不含 1/O、0/D 这类歧义。
 *
 * <p>号源直接取工单自增 ID 加基数（100000 + id），不再数「今天已经有几条」：
 * 全局流水用 count+1 每天都会撞 uk_ticket_no，并发高时重试三轮还撞就得让报修人重填。
 * 自增 ID 天生唯一，因此这里的 format() 不可能重复，也就不需要重试。
 *
 * <p>代价是单号变成可预测的（知道 ST100001 就猜得出 ST100002）。这是有意的取舍：
 * 可预测的号 + 每单不同的 6 位查询密码 = 仍然要猜中密码才能读到别人的报修内容。
 * 反过来说，绝不能把查询密码做成「不设置」或「全员统一」，那样这两个凭证一起归零，
 * 查询接口就成了全库遍历接口。密码规则见 {@link TicketQueryCode}。
 */
@Component
public class TicketNoGenerator {

    private static final String PREFIX = "ST";

    /** 号段基数：第一条工单（id=1）拿到 ST100001，也保证单号至少 6 位数字、长度稳定 */
    private static final long BASE = 100_000L;

    /** 按工单自增 ID 算出对外单号；id 为空说明插入没回填主键，属于装配错误，直接抛出去 */
    public String format(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalStateException("生成单号失败：工单主键未回填");
        }
        return PREFIX + (BASE + id);
    }

    /**
     * 给蜜罐用的假单号：形状与真号完全一致（ST + 6 位数字）。
     * 机器人拿到的号如果格式明显不同，就等于告诉它「你被识别出来了」。
     */
    public String fakeNo() {
        return PREFIX + TicketQueryCode.randomDigits();
    }
}