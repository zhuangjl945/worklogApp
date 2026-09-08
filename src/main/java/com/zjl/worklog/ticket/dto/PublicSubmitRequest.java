package com.zjl.worklog.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 手机端提交问题的请求体。
 *
 * <p>字段长度上限与 service_ticket 建表一致，超长直接 400，不要依赖数据库截断。
 */
@Data
public class PublicSubmitRequest {

    /** 先调 /api/public/tickets/form-token 换取的一次性填表凭证 */
    @NotBlank(message = "填表凭证缺失，请重新扫码")
    @Size(max = 64)
    private String formToken;

    @NotBlank(message = "请填写问题标题")
    @Size(min = 4, max = 200, message = "标题需 4~200 字")
    private String title;

    @NotBlank(message = "请描述遇到的问题")
    @Size(min = 5, max = 5000, message = "问题描述需 5~5000 字")
    private String content;

    @Size(max = 200)
    private String location;

    @Size(max = 50)
    private String contactName;

    /** 内部员工场景：手机号可不填；填了就必须是合法格式，用于限流与联系 */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    @Size(max = 20)
    private String contactPhone;

    /** 问题类型，必须是本渠道科室下的分类 */
    private Long categoryId;

    /** 1-紧急 2-普通 3-一般；非法值按 2 处理 */
    private Integer urgency;

    /** 已直传到本渠道目录下的 objectKey 列表，最多 9 张 */
    @Size(max = 9, message = "最多上传 9 张图片")
    private List<String> imageKeys;

    /**
     * 蜜罐字段：页面上不存在这个输入框，只有脚本按表单规律猜填。
     * 一旦有值即判定为机器人，静默返回假单号，不给对方探测信号。
     */
    @Size(max = 0, message = "请勿填写隐藏字段")
    private String website;
}
