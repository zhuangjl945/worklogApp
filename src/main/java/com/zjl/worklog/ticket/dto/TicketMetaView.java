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
    private Boolean needPhone;
    private Integer maxImages;
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
        /** 该紧急度对应的 SLA 小时数，手机端可直接提示「预计 4 小时内响应」 */
        private Integer slaHours;
    }
}
