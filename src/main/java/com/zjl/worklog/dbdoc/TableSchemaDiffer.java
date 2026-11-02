package com.zjl.worklog.dbdoc;

import com.zjl.worklog.dbdoc.dto.ColumnInfo;
import com.zjl.worklog.dbdoc.dto.IndexInfo;
import com.zjl.worklog.dbdoc.dto.TableSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 表结构差异比对器。
 *
 * 作用：判断「目录里现有的这张表」和「这次脚本里的这张表」是不是同一份结构，
 * 并给出一句运维看得懂的差异摘要（新增了哪些字段、哪些字段改了类型……）。
 * 有它才能做到「后续再分析只把变化的部分补进去」，而不是每次存一份整库快照。
 *
 * 比对口径：
 * - 字段名、索引名大小写不敏感（Oracle 导出全大写、MySQL 常全小写，不该因此判成变更）；
 * - 字段属性（类型/长度/可空/默认值/注释）逐项严格比较，空白归一后再比；
 * - 字段顺序变化算变更（MySQL 改列顺序确实要 ALTER，属于真实结构变动）；
 * - 索引比较类型与列集合，列顺序变化算变更（联合索引顺序影响执行计划）。
 */
@Component
public class TableSchemaDiffer {

    /** 比对结论：是否变化 + 人看的懂的摘要 */
    public record Difference(boolean changed, String summary) {
    }

    /** 无差异常量，避免反复构造 */
    private static final Difference SAME = new Difference(false, "");

    /**
     * 比较现有结构与 incoming 结构。
     *
     * @param current  目录里已有的表结构，可为 null（表示这张表是第一次登记）
     * @param incoming 本次脚本解析出的表结构
     */
    public Difference diff(TableSchema current, TableSchema incoming) {
        if (current == null) {
            return new Difference(true, "首次登记");
        }
        List<String> parts = new ArrayList<>();

        // ---------- 字段 ----------
        Map<String, ColumnInfo> oldCols = columnMap(current.getColumns());
        Map<String, ColumnInfo> newCols = columnMap(incoming.getColumns());
        Set<String> addedCols = new LinkedHashSet<>(newCols.keySet());
        addedCols.removeAll(oldCols.keySet());
        Set<String> removedCols = new LinkedHashSet<>(oldCols.keySet());
        removedCols.removeAll(newCols.keySet());
        List<String> modifiedCols = new ArrayList<>();
        for (Map.Entry<String, ColumnInfo> entry : newCols.entrySet()) {
            ColumnInfo old = oldCols.get(entry.getKey());
            if (old != null && !columnFingerprint(old).equals(columnFingerprint(entry.getValue()))) {
                modifiedCols.add(entry.getValue().getName()
                        + "(" + shortDesc(old) + "→" + shortDesc(entry.getValue()) + ")");
            }
        }
        if (!addedCols.isEmpty()) {
            parts.add("新增字段:" + joinNames(newCols, addedCols));
        }
        if (!removedCols.isEmpty()) {
            parts.add("删除字段:" + joinNames(oldCols, removedCols));
        }
        if (!modifiedCols.isEmpty()) {
            parts.add("字段变更:" + String.join("、", modifiedCols));
        }
        // 名字集合相同但顺序不同，也算结构变化
        if (addedCols.isEmpty() && removedCols.isEmpty() && modifiedCols.isEmpty()
                && oldCols.keySet().equals(newCols.keySet())
                && !orderedKeys(oldCols).equals(orderedKeys(newCols))) {
            parts.add("字段顺序调整");
        }

        // ---------- 索引 ----------
        Map<String, IndexInfo> oldIdx = indexMap(current.getIndexes());
        Map<String, IndexInfo> newIdx = indexMap(incoming.getIndexes());
        Set<String> addedIdx = new LinkedHashSet<>(newIdx.keySet());
        addedIdx.removeAll(oldIdx.keySet());
        Set<String> removedIdx = new LinkedHashSet<>(oldIdx.keySet());
        removedIdx.removeAll(newIdx.keySet());
        List<String> modifiedIdx = new ArrayList<>();
        for (Map.Entry<String, IndexInfo> entry : newIdx.entrySet()) {
            IndexInfo old = oldIdx.get(entry.getKey());
            if (old != null && !indexFingerprint(old).equals(indexFingerprint(entry.getValue()))) {
                modifiedIdx.add(entry.getValue().getName());
            }
        }
        if (!addedIdx.isEmpty()) {
            parts.add("新增索引:" + indexNames(newIdx, addedIdx));
        }
        if (!removedIdx.isEmpty()) {
            parts.add("删除索引:" + indexNames(oldIdx, removedIdx));
        }
        if (!modifiedIdx.isEmpty()) {
            parts.add("索引变更:" + String.join("、", modifiedIdx));
        }

        // ---------- 表注释 ----------
        String oldComment = normalize(current.getTableComment());
        String newComment = normalize(incoming.getTableComment());
        if (!oldComment.equals(newComment)) {
            parts.add("表注释变更");
        }

        if (parts.isEmpty()) {
            return SAME;
        }
        String summary = String.join("; ", parts);
        // 摘要要塞进 db_table_version.diff_summary（varchar(1000)），过长就截断并标注条数
        if (summary.length() > 900) {
            summary = summary.substring(0, 780) + "...（共 " + parts.size() + " 项差异，明细见两版结构对比）";
        }
        return new Difference(true, summary);
    }

