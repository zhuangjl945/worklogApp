package com.zjl.worklog.dbdoc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjl.worklog.dbdoc.dto.ColumnInfo;
import com.zjl.worklog.dbdoc.dto.IndexInfo;
import com.zjl.worklog.dbdoc.dto.ManualEditView;
import com.zjl.worklog.dbdoc.dto.TableSchema;
import com.zjl.worklog.dbdoc.entity.DbTableDocEntity;
import com.zjl.worklog.dbdoc.mapper.DbDocMapper;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.Role;
import com.zjl.worklog.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「人工修订表结构」这一对接口的外形契约。
 *
 * <p>服务层的合并语义在 DbTableImportServiceTest 里盯过了，这里只钉两件编译期看不出来的事：
 * 一是页面上那份 JSON 请求体能不能原样落到 StructureRequest / TableSchema 的字段上（字段名对不上就是静默丢数据，
 * 前端弹「已保存」而结构没变，比报错更坑）；二是路由参数与登录人有没有真的传到服务层。
 */
class DbAssetStructureApiTest {

    private final DbAssetService assetService = mock(DbAssetService.class);
    private final DbTableImportService importService = mock(DbTableImportService.class);
    private final DbAssetController controller =
            new DbAssetController(assetService, importService, mock(DbDocMapper.class));

    /** 前端 DbTableStructureEditor 提交时拼出来的真实形状 */
    private static final String PAYLOAD_JSON = """
            {
              "databaseId": 10,
              "tableName": "USER_INFO",
              "tableComment": "用户基本信息",
              "columns": [
                {"name": "ID", "type": "VARCHAR", "length": "32", "nullable": false,
                 "defaultValue": null, "comment": "主键", "primaryKey": true},
                {"name": "NAME", "type": "VARCHAR", "length": "50", "nullable": true,
                 "defaultValue": null, "comment": "姓名", "primaryKey": false}
              ],
              "indexes": [
                {"name": "PRIMARY", "type": "PRIMARY", "columns": ["ID"]}
              ]
            }
            """;

    @AfterEach
    void logout() {
        UserContext.clear();
    }

    private void loginAs(Long userId) {
        UserContext.set(new CurrentUser(userId, "smoke", 1L, "冒烟账号", Role.USER));
    }

    @Test
    @DisplayName("请求体字段一个都不落地映射成 TableSchema，归属前缀交给服务端定")
    void payloadMapsIntoSchema() throws Exception {
        DbAssetController.StructureRequest req =
                new ObjectMapper().readValue(PAYLOAD_JSON, DbAssetController.StructureRequest.class);
        assertEquals(10L, req.getDatabaseId());

        TableSchema schema = req.toSchema();
        assertEquals("USER_INFO", schema.getTableName());
        assertEquals("用户基本信息", schema.getTableComment());
        // 建档与修订的归属都由服务端按现有行/所选库用户决定，请求里那个前缀不该被当作用户改归属
        assertNull(schema.getDbUser(), "toSchema 不该把 databaseId 混成库用户前缀");

        ColumnInfo first = schema.getColumns().get(0);
        assertEquals("ID", first.getName());
        assertEquals("VARCHAR", first.getType());
        assertEquals("32", first.getLength());
        assertTrue(first.isPrimaryKey());
        assertTrue(schema.getColumns().get(1).isNullable());

        IndexInfo primary = schema.getIndexes().get(0);
        assertEquals("PRIMARY", primary.getType());
        assertEquals(List.of("ID"), primary.getColumns());
    }

    @Test
    @DisplayName("PUT /tables/{id}/structure：路径 ID、结构、登录人一起交给服务层")
    void updateStructureRoutesToService() {
        loginAs(9L);
        when(importService.updateStructureManually(eq(50L), any(), eq(9L)))
                .thenReturn(ManualEditView.builder().changed(true).diffSummary("字段变更:NAME").build());

        controller.updateTableStructure(50L, request());

        ArgumentCaptor<TableSchema> captor = ArgumentCaptor.forClass(TableSchema.class);
        verify(importService).updateStructureManually(eq(50L), captor.capture(), eq(9L));
        assertEquals("USER_INFO", captor.getValue().getTableName());
        assertEquals(2, captor.getValue().getColumns().size());
    }

    @Test
    @DisplayName("POST /tables：建档走所选库用户，不落到某张已有表上")
    void createTableRoutesToService() {
        loginAs(9L);
        when(importService.createTableManually(eq(10L), any(), eq(9L))).thenReturn(new DbTableDocEntity());

        controller.createTable(request());

        verify(importService).createTableManually(eq(10L), any(), eq(9L));
    }

    @Test
    @DisplayName("服务层给出的 changed/diffSummary 原样回到前端，页面才能说准话")
    void serviceResultPassesThrough() {
        loginAs(9L);
        when(importService.updateStructureManually(eq(50L), any(), eq(9L)))
                .thenReturn(ManualEditView.builder().changed(false).diffSummary("").build());

        var response = controller.updateTableStructure(50L, request());

        assertNotNull(response.getData());
        assertEquals(false, response.getData().isChanged());
    }

    private DbAssetController.StructureRequest request() {
        try {
            return new ObjectMapper().readValue(PAYLOAD_JSON, DbAssetController.StructureRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}