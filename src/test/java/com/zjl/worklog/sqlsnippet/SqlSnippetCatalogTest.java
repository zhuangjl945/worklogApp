package com.zjl.worklog.sqlsnippet;

import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.Role;
import com.zjl.worklog.security.UserContext;
import com.zjl.worklog.sqlsnippet.entity.SqlSnippet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 常用 SQL 的分类和数据库类型。分类是本人随手起的名字，数据库类型必须落在系统认得的那几种里。
 */
class SqlSnippetCatalogTest {

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @Test
    @DisplayName("分类和数据库类型去掉空白后记在本人名下")
    void savesCategoryAndDatabaseType() {
        SqlSnippetOwnershipTest.Memory mapper = new SqlSnippetOwnershipTest.Memory();
        SqlSnippetController controller = new SqlSnippetController(mapper);
        UserContext.set(user(1L));

        long id = controller.create(body("查在院人数", "select 1", null, "  住院统计  ", "oracle")).getData().get("id");

        SqlSnippet saved = mapper.rows.stream().filter(row -> row.getId().equals(id)).findFirst().orElseThrow();
        assertEquals("住院统计", saved.getCategory());
        assertEquals("oracle", saved.getDbType());
    }

    @Test
    @DisplayName("分类和数据库类型留空就存成空")
    void blankCategoryAndDatabaseTypeStayEmpty() {
        SqlSnippetOwnershipTest.Memory mapper = new SqlSnippetOwnershipTest.Memory();
        SqlSnippetController controller = new SqlSnippetController(mapper);
        UserContext.set(user(1L));

        long id = controller.create(body("查在院人数", "select 1", null, "  ", "")).getData().get("id");

        SqlSnippet saved = mapper.rows.stream().filter(row -> row.getId().equals(id)).findFirst().orElseThrow();
        assertNull(saved.getCategory());
        assertNull(saved.getDbType());
    }

    @Test
    @DisplayName("不认识的数据库类型不入库")
    void rejectsUnknownDatabaseType() {
        SqlSnippetOwnershipTest.Memory mapper = new SqlSnippetOwnershipTest.Memory();
        SqlSnippetController controller = new SqlSnippetController(mapper);
        UserContext.set(user(1L));

        BizException ex = assertThrows(BizException.class,
                () -> controller.create(body("查在院人数", "select 1", null, "住院统计", "sqlite")));

        assertEquals(40001, ex.getCode());
        assertEquals("数据库类型不正确", ex.getMsg());
        assertTrue(mapper.rows.isEmpty());
    }

    @Test
    @DisplayName("分类超过 50 字不入库")
    void rejectsLongCategory() {
        SqlSnippetOwnershipTest.Memory mapper = new SqlSnippetOwnershipTest.Memory();
        SqlSnippetController controller = new SqlSnippetController(mapper);
        UserContext.set(user(1L));

        BizException ex = assertThrows(BizException.class,
                () -> controller.create(body("标题", "select 1", null, "类".repeat(51), null)));

        assertEquals(40001, ex.getCode());
        assertEquals("分类不能超过 50 字", ex.getMsg());
        assertTrue(mapper.rows.isEmpty());
    }

    @Test
    @DisplayName("按分类和数据库类型筛选，看不到别人的同名分类")
    void filtersStayInsideTheCurrentUser() {
        SqlSnippetOwnershipTest.Memory mapper = new SqlSnippetOwnershipTest.Memory();
        SqlSnippetController controller = new SqlSnippetController(mapper);
        UserContext.set(user(1L));
        controller.create(body("查在院人数", "select 1", null, "住院统计", "oracle"));
        controller.create(body("空床", "select bed_no from bed", null, "接口排查", "mysql"));
        UserContext.set(user(2L));
        controller.create(body("别人的在院", "select 1", null, "住院统计", "oracle"));

        UserContext.set(user(1L));
        PageResponse<SqlSnippet> byCategory = controller.page(1, 15, null, "住院统计", null).getData();
        assertEquals(1, byCategory.getTotal());
        assertEquals("查在院人数", byCategory.getRecords().get(0).getTitle());

        PageResponse<SqlSnippet> byDb = controller.page(1, 15, null, null, "mysql").getData();
        assertEquals(1, byDb.getTotal());
        assertEquals("空床", byDb.getRecords().get(0).getTitle());

        PageResponse<SqlSnippet> both = controller.page(1, 15, null, "住院统计", "mysql").getData();
        assertEquals(0, both.getTotal());

        assertEquals(List.of("住院统计", "接口排查"), controller.categories().getData());
    }

    @Test
    @DisplayName("关键字也能命中分类")
    void keywordMatchesCategory() {
        SqlSnippetOwnershipTest.Memory mapper = new SqlSnippetOwnershipTest.Memory();
        SqlSnippetController controller = new SqlSnippetController(mapper);
        UserContext.set(user(1L));
        controller.create(body("空床", "select bed_no from bed", null, "住院统计", null));

        PageResponse<SqlSnippet> found = controller.page(1, 15, "住院统计", null, null).getData();

        assertEquals(1, found.getTotal());
        assertEquals("空床", found.getRecords().get(0).getTitle());
    }

    @Test
    @DisplayName("修改时分类和数据库类型一起换掉")
    void updateRewritesCategoryAndDatabaseType() {
        SqlSnippetOwnershipTest.Memory mapper = new SqlSnippetOwnershipTest.Memory();
        SqlSnippetController controller = new SqlSnippetController(mapper);
        UserContext.set(user(1L));
        long id = controller.create(body("查在院人数", "select 1", null, "住院统计", "oracle")).getData().get("id");

        controller.update(id, body("查在院人数", "select 1", null, "接口排查", "mysql"));

        SqlSnippet saved = controller.detail(id).getData();
        assertEquals("接口排查", saved.getCategory());
        assertEquals("mysql", saved.getDbType());
    }

    private static CurrentUser user(Long id) {
        return new CurrentUser(id, "u" + id, 1L, "用户" + id, Role.USER);
    }

    private static SqlSnippetController.SaveRequest body(String title, String sql, String remark, String category, String dbType) {
        SqlSnippetController.SaveRequest req = new SqlSnippetController.SaveRequest();
        req.setTitle(title);
        req.setSqlText(sql);
        req.setRemark(remark);
        req.setCategory(category);
        req.setDbType(dbType);
        return req;
    }
}
