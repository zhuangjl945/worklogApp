package com.zjl.worklog.ticket;

import com.zjl.worklog.config.entity.SysConfigEntity;
import com.zjl.worklog.ticket.dto.TicketFormRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「登记表单」参数的解析单测。
 *
 * <p>这套参数直接决定报修人能不能提交，所以最怕的不是解析错而是「解析崩了」：
 * 缺键、脏值、被删空都必须退回默认值，而不是让全院的报修一起 500。
 */
class TicketFormRulesTest {

    private static SysConfigEntity row(String key, String value) {
        SysConfigEntity e = new SysConfigEntity();
        e.setConfigGroup(TicketFormRules.CONFIG_GROUP);
        e.setConfigKey(key);
        e.setConfigValue(value);
        return e;
    }

    private static List<SysConfigEntity> rows(String... kv) {
        List<SysConfigEntity> list = new ArrayList<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            list.add(row(kv[i], kv[i + 1]));
        }
        return list;
    }

    @Test
    @DisplayName("没有任何配置时等于既有行为：标题必填 4 字、描述必填 5 字、图片 0~9 张")
    void emptyConfigKeepsLegacyBehaviour() {
        TicketFormRules r = TicketFormRules.fromRows(List.of());
        assertTrue(r.isTitleRequired());
        assertEquals(4, r.getTitleMinLen());
        assertTrue(r.isContentRequired());
        assertEquals(5, r.getContentMinLen());
        assertFalse(r.isCategoryRequired());
        assertFalse(r.isLocationRequired());
        assertFalse(r.isContactNameRequired());
        assertFalse(r.isContactPhoneRequired());
        assertEquals(0, r.getMinImages());
        assertEquals(9, r.getMaxImages());
    }

    @Test
    @DisplayName("配置为 null（分组被删干净）也不抛异常")
    void nullRowsFallBackToDefaults() {
        assertEquals(4, TicketFormRules.fromRows(null).getTitleMinLen());
    }

    @Test
    @DisplayName("开关认 1/0，也认 true/false；脏值一律回到默认而不是猜")
    void switchesTolerateDirtyValues() {
        TicketFormRules on = TicketFormRules.fromRows(rows("location_required", "1"));
        assertTrue(on.isLocationRequired());
        TicketFormRules off = TicketFormRules.fromRows(rows("location_required", "false"));
        assertFalse(off.isLocationRequired());
        // 管理员手抖写了个「是」：按默认值（选填）处理，不能让提交链路因此改变语义
        TicketFormRules junk = TicketFormRules.fromRows(rows("location_required", "是"));
        assertFalse(junk.isLocationRequired());
        // 空值同理：留空 = 没配 = 默认
        assertFalse(TicketFormRules.fromRows(rows("location_required", "  ")).isLocationRequired());
    }

    @Test
    @DisplayName("字数下限被夹进列宽：填 99999 不会把 200 字的标题列撑爆")
    void minLenIsClampedToColumnWidth() {
        TicketFormRules r = TicketFormRules.fromRows(rows("title_min_len", "99999"));
        assertEquals(TicketFormRules.TITLE_MAX, r.getTitleMinLen());
        TicketFormRules neg = TicketFormRules.fromRows(rows("content_min_len", "-5"));
        assertEquals(1, neg.getContentMinLen());
    }

    @Test
    @DisplayName("字段设为必填时字数下限至少是 1，避免「必填但 0 字也算填了」")
    void requiredFieldAlwaysNeedsAtLeastOneChar() {
        TicketFormRules r = TicketFormRules.fromRows(rows(
                "title_required", "1", "title_min_len", "0",
                "content_required", "1", "content_min_len", "0"));
        assertEquals(1, r.getTitleMinLen());
        assertEquals(1, r.getContentMinLen());
    }

    @Test
    @DisplayName("图片张数：max 不超过硬上限，min 不超过 max")
    void imageCountsStayCoherent() {
        TicketFormRules r = TicketFormRules.fromRows(rows("max_images", "99", "min_images", "50"));
        assertEquals(TicketFormRules.IMAGES_HARD_CAP, r.getMaxImages());
        assertEquals(TicketFormRules.IMAGES_HARD_CAP, r.getMinImages());
        // max 调小之后 min 要跟着收，否则会出现「至少 8 张、最多 3 张」这种永远提交不了的组合
        TicketFormRules tight = TicketFormRules.fromRows(rows("max_images", "3", "min_images", "8"));
        assertEquals(3, tight.getMaxImages());
        assertEquals(3, tight.getMinImages());
    }

    @Test
    @DisplayName("非数字的张数配置回退默认值")
    void imageCountsRejectGarbage() {
        TicketFormRules r = TicketFormRules.fromRows(rows("max_images", "abc"));
        assertEquals(9, r.getMaxImages());
    }

    @Test
    @DisplayName("标题与描述同时关掉时才需要「二选一」提示")
    void problemTextOptionalOnlyWhenBothOff() {
        assertFalse(TicketFormRules.fromRows(List.of()).problemTextOptional());
        assertTrue(TicketFormRules.fromRows(rows(
                "title_required", "0", "content_required", "0")).problemTextOptional());
        assertFalse(TicketFormRules.fromRows(rows(
                "title_required", "0", "content_required", "1")).problemTextOptional());
    }
}
