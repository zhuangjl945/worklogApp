package com.zjl.worklog.ticket;

import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.ticket.entity.TicketChannelEntity;
import com.zjl.worklog.ticket.mapper.TicketChannelMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 渠道解析：公开接口唯一获取渠道信息的地方。
 *
 * <p>渠道码本身就是能力凭证（谁扫都能报修），真正的防护在
 * TicketFormTokenService（一次填表一令牌）+ TicketRateLimiter（多维限流）+ 渠道当日配额。
 */
@Service
public class TicketChannelService {

    private final TicketChannelMapper channelMapper;

    public TicketChannelService(TicketChannelMapper channelMapper) {
        this.channelMapper = channelMapper;
    }

    /** 按渠道码取启用中的渠道；不存在或已停用一律同一个提示，不给脚本探测信号 */
    public TicketChannelEntity requireEnabled(String channelCode) {
        if (!StringUtils.hasText(channelCode)) {
            throw new BizException(40301, "登记链接无效，请重新扫码");
        }
        TicketChannelEntity channel = channelMapper.selectByCode(channelCode.trim());
        if (channel == null || channel.getStatus() == null || channel.getStatus() != 1) {
            throw new BizException(40301, "登记链接无效或已停用，请联系科室确认");
        }
        return channel;
    }

    /** 按 form token 里记录的渠道ID 取启用中的渠道（提交时以此为准，绝不信任客户端传来的渠道码） */
    public TicketChannelEntity requireEnabledById(Long channelId) {
        TicketChannelEntity channel = channelId == null ? null : channelMapper.selectById(channelId);
        if (channel == null || channel.getStatus() == null || channel.getStatus() != 1) {
            throw new BizException(40301, "登记入口已停用，请重新扫码");
        }
        return channel;
    }

    public TicketChannelEntity requireExists(Long id) {
        TicketChannelEntity channel = id == null ? null : channelMapper.selectById(id);
        if (channel == null) {
            throw new BizException(40001, "渠道不存在");
        }
        return channel;
    }
}
