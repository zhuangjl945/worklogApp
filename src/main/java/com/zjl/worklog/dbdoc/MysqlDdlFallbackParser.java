package com.zjl.worklog.dbdoc;

import com.zjl.worklog.dbdoc.dto.ColumnInfo;
import com.zjl.worklog.dbdoc.dto.IndexInfo;
import com.zjl.worklog.dbdoc.dto.TableSchema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地容错 DDL 解析器（回退方案）。
 *
 * JSqlParser 不认识很多数据库的专有写法，例如 MySQL 的 FULLTEXT KEY / SPATIAL KEY /
 * PARTITION BY、SQL Server 的 IDENTITY 与方括号标识符等。只要整段脚本里出现一条这样的语句，
 * 就不应该让用户什么文档都拿不到。
 *
 * 本类只处理与表结构文档有关的 CREATE TABLE / ALTER TABLE / COMMENT ON，
 * 用括号配对加关键字识别做词法级解析；遇到实在不认识的内容就抛异常或返回待忽略片段，
 * 由调用方降级成警告，不影响其它语句。
 */
final class MysqlDdlFallbackParser {

    /** CREATE TABLE [IF NOT EXISTS] 表名 ( 开始 */
    private static final Pattern CREATE_HEAD = Pattern.compile(
            "^create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?(\\S+)", Pattern.CASE_INSENSITIVE);

