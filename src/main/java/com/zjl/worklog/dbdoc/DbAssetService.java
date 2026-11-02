package com.zjl.worklog.dbdoc;

import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.dbdoc.dto.CatalogView;
import com.zjl.worklog.dbdoc.dto.TableDetailView;
import com.zjl.worklog.dbdoc.entity.DbDatabaseEntity;
import com.zjl.worklog.dbdoc.entity.DbSystemEntity;
import com.zjl.worklog.dbdoc.entity.DbTableDocEntity;
import com.zjl.worklog.dbdoc.entity.DbTableVersionEntity;
import com.zjl.worklog.dbdoc.mapper.DbDatabaseMapper;
import com.zjl.worklog.dbdoc.mapper.DbSystemMapper;
import com.zjl.worklog.dbdoc.mapper.DbTableDocMapper;
import com.zjl.worklog.dbdoc.mapper.DbTableVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * 表结构资产服务：三级目录（业务系统 → 数据库用户 → 表）的维护与浏览。
 *
 * 与导入解耦：本类只管「目录本身」，「一份脚本怎么并进来」在 {@link DbTableImportService}。
 */
@Service
public class DbAssetService {

    /** 环境白名单：只认这四个值，避免「生产」「生产1」「prod 」把唯一键拆成好几条 */
    private static final List<String> ENVS = List.of("prod", "test", "uat", "dev");

    private final DbSystemMapper systemMapper;
    private final DbDatabaseMapper databaseMapper;
    private final DbTableDocMapper tableDocMapper;
    private final DbTableVersionMapper versionMapper;
    private final TableSchemaCodec codec;

    public DbAssetService(DbSystemMapper systemMapper,
                          DbDatabaseMapper databaseMapper,
                          DbTableDocMapper tableDocMapper,
                          DbTableVersionMapper versionMapper,
                          TableSchemaCodec codec) {
        this.systemMapper = systemMapper;
        this.databaseMapper = databaseMapper;
        this.tableDocMapper = tableDocMapper;
        this.versionMapper = versionMapper;
        this.codec = codec;
    }

    // ============================== 业务系统 ==============================

    /** 系统列表，带库数/表数统计 */
    public List<DbSystemEntity> listSystems(String keyword, Integer status) {
        return systemMapper.selectList(trimToNull(keyword), status);
    }

