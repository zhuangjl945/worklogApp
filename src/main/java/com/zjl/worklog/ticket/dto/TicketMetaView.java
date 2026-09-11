package com.zjl.worklog.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 手机端表单初始化数据：渠道名 + 可选问题类型 + 必填规则。
 * 只回展示需要的字段，不回 deptId、不回 sign_secret、不回分类模板 JSON。
 */
@Data
public class TicketMetaView {

    private String channelCode;
    private String channelName;
    /** 本入口是否要求填联系电话：渠道勾选或全局参数要求，任一成立即为真 */
    private Boolean needPhone;
    private Integer maxImages;
    /** 本入口绑定的问题所在科室名称（不回 ID） */
    private String bizDeptName;
    /**
     * 表单必填规则（来自「参数配置 → 登记表单」）。
     * 手机端据此决定哪些字段挂「必填」标记、最少几个字、照片至少几张；
     * 服务端提交时读的是同一份参数，两边不会各说各话。
     */
    private TicketFormRules formRules;
    private List<CategoryOption> categories;
    private List<UrgencyOption> urgencies;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryOption {
        private Long id;
        private String name;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UrgencyOption {
        private Integer code;
        private String name;
        /** 该紧急度对应的受理时限分钟数（与参数配置里填的值一致） */
        private Integer slaMinutes;
    }
}
