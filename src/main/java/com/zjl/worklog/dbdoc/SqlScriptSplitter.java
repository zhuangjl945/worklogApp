package com.zjl.worklog.dbdoc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 脚本切分器。
 *
 * 作用：把用户粘贴的整段 SQL 拆成一条条独立语句，让解析服务可以逐条处理。
 * 不直接用 JSqlParser 的整段解析，是因为整段解析只要有一条语句不认识就全部失败，
 * 用户从数据库导出的 dump 里粘贴几十条语句时，往往就是其中一两条写法特殊导致整体报错。
 *
 * 切分时需要正确处理：UTF-8 BOM、单双引号字符串、反引号标识符、反斜杠转义、
 * 行注释、块注释、MySQL 的版本条件注释，以及 DELIMITER 自定义语句分隔符。
 */
final class SqlScriptSplitter {

    private SqlScriptSplitter() {
    }

    /** 切分出来的一段语句：语句文本 + 它出现在原文中的行号（行号从 1 开始，用于提示用户定位） */
    record StatementChunk(String sql, int startLine) {
    }

    /**
     * 将脚本文本切分为语句列表。
     *
     * @param script 原始 SQL 文本，允许为 null
     * @return 语句列表，纯空白与纯注释的片段会被丢弃
     */
    static List<StatementChunk> split(String script) {
        List<StatementChunk> chunks = new ArrayList<>();
        if (script == null || script.isEmpty()) {
            return chunks;
        }
        String text = SqlSyntaxUtils.stripBom(script);
        String delimiter = ";";
        StringBuilder current = new StringBuilder();
        int line = 1;
        int chunkLine = 0;
        int n = text.length();
        int i = 0;
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        boolean versionComment = false;

        while (i < n) {
            char c = text.charAt(i);

            if (c == '\n') {
                line++;
            }

            // 行注释中：整段丢弃，遇到换行结束
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    current.append('\n');
                }
                i++;
                continue;
            }

            // 块注释中：整段丢弃
            if (blockComment) {
                if (c == '*' && i + 1 < n && text.charAt(i + 1) == '/') {
                    blockComment = false;
                    i += 2;
                    continue;
                }
                i++;
                continue;
            }

