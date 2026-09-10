package com.zjl.worklog.ticket.dto;

import com.zjl.worklog.config.entity.SysConfigEntity;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 手机端「问题登记」表单规则：哪些字段必填、最短几个字、照片至少/最多几张。
 *
 * <p>数据源是 sys_config 的 ticket_form 分组，管理员在「参数配置」页改完即时生效
 * （不用重启、不用改代码）。本对象是「读出来就已经夹好」的值对象：字段全是基本类型、
 * 区间全部收敛过，所以调用方（TicketService 校验、手机端渲染）不必再判 null 或重复夹取。
 *
 * <p>用 JavaBean 而不是 record：这个类会被 meta 接口原样序列化给手机端，
 * record 的访问器没有 get 前缀，一旦字段改名，前后端约定就悄悄错位了。
 */
@Data
public class TicketFormRules {

    /** sys_config 里的分组名，参数配置页显示为「登记表单」 */
    public static final String CONFIG_GROUP = "ticket_form";

    /** 与 service_ticket 建表长度一致的上限：参数只能收紧，不能突破列宽 */
    public static final int TITLE_MAX = 200;
    public static final int CONTENT_MAX = 5000;
    public static final int NAME_MAX = 50;
    public static final int LOCATION_MAX = 200;
    /** 图片张数硬上限：手机端压缩与 OSS 目录都按 9 张设计，参数只能在这个数以下调 */
    public static final int IMAGES_HARD_CAP = 9;

    private boolean titleRequired;
    private int titleMinLen;
    private boolean contentRequired;
    private int contentMinLen;
    /** 是否必须在页面上亲手选一个问题类型（不允许「不指定」） */
    private boolean categoryRequired;
    private boolean locationRequired;
    private boolean contactNameRequired;
    /**
     * 联系电话是否必填。这是「全局底线」：
     * 与渠道上的 need_phone 取并集（任一为必填即必填），渠道只能在此之上收紧，不能放松。
     */
    private boolean contactPhoneRequired;
    /** 至少上传几张现场照片，0 表示不作要求 */
    private int minImages;
    /** 最多允许几张现场照片 */
    private int maxImages;

    /**
     * 与既有行为完全一致的默认值：标题必填不短于 4 字、描述必填不短于 5 字、其余选填、图片 0~9 张。
     * 配置缺失、被删空、解析失败时一律回退到这里，绝不能让手机端提交整体挂掉。
     */
    public static TicketFormRules defaults() {
        TicketFormRules r = new TicketFormRules();
        r.titleRequired = true;
        r.titleMinLen = 4;
        r.contentRequired = true;
        r.contentMinLen = 5;
        r.categoryRequired = false;
        r.locationRequired = false;
        r.contactNameRequired = false;
        r.contactPhoneRequired = false;
        r.minImages = 0;
        r.maxImages = IMAGES_HARD_CAP;
        return r;
    }

    /**
     * 把 sys_config 的行解析成规则。缺失的键沿用默认值，非法值丢弃该键继续往下读。
     *
     * <p>这里不抛异常：参数配置页允许自由增删键，不能让一次误删变成「所有报修都提交不了」。
     */
    public static TicketFormRules fromRows(List<SysConfigEntity> rows) {
        TicketFormRules r = defaults();
        if (rows == null || rows.isEmpty()) {
            return r;
        }
        Map<String, String> kv = new HashMap<>();
        for (SysConfigEntity row : rows) {
            if (row != null && row.getConfigKey() != null) {
                kv.put(row.getConfigKey().trim(), row.getConfigValue());
            }
        }
        r.titleRequired = boolOf(kv, "title_required", r.titleRequired);
        r.titleMinLen = intOf(kv, "title_min_len", r.titleMinLen);
        r.contentRequired = boolOf(kv, "content_required", r.contentRequired);
        r.contentMinLen = intOf(kv, "content_min_len", r.contentMinLen);
        r.categoryRequired = boolOf(kv, "category_required", r.categoryRequired);
        r.locationRequired = boolOf(kv, "location_required", r.locationRequired);
        r.contactNameRequired = boolOf(kv, "contact_name_required", r.contactNameRequired);
        r.contactPhoneRequired = boolOf(kv, "contact_phone_required", r.contactPhoneRequired);
        r.minImages = intOf(kv, "min_images", r.minImages);
        r.maxImages = intOf(kv, "max_images", r.maxImages);
        r.clamp();
        return r;
    }

    /** 把参数值夹进安全区间：必填时至少要求 1 个字，长度不超过列宽，图片张数不超过硬上限 */
    private void clamp() {
        titleMinLen = clampInt(titleMinLen, titleRequired ? 1 : 0, TITLE_MAX);
        contentMinLen = clampInt(contentMinLen, contentRequired ? 1 : 0, CONTENT_MAX);
        maxImages = clampInt(maxImages, 1, IMAGES_HARD_CAP);
        minImages = clampInt(minImages, 0, maxImages);
    }

    /** 标题与描述都被设成选填：此时只要求「两者至少填一个」，见 TicketService#submit */
    public boolean problemTextOptional() {
        return !titleRequired && !contentRequired;
    }

    private static boolean boolOf(Map<String, String> kv, String key, boolean fallback) {
        String raw = kv.get(key);
        if (raw == null) {
            return fallback;
        }
        String v = raw.trim().toLowerCase();
        if (v.isEmpty()) {
            return fallback;
        }
        return switch (v) {
            case "1", "true", "yes", "on", "required" -> true;
            case "0", "false", "no", "off", "optional" -> false;
            default -> fallback;
        };
    }

    private static int intOf(Map<String, String> kv, String key, int fallback) {
        String raw = kv.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