    /** 只比较结构本体（字段 + 索引 + 注释）是否一致，供导入时快速判定 */
    public boolean isSameStructure(TableSchema current, TableSchema incoming) {
        return !diff(current, incoming).changed();
    }

    // ============================== 内部工具 ==============================

    /** 字段名（小写）→ 字段，保留脚本里的出现顺序 */
    private Map<String, ColumnInfo> columnMap(List<ColumnInfo> columns) {
        Map<String, ColumnInfo> map = new LinkedHashMap<>();
        if (columns == null) {
            return map;
        }
        for (ColumnInfo col : columns) {
            if (col == null || col.getName() == null) {
                continue;
            }
            map.put(col.getName().toLowerCase(), col);
        }
        return map;
    }

    /** 索引名（小写）→ 索引；没有名字的索引用「类型+列」兜底当键，避免互相覆盖 */
    private Map<String, IndexInfo> indexMap(List<IndexInfo> indexes) {
        Map<String, IndexInfo> map = new LinkedHashMap<>();
        if (indexes == null) {
            return map;
        }
        for (IndexInfo idx : indexes) {
            if (idx == null) {
                continue;
            }
            String key = idx.getName() != null && !idx.getName().isEmpty()
                    ? idx.getName().toLowerCase()
                    : "(无名)" + indexFingerprint(idx);
            map.put(key, idx);
        }
        return map;
    }

    /** 字段的可比指纹 */
    private String columnFingerprint(ColumnInfo col) {
        return String.join("|",
                text(col.getType()),
                text(col.getLength()),
                col.isNullable() ? "NULL" : "NOT NULL",
                text(col.getDefaultValue()),
                text(col.getComment()),
                col.isPrimaryKey() ? "PK" : "");
    }

    /** 索引的可比指纹 */
    private String indexFingerprint(IndexInfo idx) {
        String cols = idx.getColumns() == null ? "" : idx.getColumns().stream()
                .filter(Objects::nonNull)
                .map(v -> v.toLowerCase())
                .collect(Collectors.joining(","));
        return text(idx.getType()) + "|" + cols;
    }

    /** 摘要里对字段的一句话描述 */
    private String shortDesc(ColumnInfo col) {
        StringBuilder sb = new StringBuilder();
        sb.append(text(col.getType()));
        String len = text(col.getLength());
        if (!len.isEmpty()) {
            sb.append("(").append(len).append(")");
        }
        if (!col.isNullable()) {
            sb.append(" NOT NULL");
        }
        String comment = text(col.getComment());
        if (!comment.isEmpty()) {
            sb.append(" ").append(comment);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** 按 key 集合取回原始大小写的字段名 */
    private String joinNames(Map<String, ColumnInfo> source, Set<String> keys) {
        return keys.stream()
                .map(k -> {
                    ColumnInfo col = source.get(k);
                    return col != null && col.getName() != null ? col.getName() : k;
                })
                .collect(Collectors.joining("、"));
    }

    /** 摘要里的索引名用脚本里的原始大小写，别把 IDX_NAME 写成 idx_name */
    private String indexNames(Map<String, IndexInfo> source, Set<String> keys) {
        return keys.stream()
                .map(k -> {
                    IndexInfo idx = source.get(k);
                    return idx != null && idx.getName() != null && !idx.getName().isEmpty() ? idx.getName() : k;
                })
                .collect(Collectors.joining("、"));
    }
    /** 小写键的有序比较：Set 相等但顺序不同的情况只有靠这个列表看出来 */
    private List<String> orderedKeys(Map<String, ?> map) {
        return new ArrayList<>(map.keySet());
    }

    private String text(String raw) {
        return normalize(raw);
    }

    /** 归一：null 转空串、去首尾空白、连续空白并一个空格，大小写保留（注释文本可能有意义） */
    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replaceAll("\\s+", " ");
    }
}