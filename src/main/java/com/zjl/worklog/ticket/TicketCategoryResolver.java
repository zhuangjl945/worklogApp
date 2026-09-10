package com.zjl.worklog.ticket;

import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.work.entity.WorkCategory;

import java.util.List;

/**
 * 工单的问题类型最终要落到 work_record.category_id（库里 NOT NULL）。
 * 手机端允许「不指定」，渠道也可以不配默认分类，受理时不能把 null 写进工作记录。
 */
final class TicketCategoryResolver {

    private TicketCategoryResolver() {
    }

    /**
     * 解析顺序：报修人选择 → 渠道默认 → 科室第一个启用分类。
     * 选了但不属于本科室时视为注入，直接拒绝；三个来源都没有则给出可执行的错误，而不是 SQL 约束异常。
     */
    static Long resolve(Long requestedId, Long channelDefaultId, List<WorkCategory> enabled) {
        if (requestedId != null) {
            if (contains(enabled, requestedId)) {
                return requestedId;
            }
            throw new BizException(40001, "问题类型无效，请重新选择");
        }
        if (channelDefaultId != null && contains(enabled, channelDefaultId)) {
            return channelDefaultId;
        }
        if (enabled != null) {
            for (WorkCategory c : enabled) {
                if (c != null && c.getId() != null) {
                    return c.getId();
                }
            }
        }
        throw new BizException(40001, "本科室还没有可用的工作分类，无法生成工作记录。请先在「工作分类」里新增一项，并建议给登记渠道指定默认问题类型。");
    }

    /** 受理已有工单：历史单可能 category_id 为空或分类已停用，不能因「曾经选过」就拒绝受理。 */
    static Long resolveForAccept(Long ticketCategoryId, Long channelDefaultId, List<WorkCategory> enabled) {
        if (ticketCategoryId != null && contains(enabled, ticketCategoryId)) {
            return ticketCategoryId;
        }
        if (channelDefaultId != null && contains(enabled, channelDefaultId)) {
            return channelDefaultId;
        }
        if (enabled != null) {
            for (WorkCategory c : enabled) {
                if (c != null && c.getId() != null) {
                    return c.getId();
                }
            }
        }
        throw new BizException(40001, "本科室还没有可用的工作分类，无法受理。请先在「工作分类」里新增一项。");
    }

    private static boolean contains(List<WorkCategory> enabled, Long id) {
        if (enabled == null || id == null) {
            return false;
        }
        for (WorkCategory c : enabled) {
            if (c != null && id.equals(c.getId())) {
                return true;
            }
        }
        return false;
    }
}
