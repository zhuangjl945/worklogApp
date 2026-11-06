package com.zjl.worklog.sqlsnippet;

import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.Role;
import com.zjl.worklog.security.UserContext;
import com.zjl.worklog.sqlsnippet.entity.SqlSnippet;
import com.zjl.worklog.sqlsnippet.mapper.SqlSnippetMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 常用 SQL 按人隔离。创建人之外的账号，读、改、删都应当成这条语句不存在。
 */
class SqlSnippetOwnershipTest {

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @Test
    @DisplayName("别人读不到这条语句")
    void otherUserCannotRead() {
        Memory mapper = new Memory();
        SqlSnippetController controller = new SqlSnippetController(mapper);
        UserContext.set(user(1L));
        long id = controller.create(body("查在院人数", "select count(*) from patient", "住院")).getData().get("id");

        UserContext.set(user(2L));
        BizException ex = assertThrows(BizException.class, () -> controller.detail(id));

        assertEquals(40400, ex.getCode());
        assertEquals("语句不存在", ex.getMsg());
    }

    @Test
    @DisplayName("别人改不了，原文还在")
    void otherUserCannotUpdate() {
        Memory mapper = new Memory();
        SqlSnippetController controller = new SqlSnippetController(mapper);
        UserContext.set(user(1L));
        long id = controller.create(body("查在院人数", "select count(*) from patient", "住院")).getData().get("id");

        UserContext.set(user(2L));
        BizException ex = assertThrows(BizException.class,
                () -> controller.update(id, body("被改掉了", "select 1", "备注")));

        assertEquals(40400, ex.getCode());
        UserContext.set(user(1L));
        assertEquals("查在院人数", controller.detail(id).getData().getTitle());
        assertEquals("select count(*) from patient", controller.detail(id).getData().getSqlText());
    }

    @Test
    @DisplayName("别人删不了，本人还能打开")
    void otherUserCannotDelete() {
        Memory mapper = new Memory();
        SqlSnippetController controller = new SqlSnippetController(mapper);
        UserContext.set(user(1L));
        long id = controller.create(body("查在院人数", "select count(*) from patient", null)).getData().get("id");

        UserContext.set(user(2L));
        BizException ex = assertThrows(BizException.class, () -> controller.delete(id));

        assertEquals(40400, ex.getCode());
        UserContext.set(user(1L));
        assertEquals(id, controller.detail(id).getData().getId());
    }

    @Test
    @DisplayName("列表只返回当前用户的语句，关键字匹配标题、SQL 和备注")
    void listStaysInsideTheCurrentUser() {
        Memory mapper = new Memory();
        SqlSnippetController controller = new SqlSnippetController(mapper);
        UserContext.set(user(1L));
        controller.create(body("查在院人数", "select count(*) from patient", "住院"));
        controller.create(body("空床", "select bed_no from bed", "病房"));
        UserContext.set(user(2L));
        controller.create(body("别人的在院", "select count(*) from patient", "住院"));

        UserContext.set(user(1L));
        PageResponse<SqlSnippet> all = controller.page(1, 15, "  ", null, null).getData();
        assertEquals(2, all.getTotal());
        assertEquals(2, all.getRecords().size());

        PageResponse<SqlSnippet> bySql = controller.page(1, 15, "patient", null, null).getData();
        assertEquals(1, bySql.getTotal());
        assertEquals("查在院人数", bySql.getRecords().get(0).getTitle());

        PageResponse<SqlSnippet> byRemark = controller.page(1, 15, "病房", null, null).getData();
        assertEquals(1, byRemark.getTotal());
        assertEquals("空床", byRemark.getRecords().get(0).getTitle());
    }

    @Test
    @DisplayName("新建记在当前用户名下")
    void createStampsCurrentUser() {
        Memory mapper = new Memory();
        SqlSnippetController controller = new SqlSnippetController(mapper);
        UserContext.set(user(7L));

        long id = controller.create(body("  日报  ", "  select 1  ", "  ")).getData().get("id");

        SqlSnippet saved = mapper.rows.stream().filter(row -> row.getId().equals(id)).findFirst().orElseThrow();
        assertEquals(7L, saved.getCreatedBy());
        assertEquals("日报", saved.getTitle());
        assertEquals("select 1", saved.getSqlText());
        assertEquals(null, saved.getRemark());
    }

    @Test
    @DisplayName("标题或 SQL 为空时不入库")
    void blankTitleOrSqlIsRejected() {
        Memory mapper = new Memory();
        SqlSnippetController controller = new SqlSnippetController(mapper);
        UserContext.set(user(1L));

        BizException noTitle = assertThrows(BizException.class, () -> controller.create(body("  ", "select 1", null)));
        BizException noSql = assertThrows(BizException.class, () -> controller.create(body("标题", "  ", null)));

        assertEquals(40001, noTitle.getCode());
        assertEquals("请填写标题", noTitle.getMsg());
        assertEquals(40001, noSql.getCode());
        assertEquals("请填写 SQL", noSql.getMsg());
        assertTrue(mapper.rows.isEmpty());
    }

