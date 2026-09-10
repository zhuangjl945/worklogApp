package com.zjl.worklog.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 手机端提交问题的请求体。
 *
 * <p>字段长度上限与 service_ticket 建表一致，超长直接 400，不要依赖数据库截断。
 *
 * <p>注意：这里刻意不放「必填」类注解（@NotBlank / @Size(min=...)）。
 * 哪些字段必填、最少几个字由「参数配置 → 登记表单」里的 sys_config 决定，
 * 注解是编译期固定的，一旦写了就改不动，因此这类规则统一在 TicketService#submit 里按参数校验。
 * 注解只保留无法配置化的硬上限。
 */
@Data
public class PublicSubmitRequest {

    /** 先调 /api/public/tickets/form-token 换取的一次性填表凭证 */
    @NotBlank(message = "填表凭证缺失，请重新扫码")
    @Size(max = 64)
    private String formToken;

    /** 是否必填、最短长度见 TicketFormRules#titleRequired / titleMinLen */
    @Size(max = 200, message = "标题不超过 200 字")
    private String title;

    /** 是否必填、最短长度见 TicketFormRules#contentRequired / contentMinLen */
    @Size(max = 5000, message = "问题描述不超过 5000 字")
    private String content;

    @Size(max = 200)
    private String location;

    @Size(max = 50)
    private String contactName;

    /** 联系电话：手机、短号、内线均可；必填与否取「渠道勾选 OR 全局参数」的并集 */
    @Size(max = 20, message = "联系电话过长")
    private String contactPhone;

    /**
     * 报修人自己设定的 6 位数字查询密码，之后凭「单号 + 这 6 位」查进度。
     *
     * <p>这里刻意不写 @NotBlank：注解只到「格式对不对」，
     * 「是不是 6 位纯数字、是不是 123456 这种一眼猜中的号」由 TicketQueryCode 判定，
     * 报错文案也要跟着规则走，两处不好拆着用注解。
     * 但它事实上是必填——单号已经是可预测的短流水，密码一旦被留空，
     * 查询接口就退化成了公开的全库遍历接口，这条不随 sys_config 变。
     */
    @Size(max = 16, message = "查询密码为 6 位数字")
    private String queryCode;

    /** 问题类型，必须是本渠道科室下的分类；是否必须亲手选择由参数 category_required 决定 */
    private Long categoryId;

    /** 紧急程度 code，取值由 sys_config urgency 分组动态决定（默认 1-4）；非法值取中间档 */
    private Integer urgency;

    /** 已直传到本渠道目录下的 objectKey 列表，张数上下限由参数 min_images / max_images 决定 */
    private List<String> imageKeys;

    /**
     * 蜜罐字段：页面上对人隐藏。有值时由 TicketService 静默返回假单号，
     * 不要用 @Size(max=0) 直接 400——那会把手机浏览器误填也打成「提交失败」。
     */
    @Size(max = 200)
    private String website;
}