    /** 系统详情 */
    public DbSystemEntity getSystem(Long id) {
        DbSystemEntity entity = systemMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "业务系统不存在");
        }
        return entity;
    }

    /** 按名称找系统，找不到返回 null（导入时「同名即同一个」） */
    public DbSystemEntity findSystemByName(String name) {
        String target = trimToNull(name);
        if (target == null) {
            return null;
        }
        for (DbSystemEntity entity : systemMapper.selectList(target, null)) {
            if (entity.getSystemName() != null && entity.getSystemName().equalsIgnoreCase(target)) {
                return entity;
            }
        }
        return null;
    }

    /** 新增系统 */
    public DbSystemEntity createSystem(DbSystemEntity form, Long operatorId) {
        form.setSystemName(requireText(form.getSystemName(), "系统名称不能为空"));
        form.setSystemCode(trimToNull(form.getSystemCode()));
        assertCodeUnique(form.getSystemCode(), null);
        form.setDbType(lowerOrDefault(trimToNull(form.getDbType()), "mysql"));
        form.setStatus(form.getStatus() == null ? 1 : form.getStatus());
        form.setSortNo(form.getSortNo() == null ? 0 : form.getSortNo());
        form.setCreatedBy(operatorId);
        systemMapper.insert(form);
        return getSystem(form.getId());
    }

    /** 修改系统 */
    public DbSystemEntity updateSystem(Long id, DbSystemEntity form) {
        DbSystemEntity existed = getSystem(id);
        form.setId(id);
        form.setSystemName(requireText(form.getSystemName(), "系统名称不能为空"));
        form.setSystemCode(trimToNull(form.getSystemCode()));
        assertCodeUnique(form.getSystemCode(), id);
        form.setDbType(lowerOrDefault(trimToNull(form.getDbType()), existed.getDbType()));
        form.setStatus(form.getStatus() == null ? existed.getStatus() : form.getStatus());
        form.setSortNo(form.getSortNo() == null ? existed.getSortNo() : form.getSortNo());
        systemMapper.updateById(form);
        return getSystem(id);
    }

    /** 删除系统：名下还有库或表就不允许，历史结构不能跟着目录一起没了 */
    @Transactional
    public void deleteSystem(Long id) {
        getSystem(id);
        if (!databaseMapper.selectBySystemId(id).isEmpty()) {
            throw new BizException(40001, "该系统下还有数据库用户，请先清理或改用停用");
        }
        if (tableDocMapper.countBySystem(id) > 0) {
            throw new BizException(40001, "该系统下仍有表结构登记，无法删除");
        }
        systemMapper.deleteById(id);
    }

    // ============================== 数据库用户 ==============================

    /** 数据库用户列表 */
    public List<DbDatabaseEntity> listDatabases(Long systemId, String keyword, Integer status) {
        return databaseMapper.selectList(systemId, trimToNull(keyword), status);
    }

    /** 数据库用户详情 */
    public DbDatabaseEntity getDatabase(Long id) {
        DbDatabaseEntity entity = databaseMapper.selectById(id);
        if (entity == null) {
            throw new BizException(404, "数据库用户不存在");
        }
        return entity;
    }

    /** 新增数据库用户 */
    public DbDatabaseEntity createDatabase(DbDatabaseEntity form, Long operatorId) {
        if (form.getSystemId() == null) {
            throw new BizException(40001, "必须指定所属系统");
        }
        getSystem(form.getSystemId());
        form.setDbUser(requireText(form.getDbUser(), "数据库用户/模式名不能为空"));
        form.setEnv(normalizeEnv(form.getEnv()));
        form.setDbType(lowerOrDefault(trimToNull(form.getDbType()), "mysql"));
        assertDatabaseUnique(form.getSystemId(), form.getDbUser(), form.getEnv(), null);
        form.setStatus(form.getStatus() == null ? 1 : form.getStatus());
        form.setCreatedBy(operatorId);
        databaseMapper.insert(form);
        return getDatabase(form.getId());
    }

    /** 修改数据库用户 */
    public DbDatabaseEntity updateDatabase(Long id, DbDatabaseEntity form) {
        DbDatabaseEntity existed = getDatabase(id);
        form.setId(id);
        form.setSystemId(form.getSystemId() == null ? existed.getSystemId() : form.getSystemId());
        form.setDbUser(requireText(form.getDbUser(), "数据库用户/模式名不能为空"));
        form.setEnv(normalizeEnv(form.getEnv() == null ? existed.getEnv() : form.getEnv()));
        form.setDbType(lowerOrDefault(trimToNull(form.getDbType()), existed.getDbType()));
        assertDatabaseUnique(form.getSystemId(), form.getDbUser(), form.getEnv(), id);
        form.setStatus(form.getStatus() == null ? existed.getStatus() : form.getStatus());
        databaseMapper.updateById(form);
        // db_user 改名后，表目录里的冗余列要跟着换，否则列表显示的还是老用户名
        tableDocMapper.renameDbUser(id, form.getDbUser());
        return getDatabase(id);
    }

    /** 删除数据库用户：名下有表就先让用户显式处理，防止一键清空资产 */
    @Transactional
    public void deleteDatabase(Long id) {
        getDatabase(id);
        int tables = databaseMapper.countTables(id);
        if (tables > 0) {
            throw new BizException(40001, "该数据库用户下还登记了 " + tables + " 张表，请先删除表或改用停用");
        }
        databaseMapper.deleteById(id);
    }

    /** 停用/启用不改结构，只影响是否出现在下拉里 */
    public DbDatabaseEntity toggleDatabase(Long id, Integer status) {
        DbDatabaseEntity existed = getDatabase(id);
        existed.setStatus(status != null && status == 1 ? 1 : 0);
        databaseMapper.updateById(existed);
        return getDatabase(id);
    }

    // ============================== 目录树 / 表 ==============================

    /** 三级目录概览：系统 → 数据库用户（表清单走分页接口，不在这里全量吐） */
    public CatalogView catalog() {
        CatalogView view = new CatalogView();
        for (DbSystemEntity system : systemMapper.selectList(null, null)) {
            CatalogView.SystemNode node = new CatalogView.SystemNode();
            node.setId(system.getId());
            node.setSystemName(system.getSystemName());
            node.setSystemCode(system.getSystemCode());
            node.setDbType(system.getDbType());
            node.setTableCount(system.getTableCount() == null ? 0 : system.getTableCount());
            for (DbDatabaseEntity db : databaseMapper.selectBySystemId(system.getId())) {
                CatalogView.DatabaseNode child = new CatalogView.DatabaseNode();
                child.setId(db.getId());
                child.setSystemId(db.getSystemId());
                child.setDbUser(db.getDbUser());
                child.setDbName(db.getDbName());
                child.setEnv(db.getEnv());
                child.setDbType(db.getDbType());
                child.setHost(db.getHost());
                child.setTableCount(db.getTableCount() == null ? 0 : db.getTableCount());
                node.getDatabases().add(child);
                view.setTotalTables(view.getTotalTables() + child.getTableCount());
            }
            view.setTotalDatabases(view.getTotalDatabases() + node.getDatabases().size());
            view.getSystems().add(node);
        }
        return view;
    }

    /** 表目录分页 */
    public PageResponse<DbTableDocEntity> pageTables(Long systemId, Long databaseId, String keyword,
                                                     String columnKeyword, Integer status,
                                                     int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = size < 1 ? 20 : Math.min(size, 200);
        String kw = trimToNull(keyword);
        String ck = trimToNull(columnKeyword);
        int total = tableDocMapper.countPage(systemId, databaseId, kw, ck, status);
        List<DbTableDocEntity> records = total == 0
                ? List.of()
                : tableDocMapper.selectPage(systemId, databaseId, kw, ck, status, (safePage - 1) * safeSize, safeSize);
        return PageResponse.of(safePage, safeSize, total, records);
    }

    /** 表详情：结构 + 历史版本 + 还原出的建表语句 */
    public TableDetailView tableDetail(Long id) {
        DbTableDocEntity row = tableDocMapper.selectById(id);
        if (row == null) {
            throw new BizException(404, "表结构记录不存在");
        }
        TableDetailView view = new TableDetailView();
        view.setTable(row);
        view.setColumns(codec.readColumns(row.getColumnsJson()));
        view.setIndexes(codec.readIndexes(row.getIndexesJson()));
        view.setVersions(versionMapper.selectLiteByTableDocId(id));
        view.setRenderedDdl(DdlRenderer.render(codec.toSchema(row)));
        return view;
    }

    /** 人工补充说明 / 手工标记在用与下线 */
    public DbTableDocEntity updateTableManual(Long id, String remark, Integer status, Long operatorId) {
        DbTableDocEntity row = requireTable(id);
        row.setRemark(trimToNull(remark));
        row.setStatus(status == null ? row.getStatus() : (status == 0 ? 0 : 1));
        row.setUpdatedBy(operatorId);
        tableDocMapper.updateManualFields(row);
        databaseMapper.refreshTableCount(row.getDatabaseId());
        return tableDocMapper.selectById(id);
    }

    /** 删除一张表的登记（历史版本一并清掉） */
    @Transactional
    public void deleteTable(Long id) {
        DbTableDocEntity row = requireTable(id);
        versionMapper.deleteByTableDocId(id);
        tableDocMapper.deleteById(id);
        databaseMapper.refreshTableCount(row.getDatabaseId());
    }

    /** 某张表的历史版本列表 */
    public List<DbTableVersionEntity> listVersions(Long tableDocId) {
        requireTable(tableDocId);
        return versionMapper.selectLiteByTableDocId(tableDocId);
    }

    /** 单个历史版本的完整快照 */
    public DbTableVersionEntity versionDetail(Long versionId) {
        DbTableVersionEntity version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BizException(404, "历史版本不存在");
        }
        return version;
    }

    /** 某个库用户的全部表结构，ER 图用 */
    public List<DbTableDocEntity> databaseTables(Long databaseId, int limit) {
        getDatabase(databaseId);
        return tableDocMapper.selectFullByDatabase(databaseId, limit <= 0 ? 300 : Math.min(limit, 1000));
    }

    // ============================== 供导入服务复用的内部方法 ==============================

    /** 表行的存在性检查 */
    DbTableDocEntity requireTable(Long id) {
        DbTableDocEntity row = tableDocMapper.selectById(id);
        if (row == null) {
            throw new BizException(404, "表结构记录不存在");
        }
        return row;
    }

    /** 环境值归一 */
    public static String normalizeEnv(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return "prod";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return ENVS.contains(lower) ? lower : "prod";
    }

    /** 空串转 null，避免把「没填」写成一条空值记录 */
    public static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }

    private String lowerOrDefault(String value, String fallback) {
        return value == null ? fallback : value.toLowerCase(Locale.ROOT);
    }

    private void assertCodeUnique(String code, Long excludeId) {
        if (code != null && systemMapper.countByCode(code, excludeId) > 0) {
            throw new BizException(40001, "系统编码已存在：" + code);
        }
    }

    private void assertDatabaseUnique(Long systemId, String dbUser, String env, Long excludeId) {
        if (databaseMapper.countByUk(systemId, dbUser, env, excludeId) > 0) {
            throw new BizException(40001, "该系统下已存在同名数据库用户：" + dbUser + "（" + env + "）");
        }
    }

    private String requireText(String raw, String message) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BizException(40001, message);
        }
        return value;
    }
}