            // 引号内：原样保留，处理反斜杠与重复引号两种转义
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && quote != SqlSyntaxUtils.BT && i + 1 < n) {
                    current.append(text.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    if (i + 1 < n && text.charAt(i + 1) == quote) {
                        current.append(quote);
                        i += 2;
                        continue;
                    }
                    quote = 0;
                }
                i++;
                continue;
            }

            // 注释起始标记
            if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-'
                    && SqlSyntaxUtils.isLineCommentTail(text, i + 2)) {
                lineComment = true;
                i += 2;
                continue;
            }
            if (c == '#') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                boolean isVersion = i + 2 < n && text.charAt(i + 2) == '!';
                if (isVersion) {
                    // MySQL 条件注释：跳过标记与版本号，把中间内容当作正常 SQL 继续扫描
                    int j = i + 3;
                    while (j < n && Character.isDigit(text.charAt(j))) {
                        j++;
                    }
                    i = j;
                    versionComment = true;
                    continue;
                }
                blockComment = true;
                i += 2;
                continue;
            }
            // 条件注释的结束标记
            if (versionComment && c == '*' && i + 1 < n && text.charAt(i + 1) == '/') {
                versionComment = false;
                i += 2;
                continue;
            }

            // 引号起始
            if (c == SqlSyntaxUtils.SQ || c == SqlSyntaxUtils.DQ || c == SqlSyntaxUtils.BT) {
                quote = c;
                current.append(c);
                i++;
                continue;
            }

            // 批处理分隔行（Oracle 的单独斜杠、T-SQL 的 GO）与 SQL*Plus 客户端指令行：
            // 这些行既结束上一条语句，自己也不是要解析的 SQL
            if (!Character.isWhitespace(c) && isBoundaryLine(text, i, current)) {
                int lineEnd = indexOfLineEnd(text, i);
                // 分隔行之前攒下的内容算一条完整语句（Oracle 建表语句常常只以 / 结尾，没有分号）
                addChunk(chunks, current, chunkLine);
                // 至少前进一个字符，避免停在分隔行上不前进
                i = Math.max(lineEnd, i + 1);
                current.setLength(0);
                chunkLine = 0;
                continue;
            }
            // DELIMITER 指令：只在一条语句还没开始时识别
            if (current.toString().isBlank() && matchWordAt(text, i, "DELIMITER")) {
                int lineEnd = indexOfLineEnd(text, i);
                if (lineEnd > i) {
                    String directive = text.substring(i, lineEnd).trim();
                    String newDelimiter = directive.substring("DELIMITER".length()).trim();
                    delimiter = newDelimiter.isEmpty() ? ";" : newDelimiter;
                    i = lineEnd;
                    current.setLength(0);
                    continue;
                }
            }

            // 语句结束标记
            if (text.startsWith(delimiter, i)) {
                addChunk(chunks, current, chunkLine);
                current.setLength(0);
                chunkLine = 0;
                i += delimiter.length();
                continue;
            }

            if (chunkLine == 0 && !Character.isWhitespace(c)) {
                chunkLine = line;
            }
            current.append(c);
            i++;
        }

        addChunk(chunks, current, chunkLine);
        return chunks;
    }

    /** 收尾：把缓冲区里的残余语句加入结果，纯注释或空白直接丢弃 */
    private static void addChunk(List<StatementChunk> chunks, StringBuilder buffer, int chunkLine) {
        String sql = buffer.toString().trim();
        if (sql.isEmpty()) {
            return;
        }
        // 只剩注释的片段（例如脚本结尾的 SET 注释）不参与解析
        String withoutComments = SqlSyntaxUtils.stripComments(sql).trim();
        if (withoutComments.isEmpty()) {
            return;
        }
        chunks.add(new StatementChunk(sql, chunkLine == 0 ? 1 : chunkLine));
    }

    /** SQL*Plus / T-SQL 客户端指令，这些行不是 SQL，却常常不带分号，会把后面的语句粘在一起 */
    private static final Set<String> CLIENT_COMMANDS = Set.of(
            "/", "GO", "PROMPT", "WHENEVER", "SPOOL", "EXIT", "QUIT", "HOST", "CONNECT",
            "DISCONNECT", "ACCEPT", "UNDEFINE", "DEFINE", "VARIABLE", "VAR", "EXEC", "EXECUTE",
            "REM", "REMARK", "ECHO", "SHUTDOWN", "SET", "SHOW", "DESC", "DESCRIBE", "GET",
            "SAVE", "START", "RUN", "COLUMN", "COPY", "SPOOLOUT");

    /**
     * 判断当前位置所在的一整行是不是语句边界：
     * 单独一行的斜杠（Oracle 建表语句常以它结尾而没有分号）、GO，或 SQL*Plus 客户端指令。
     * 括号还没闭合时不当边界，避免把 CREATE TABLE 体里以 SET、START 之类开头的列定义误判。
     */
    private static boolean isBoundaryLine(String text, int pos, StringBuilder current) {
        int lineEnd = indexOfLineEnd(text, pos);
        int lineStart = pos;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        String line = text.substring(lineStart, lineEnd).trim();
        if (line.isEmpty()) {
            return false;
        }
        if (line.startsWith("@@") || line.startsWith("@")) {
            return true;
        }
        String firstWord = line.split("[\\s(]+", 2)[0].toUpperCase(Locale.ROOT);
        if (!CLIENT_COMMANDS.contains(firstWord)) {
            return false;
        }
        // 单独一行的 / 无条件算边界；其它指令只在括号闭合时生效
        if (line.equals("/")) {
            return true;
        }
        return parenBalance(current.toString()) == 0;
    }

    /** 括号差值：左括号比右括号多的数量（忽略引号内的括号） */
    private static int parenBalance(String text) {
        int balance = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == 92 && quote != SqlSyntaxUtils.BT) { // 92 是反斜杠，避开 here-string 的转义坑
                    i++;
                    continue;
                }
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == SqlSyntaxUtils.SQ || c == SqlSyntaxUtils.DQ || c == SqlSyntaxUtils.BT) {
                quote = c;
            } else if (c == '(') {
                balance++;
            } else if (c == ')') {
                balance--;
            }
        }
        return balance;
    }
    /** 从 pos 开始找行尾（换行符下标），没有换行返回文本长度 */
    private static int indexOfLineEnd(String text, int pos) {
        int idx = text.indexOf('\n', pos);
        return idx < 0 ? text.length() : idx;
    }

    /** 判断 pos 处是否正好是某个大写单词，且前后是词边界 */
    private static boolean matchWordAt(String text, int pos, String upperWord) {
        int len = upperWord.length();
        if (pos + len > text.length()) {
            return false;
        }
        for (int k = 0; k < len; k++) {
            if (Character.toUpperCase(text.charAt(pos + k)) != upperWord.charAt(k)) {
                return false;
            }
        }
        if (pos > 0 && SqlSyntaxUtils.isWordChar(text.charAt(pos - 1))) {
            return false;
        }
        int after = pos + len;
        return after >= text.length() || !SqlSyntaxUtils.isWordChar(text.charAt(after));
    }
}