    @Test
    @DisplayName("未登录不能查列表")
    void anonymousCannotList() {
        SqlSnippetController controller = new SqlSnippetController(new Memory());

        BizException ex = assertThrows(BizException.class, () -> controller.page(1, 15, null, null, null));

        assertEquals(401, ex.getCode());
        assertEquals("未登录", ex.getMsg());
    }

    private static CurrentUser user(Long id) {
        return new CurrentUser(id, "u" + id, 1L, "用户" + id, Role.USER);
    }

    private static SqlSnippetController.SaveRequest body(String title, String sql, String remark) {
        SqlSnippetController.SaveRequest req = new SqlSnippetController.SaveRequest();
        req.setTitle(title);
        req.setSqlText(sql);
        req.setRemark(remark);
        return req;
    }

    /**
     * 测试里的语句本。按创建人过滤，用来证明控制器把当前用户传了下去。
     */
    static final class Memory implements SqlSnippetMapper {
        final List<SqlSnippet> rows = new ArrayList<>();
        long seq = 1;

        @Override
        public int insert(SqlSnippet entity) {
            SqlSnippet row = copy(entity);
            row.setId(seq++);
            row.setCreateTime(LocalDateTime.now());
            row.setUpdateTime(row.getCreateTime());
            rows.add(row);
            entity.setId(row.getId());
            return 1;
        }

        @Override
        public SqlSnippet selectById(Long id, Long userId) {
            return rows.stream()
                    .filter(row -> row.getId().equals(id) && row.getCreatedBy().equals(userId))
                    .map(Memory::copy)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public long count(Long userId, String keyword, String category, String dbType) {
            return matching(userId, keyword, category, dbType).size();
        }

        @Override
        public List<SqlSnippet> selectPage(Long userId, String keyword, String category, String dbType, long offset, long size) {
            List<SqlSnippet> matched = matching(userId, keyword, category, dbType);
            matched.sort(Comparator.comparing(SqlSnippet::getId).reversed());
            int from = (int) Math.min(offset, matched.size());
            int to = (int) Math.min(from + size, matched.size());
            return matched.subList(from, to).stream().map(Memory::copy).toList();
        }

        @Override
        public List<String> selectCategories(Long userId) {
            return rows.stream()
                    .filter(row -> row.getCreatedBy().equals(userId))
                    .map(SqlSnippet::getCategory)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .sorted()
                    .toList();
        }

        @Override
        public int update(SqlSnippet entity) {
            for (SqlSnippet row : rows) {
                if (row.getId().equals(entity.getId()) && row.getCreatedBy().equals(entity.getCreatedBy())) {
                    row.setTitle(entity.getTitle());
                    row.setCategory(entity.getCategory());
                    row.setDbType(entity.getDbType());
                    row.setSqlText(entity.getSqlText());
                    row.setRemark(entity.getRemark());
                    row.setUpdateTime(LocalDateTime.now());
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int deleteById(Long id, Long userId) {
            boolean removed = rows.removeIf(row -> row.getId().equals(id) && row.getCreatedBy().equals(userId));
            return removed ? 1 : 0;
        }

        private List<SqlSnippet> matching(Long userId, String keyword, String category, String dbType) {
            List<SqlSnippet> matched = new ArrayList<>();
            for (SqlSnippet row : rows) {
                if (!row.getCreatedBy().equals(userId)) {
                    continue;
                }
                if (category != null && !category.equals(row.getCategory())) {
                    continue;
                }
                if (dbType != null && !dbType.equals(row.getDbType())) {
                    continue;
                }
                if (keyword != null && !keyword.isBlank() && !hit(row, keyword)) {
                    continue;
                }
                matched.add(row);
            }
            return matched;
        }

        private static boolean hit(SqlSnippet row, String keyword) {
            return contains(row.getTitle(), keyword)
                    || contains(row.getSqlText(), keyword)
                    || contains(row.getRemark(), keyword)
                    || contains(row.getCategory(), keyword);
        }

        private static boolean contains(String value, String keyword) {
            return value != null && value.contains(keyword);
        }

        private static SqlSnippet copy(SqlSnippet src) {
            SqlSnippet row = new SqlSnippet();
            row.setId(src.getId());
            row.setTitle(src.getTitle());
            row.setCategory(src.getCategory());
            row.setDbType(src.getDbType());
            row.setSqlText(src.getSqlText());
            row.setRemark(src.getRemark());
            row.setCreatedBy(src.getCreatedBy());
            row.setCreateTime(src.getCreateTime());
            row.setUpdateTime(src.getUpdateTime());
            return row;
        }
    }
}