    /** ALTER TABLE 表名 动作列表 */
    private static final Pattern ALTER_HEAD = Pattern.compile(
            "^alter\\s+table\\s+(\\S+)\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** COMMENT ON TABLE / COLUMN 语句 */
    private static final Pattern COMMENT_ON = Pattern.compile(
            "^comment\\s+on\\s+(table|column)\\s+(.+?)\\s+is\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** ALTER TABLE 后面可选的修饰词，跳过即可 */
    private static final List<String> ALTER_NOISE_WORDS = List.of(
            "IF", "NOT", "EXISTS", "FIRST", "LAST", "COLUMN", "COLUMNS");

    /** 跟在类型后面的多词类型修饰语 */
    private static final Set<String> TYPE_MODIFIERS = Set.of(
            "UNSIGNED", "SIGNED", "ZEROFILL", "PRECISION", "VARYING", "NATIONAL",
            "LARGE", "WITH", "WITHOUT", "TIME", "ZONE", "LOCAL", "BYTE");

    /** 建表体里既不是列也不是索引的噪音条目（Oracle 常见），直接跳过 */
    private static final List<String> IGNORED_ITEM_HEADS = List.of(
            "SUPPLEMENTAL", "NESTED", "OPAQUE", "VARRAY", "ORGANIZATION", "INMEMORY",
            "STORAGE", "TABLESPACE", "PCTFREE", "PCTUSED", "INITRANS", "MAXTRANS",
            "SEGMENT", "LOB", "PARTITION", "SUBPARTITION", "MEMOPTIMIZE", "DEFAULT_ON_COMMIT",
            "DEREF", "XMLTYPE_SUBSTITUTION", "DOCUMENT", "ACCORDION", "ACTION", "CONSTRAINTS");

    /** 约束条目的起始关键字 */
    private static final List<String> CONSTRAINT_HEADS = List.of(
            "PRIMARY", "UNIQUE", "FULLTEXT", "SPATIAL", "KEY", "INDEX",
            "FOREIGN", "CHECK", "PERIOD", "EXCLUDE");

    private MysqlDdlFallbackParser() {
    }

    /** 解析不了时抛出，调用方据此生成警告 */
    static class UnsupportedDdlException extends RuntimeException {
        UnsupportedDdlException(String message) {
            super(message);
        }
    }

    // ============================== CREATE TABLE ==============================

    /**
     * 解析一条 CREATE TABLE 语句。
     *
     * @param statement 单条语句文本（可以带结尾分号）
     * @return 表结构
     */
    static TableSchema parseCreateTable(String statement) {
        String sql = normalize(statement);
        Matcher head = CREATE_HEAD.matcher(sql);
        if (!head.find()) {
            throw new UnsupportedDdlException("不是可识别的 CREATE TABLE 语句");
        }
        String tableName = SqlSyntaxUtils.cleanName(head.group(1));
        int open = sql.indexOf('(', head.end());
        if (open < 0) {
            throw new UnsupportedDdlException("CREATE TABLE 缺少列定义括号");
        }
        int close = SqlSyntaxUtils.matchingParen(sql, open);
        if (close < 0) {
            throw new UnsupportedDdlException("CREATE TABLE 的括号不配对，可能被截断了");
        }
        String body = sql.substring(open + 1, close);
        String options = sql.substring(close + 1);

        Set<String> primaryKeys = new LinkedHashSet<>();
        List<ColumnInfo> columns = new ArrayList<>();
        List<IndexInfo> indexes = new ArrayList<>();

        for (String item : SqlSyntaxUtils.splitTopLevel(body, ',')) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (isConstraintItem(trimmed)) {
                IndexInfo index = parseConstraintItem(trimmed, primaryKeys);
                if (index != null) {
                    indexes.add(index);
                }
            } else {
                columns.add(parseColumnItem(trimmed, primaryKeys));
            }
        }

        // 表级主键要在列上打标记，与 JSqlParser 路径保持一致
        for (ColumnInfo col : columns) {
            if (primaryKeys.contains(col.getName())) {
                col.setPrimaryKey(true);
            }
        }

        return TableSchema.builder()
                .tableName(tableName)
                .tableComment(extractTableComment(options))
                .columns(columns)
                .indexes(indexes)
                .build();
    }

    /** 从表选项里取 COMMENT='xxx' 或 COMMENT 'xxx' */
    private static String extractTableComment(String options) {
        int idx = SqlSyntaxUtils.indexOfKeyword(options, "COMMENT");
        if (idx < 0) {
            return "";
        }
        String rest = options.substring(idx + "COMMENT".length()).replaceFirst("^\\s*=?\\s*", "");
        return SqlSyntaxUtils.unquote(readValueToken(rest));
    }

    // ============================== ALTER TABLE ==============================

    /**
     * 把一条 ALTER TABLE 语句作用到已有表结构上。
     *
     * @param statement 单条语句文本
     * @param tableMap  表名到表结构的映射，表不存在时补一个空壳
     * @return 未能处理的动作说明，全部处理成功时为空列表
     */
    static List<String> applyAlterTable(String statement, Map<String, TableSchema> tableMap) {
        String sql = normalize(statement);
        Matcher head = ALTER_HEAD.matcher(sql);
        if (!head.find()) {
            throw new UnsupportedDdlException("不是可识别的 ALTER TABLE 语句");
        }
        String tableName = SqlSyntaxUtils.cleanName(head.group(1));
        TableSchema schema = tableMap.computeIfAbsent(tableName, name -> TableSchema.builder()
                .tableName(name)
                .tableComment("")
                .columns(new ArrayList<>())
                .indexes(new ArrayList<>())
                .build());

        List<String> unsupported = new ArrayList<>();
        String actions = head.group(2);
        // ALTER TABLE t ADD (a int, b int)：括号包起来的多个列定义，先拆内部
        Matcher bracketAdd = Pattern.compile("^\\s*add\\s*\\((.*)\\)\\s*$",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(actions);
        if (bracketAdd.matches()) {
            for (String col : SqlSyntaxUtils.splitTopLevel(bracketAdd.group(1), ',')) {
                applySingleAlter(schema, "ADD " + col.trim(), unsupported);
            }
            return unsupported;
        }

        for (String action : SqlSyntaxUtils.splitTopLevel(actions, ',')) {
            String trimmed = action.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            applySingleAlter(schema, trimmed, unsupported);
        }
        return unsupported;
    }

    /** 处理单个 ALTER 动作 */
    private static void applySingleAlter(TableSchema schema, String action, List<String> unsupported) {
        String upper = action.toUpperCase(Locale.ROOT);

        // MySQL 的 ALTER 附加选项与表结构无关
        if (startsWithWord(upper, "ALGORITHM") || startsWithWord(upper, "LOCK")) {
            return;
        }
        if (startsWithWord(upper, "COMMENT")) {
            String rest = action.substring("COMMENT".length()).replaceFirst("^\\s*=?\\s*", "");
            schema.setTableComment(SqlSyntaxUtils.unquote(readValueToken(rest)));
            return;
        }
        if (startsWithWord(upper, "AUTO_INCREMENT") || startsWithWord(upper, "ROW_FORMAT")
                || startsWithWord(upper, "ENGINE") || startsWithWord(upper, "CHARSET")
                || startsWithWord(upper, "CHARACTER")) {
            return;
        }

        if (startsWithWord(upper, "ADD")) {
            String rest = dropActionHead(action);
            if (isConstraintItem(rest)) {
                Set<String> pks = new LinkedHashSet<>();
                IndexInfo index = parseConstraintItem(rest, pks);
                if (index != null) {
                    schema.getIndexes().add(index);
                    for (ColumnInfo col : schema.getColumns()) {
                        if (pks.contains(col.getName())) {
                            col.setPrimaryKey(true);
                        }
                    }
                }
            } else {
                Set<String> pks = new LinkedHashSet<>();
                ColumnInfo col = parseColumnItem(rest, pks);
                schema.getColumns().add(col);
            }
            return;
        }

        if (startsWithWord(upper, "MODIFY")) {
            String rest = dropActionHead(action);
            replaceColumn(schema, parseColumnItem(rest, new LinkedHashSet<>()), null);
            return;
        }

        if (startsWithWord(upper, "CHANGE") || startsWithWord(upper, "RENAME")) {
            String rest = dropActionHead(action);
            int end = SqlSyntaxUtils.firstTokenEnd(rest);
            String oldName = SqlSyntaxUtils.cleanName(rest.substring(0, end));
            String remainder = rest.substring(end).trim();
            // CHANGE 需要 "旧名 新名 类型"，RENAME COLUMN 只有 "旧名 TO 新名"
            if (startsWithWord(upper, "CHANGE")) {
                if (remainder.isEmpty()) {
                    unsupported.add(abbreviate(action));
                    return;
                }
                replaceColumn(schema, parseColumnItem(remainder, new LinkedHashSet<>()), oldName);
            } else {
                Matcher m = Pattern.compile("(?is)^\\s*to\\s+(\\S+)\\s*$").matcher(remainder);
                if (m.matches()) {
                    ColumnInfo col = findColumn(schema, oldName);
                    if (col != null) {
                        col.setName(SqlSyntaxUtils.cleanName(m.group(1)));
                    }
                }
            }
            return;
        }

        if (startsWithWord(upper, "DROP")) {
            String rest = dropActionHead(action);
            String restUpper = rest.toUpperCase(Locale.ROOT);
            if (startsWithWord(restUpper, "PRIMARY")) {
                schema.getIndexes().removeIf(idx -> "PRIMARY".equals(idx.getType()));
                schema.getColumns().forEach(col -> col.setPrimaryKey(false));
            } else if (startsWithWord(restUpper, "INDEX") || startsWithWord(restUpper, "KEY")) {
                String idxName = SqlSyntaxUtils.cleanName(SqlSyntaxUtils.firstToken(dropFirstWord(rest)));
                schema.getIndexes().removeIf(idx -> idx.getName() != null && idx.getName().equalsIgnoreCase(idxName));
            } else if (startsWithWord(restUpper, "FOREIGN") || startsWithWord(restUpper, "CHECK")) {
                // 外键与检查约束本来就不记录，忽略
                return;
            } else {
                String colName = SqlSyntaxUtils.cleanName(SqlSyntaxUtils.firstToken(rest));
                schema.getColumns().removeIf(col -> col.getName().equalsIgnoreCase(colName));
            }
            return;
        }

        unsupported.add(abbreviate(action));
    }

    /** 去掉动作的第一个关键字（ADD、MODIFY、CHANGE、DROP、RENAME）与 COLUMN 之类前缀 */
    private static String dropActionHead(String action) {
        String rest = dropFirstWord(action.trim());
        while (true) {
            String token = SqlSyntaxUtils.cleanName(SqlSyntaxUtils.firstToken(rest)).toUpperCase(Locale.ROOT);
            if (token.isEmpty() || !ALTER_NOISE_WORDS.contains(token)) {
                return rest;
            }
            int cut = rest.indexOf(token);
            rest = (cut < 0 ? "" : rest.substring(cut + token.length())).trim();
        }
    }

    /** 去掉第一个单词，返回其余部分 */
    private static String dropFirstWord(String text) {
        int end = text.indexOf(' ');
        return end < 0 ? "" : text.substring(end + 1).trim();
    }

    /** 替换同名列，oldName 为空时按新列名查找 */
    private static void replaceColumn(TableSchema schema, ColumnInfo newCol, String oldName) {
        String key = oldName != null ? oldName : newCol.getName();
        List<ColumnInfo> cols = schema.getColumns();
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).getName().equalsIgnoreCase(key)) {
                cols.set(i, newCol);
                return;
            }
        }
        cols.add(newCol);
    }

    private static ColumnInfo findColumn(TableSchema schema, String name) {
        for (ColumnInfo col : schema.getColumns()) {
            if (col.getName().equalsIgnoreCase(name)) {
                return col;
            }
        }
        return null;
    }

    // ============================== COMMENT ON ==============================

    /**
     * 处理 PostgreSQL 风格的 COMMENT ON TABLE / COMMENT ON COLUMN。
     *
     * @return 应用成功返回 true；不是该类语句返回 false
     */
    static boolean applyCommentOn(String statement, Map<String, TableSchema> tableMap) {
        String sql = normalize(statement);
        Matcher m = COMMENT_ON.matcher(sql);
        if (!m.matches()) {
            return false;
        }
        boolean isColumn = "column".equalsIgnoreCase(m.group(1));
        String target = SqlSyntaxUtils.cleanName(m.group(2));
        String comment = SqlSyntaxUtils.unquote(readValueToken(m.group(3).trim()));
        if (comment.isEmpty()) {
            return true;
        }

        if (!isColumn) {
            TableSchema schema = tableMap.get(target);
            if (schema == null && target.contains(".")) {
                schema = tableMap.get(target.substring(target.lastIndexOf('.') + 1));
            }
            if (schema != null) {
                schema.setTableComment(comment);
            }
            return true;
        }

        int dot = target.lastIndexOf('.');
        String tableName = dot < 0 ? target : target.substring(0, dot);
        String columnName = dot < 0 ? "" : target.substring(dot + 1);
        TableSchema schema = tableMap.get(tableName);
        if (schema == null && !tableName.isEmpty()) {
            // 兼容 public.dept 这种带模式名的写法
            for (Map.Entry<String, TableSchema> entry : tableMap.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(tableName) || entry.getKey().endsWith("." + tableName)) {
                    schema = entry.getValue();
                    break;
                }
            }
        }
        if (schema != null) {
            ColumnInfo col = findColumn(schema, columnName);
            if (col != null) {
                col.setComment(comment);
            }
        }
        return true;
    }

    // ============================== 列 / 约束 ==============================

    /** 判断条目是约束还是列定义 */
    private static boolean isConstraintItem(String item) {
        String probe = constraintProbe(item);
        for (String kw : IGNORED_ITEM_HEADS) {
            if (startsWithWord(probe, kw)) {
                return true;
            }
        }
        for (String kw : CONSTRAINT_HEADS) {
            if (startsWithWord(probe, kw)) {
                return true;
            }
        }
        return false;
    }

    /** 去掉 "CONSTRAINT 名字" 前缀后的大写约束文本，用于判断条目类型 */
    private static String constraintProbe(String item) {
        String upper = item.toUpperCase(Locale.ROOT);
        if (!startsWithWord(upper, "CONSTRAINT")) {
            return upper;
        }
        String rest = upper.substring("CONSTRAINT".length()).trim();
        String firstWord = SqlSyntaxUtils.cleanName(SqlSyntaxUtils.firstToken(rest)).toUpperCase(Locale.ROOT);
        if (isConstraintKeywordWord(firstWord)) {
            // CONSTRAINT PRIMARY KEY (...) 这种写法没有约束名
            return rest;
        }
        int space = rest.indexOf(' ');
        return space < 0 ? "" : rest.substring(space + 1).trim();
    }

    /** 判断大写文本是否以某个独立单词开头 */
    private static boolean startsWithWord(String upper, String word) {
        if (!upper.startsWith(word)) {
            return false;
        }
        int after = word.length();
        if (after >= upper.length()) {
            return true;
        }
        char next = upper.charAt(after);
        return !Character.isLetterOrDigit(next) && next != '_' && next != '$';
    }


    /**
     * 解析一个列定义。
     *
     * @param item        列定义文本
     * @param primaryKeys 收集列级主键列名
     */
    private static ColumnInfo parseColumnItem(String item, Set<String> primaryKeys) {
        String text = item.trim();
        int nameEnd = SqlSyntaxUtils.firstTokenEnd(text);
        String name = SqlSyntaxUtils.cleanName(text.substring(0, nameEnd));
        String rest = text.substring(nameEnd).trim();

        // 1) 类型部分：类型词 + 括号参数 + 连续的多词修饰
        int cut = findTypeEnd(rest);
        String typeText = rest.substring(0, cut).trim();
        String attrs = rest.substring(cut).trim();

        String baseType = typeText;
        String length = null;
        int paren = baseType.indexOf('(');
        if (paren >= 0) {
            int close = SqlSyntaxUtils.matchingParen(baseType, paren);
            String args = close > 0 ? baseType.substring(paren + 1, close) : "";
            baseType = baseType.substring(0, paren);
            length = extractLength(args);
        }
        baseType = SqlSyntaxUtils.cleanName(baseType).toUpperCase(Locale.ROOT);

        // 2) 约束部分
        boolean nullable = !containsNotNull(attrs);
        String defaultValue = extractDefault(attrs);
        String comment = extractComment(attrs);
        boolean pk = false;
        if (containsKeywordSequence(attrs, "PRIMARY", "KEY")) {
            pk = true;
            nullable = false;
            if (name != null && !name.isEmpty()) {
                primaryKeys.add(name);
            }
        }

        return ColumnInfo.builder()
                .name(name)
                .type(baseType.isEmpty() ? "UNKNOWN" : baseType)
                .length(length)
                .nullable(nullable)
                .defaultValue(defaultValue)
                .comment(comment)
                .primaryKey(pk)
                .build();
    }

    /** 找到类型文本的结束位置 */
    private static int findTypeEnd(String rest) {
        int i = 0;
        int n = rest.length();
        while (i < n) {
            char c = rest.charAt(i);
            if (Character.isWhitespace(c)) {
                String word = readWord(rest, i);
                if (!word.isEmpty() && TYPE_MODIFIERS.contains(word.toUpperCase(Locale.ROOT))) {
                    i += word.length();
                    continue;
                }
                return i;
            }
            if (c == '(') {
                int close = SqlSyntaxUtils.matchingParen(rest, i);
                i = (close < 0) ? n : close + 1;
                continue;
            }
            if (c == SqlSyntaxUtils.SQ || c == SqlSyntaxUtils.DQ || c == SqlSyntaxUtils.BT) {
                // 被引号包裹的类型名（SQL Server 的 [int] 已转成反引号）
                int end = SqlSyntaxUtils.firstTokenEnd(rest.substring(i)) + i;
                i = end;
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '$') {
                i++;
                continue;
            }
            return i;
        }
        return i;
    }

    /** 从 pos 处读取一个单词 */
    private static String readWord(String text, int pos) {
        int i = pos;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '$') {
                i++;
                continue;
            }
            break;
        }
        return text.substring(pos, i);
    }

    /** 括号参数是纯数字时才当长度，ENUM/SET 的取值列表不算长度 */
    private static String extractLength(String args) {
        String trimmed = args.trim();
        if (trimmed.isEmpty() || trimmed.indexOf(SqlSyntaxUtils.SQ) >= 0) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String part : SqlSyntaxUtils.splitTopLevel(trimmed, ',')) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            // Oracle 的 VARCHAR2(10 BYTE) / CHAR(1 CHAR)，长度只取数字部分
            String numeric = p.replaceAll("(?i)\\s+(BYTE|CHAR)$", "").trim();
            if (!numeric.matches("[-+]?\\d+(\\.\\d+)?")) {
                return null;
            }
            cleaned.add(numeric);
        }
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    private static boolean containsNotNull(String attrs) {
        int notIdx = SqlSyntaxUtils.indexOfKeyword(attrs, "NOT");
        if (notIdx < 0) {
            return false;
        }
        String tail = attrs.substring(notIdx + 3).trim().toUpperCase(Locale.ROOT);
        return tail.startsWith("NULL");
    }

    /** DEFAULT 后面的值：字符串去掉引号，其它统一大写以便与 JSqlParser 路径一致 */
    private static String extractDefault(String attrs) {
        int idx = SqlSyntaxUtils.indexOfKeyword(attrs, "DEFAULT");
        if (idx < 0) {
            return null;
        }
        String rest = attrs.substring(idx + "DEFAULT".length()).trim();
        String raw = readValueToken(rest);
        if (raw.isEmpty()) {
            return null;
        }
        char first = raw.charAt(0);
        if (first == SqlSyntaxUtils.SQ || first == SqlSyntaxUtils.DQ) {
            return SqlSyntaxUtils.unquote(raw);
        }
        return raw.toUpperCase(Locale.ROOT);
    }

    /** COMMENT 后面的字符串注释，中文保持原样 */
    private static String extractComment(String attrs) {
        int idx = SqlSyntaxUtils.indexOfKeyword(attrs, "COMMENT");
        if (idx < 0) {
            return "";
        }
        String rest = attrs.substring(idx + "COMMENT".length()).trim();
        return SqlSyntaxUtils.unquote(readValueToken(rest));
    }

    /** 判断是否依次出现两个关键字，例如 PRIMARY KEY */
    private static boolean containsKeywordSequence(String text, String first, String second) {
        int idx = SqlSyntaxUtils.indexOfKeyword(text, first);
        if (idx < 0) {
            return false;
        }
        String tail = text.substring(idx + first.length()).trim();
        return SqlSyntaxUtils.indexOfKeyword(tail, second) == 0;
    }

    /** 读取一个值：引号串完整取出，括号表达式完整取出，否则取到下一个空白或逗号 */
    private static String readValueToken(String text) {
        String s = text.trim();
        if (s.isEmpty()) {
            return "";
        }
        char first = s.charAt(0);
        if (first == SqlSyntaxUtils.SQ || first == SqlSyntaxUtils.DQ || first == SqlSyntaxUtils.BT) {
            int i = 1;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\\' && first != SqlSyntaxUtils.BT) {
                    i += 2;
                    continue;
                }
                if (c == first) {
                    if (i + 1 < s.length() && s.charAt(i + 1) == first) {
                        i += 2;
                        continue;
                    }
                    return s.substring(0, i + 1);
                }
                i++;
            }
            return s;
        }
        if (first == '(') {
            int close = SqlSyntaxUtils.matchingParen(s, 0);
            return close < 0 ? s : s.substring(0, close + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                break;
            }
            if (c == ',' && sb.length() > 0 && s.charAt(0) != '(') {
                // 逗号是参数分隔符，普通值里遇到即结束
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 解析主键 / 唯一 / 普通索引。
     *
     * @return 外键、CHECK 等非索引约束返回 null
     */
    private static IndexInfo parseConstraintItem(String item, Set<String> primaryKeys) {
        String probe = constraintProbe(item);
        String type;
        String name = null;

        if (startsWithWord(probe, "FOREIGN") || startsWithWord(probe, "CHECK")
                || startsWithWord(probe, "PERIOD") || startsWithWord(probe, "EXCLUDE")) {
            return null;
        }
        for (String kw : IGNORED_ITEM_HEADS) {
            if (startsWithWord(probe, kw)) {
                // Oracle 的 SUPPLEMENTAL LOG DATA、LOB ... STORE AS 之类，与表结构文档无关
                return null;
            }
        }

        if (startsWithWord(probe, "PRIMARY")) {
            type = "PRIMARY";
            name = "PRIMARY";
        } else if (startsWithWord(probe, "UNIQUE")) {
            type = "UNIQUE";
        } else if (startsWithWord(probe, "FULLTEXT")) {
            type = "FULLTEXT";
        } else if (startsWithWord(probe, "SPATIAL")) {
            type = "SPATIAL";
        } else {
            type = "INDEX";
        }

        // 跳过 PRIMARY / UNIQUE / FULLTEXT / SPATIAL / KEY / INDEX / COLUMN 等词，剩下的第一个词是索引名
        String rest = item.trim();
        String restUpper = rest.toUpperCase(Locale.ROOT);
        for (String skip : List.of("PRIMARY", "UNIQUE", "FULLTEXT", "SPATIAL", "KEY", "INDEX", "COLUMN",
                "CLUSTERED", "NONCLUSTERED", "CONSTRAINT")) {
            if (startsWithWord(restUpper, skip)) {
                rest = rest.substring(skip.length()).trim();
                restUpper = rest.toUpperCase(Locale.ROOT);
            }
        }
        // CONSTRAINT 名字：第一词不是约束关键字时即为名字
        if (startsWithWord(item.toUpperCase(Locale.ROOT), "CONSTRAINT")) {
            String afterConstraint = item.trim().substring("CONSTRAINT".length()).trim();
            String candidate = SqlSyntaxUtils.cleanName(SqlSyntaxUtils.firstToken(afterConstraint));
            if (!candidate.isEmpty() && !isConstraintKeywordWord(candidate)) {
                name = candidate;
                rest = afterConstraint.substring(afterConstraint.indexOf(candidate) + candidate.length()).trim();
                restUpper = rest.toUpperCase(Locale.ROOT);
                for (String skip : List.of("PRIMARY", "UNIQUE", "FULLTEXT", "SPATIAL", "KEY", "INDEX", "COLUMN")) {
                    if (startsWithWord(restUpper, skip)) {
                        rest = rest.substring(skip.length()).trim();
                        restUpper = rest.toUpperCase(Locale.ROOT);
                    }
                }
            }
        }
        if (name == null) {
            String head = SqlSyntaxUtils.firstToken(rest);
            if (!head.isEmpty() && head.charAt(0) != '(' && head.charAt(0) != ')') {
                String candidate = SqlSyntaxUtils.cleanName(head);
                if (!candidate.isEmpty() && !isConstraintKeywordWord(candidate)) {
                    name = candidate;
                }
            }
        }

        List<String> cols = readIndexColumns(rest.isEmpty() ? item : rest);
        if ("PRIMARY".equals(type)) {
            primaryKeys.addAll(cols);
        }
        if (name == null || name.isEmpty()) {
            name = type;
        }
        return IndexInfo.builder().name(name).type(type).columns(cols).build();
    }

    private static boolean isConstraintKeywordWord(String word) {
        String upper = word.toUpperCase(Locale.ROOT);
        for (String kw : CONSTRAINT_HEADS) {
            if (kw.equals(upper)) {
                return true;
            }
        }
        return false;
    }

    /** 取第一个括号组作为索引列，去掉前缀长度与升降序 */
    private static List<String> readIndexColumns(String item) {
        int open = item.indexOf('(');
        if (open < 0) {
            return new ArrayList<>();
        }
        int close = SqlSyntaxUtils.matchingParen(item, open);
        if (close < 0) {
            return new ArrayList<>();
        }
        String inner = item.substring(open + 1, close);
        List<String> cols = new ArrayList<>();
        for (String part : SqlSyntaxUtils.splitTopLevel(inner, ',')) {
            String col = part.trim();
            if (col.isEmpty()) {
                continue;
            }
            int ascIdx = SqlSyntaxUtils.indexOfKeyword(col, "ASC");
            int descIdx = SqlSyntaxUtils.indexOfKeyword(col, "DESC");
            if (ascIdx > 0) {
                col = col.substring(0, ascIdx).trim();
            } else if (descIdx > 0) {
                col = col.substring(0, descIdx).trim();
            }
            if (col.startsWith("(")) {
                // 表达式索引原样保留
                cols.add(SqlSyntaxUtils.cleanName(col));
                continue;
            }
            int paren = col.indexOf('(');
            if (paren > 0) {
                col = col.substring(0, paren);
            }
            col = SqlSyntaxUtils.cleanName(col);
            if (!col.isEmpty()) {
                cols.add(col);
            }
        }
        return cols;
    }

    // ============================== 通用小工具 ==============================

    /** 统一预处理：去注释 + 方括号标识符转反引号 + 去结尾分号 */
    private static String normalize(String statement) {
        String sql = SqlSyntaxUtils.unbracket(SqlSyntaxUtils.stripComments(statement)).trim();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }

    /** 警告文本里只保留短片段 */
    private static String abbreviate(String text) {
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() <= 80 ? one : one.substring(0, 80) + "...";
    }
}
