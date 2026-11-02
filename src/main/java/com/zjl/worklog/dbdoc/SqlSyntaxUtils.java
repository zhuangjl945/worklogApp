package com.zjl.worklog.dbdoc;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 文本处理的公共小工具。
 *
 * 这些逻辑在 "交给 JSqlParser 正式解析" 和 "本地容错解析" 两条路径里都要用到，
 * 单独抽出来避免两边各写一遍、行为不一致。
 */
final class SqlSyntaxUtils {

    /** SQL 字符串/标识符用到的引号字符 */
    static final char SQ = 39;   // 单引号
    static final char DQ = 34;   // 双引号
    static final char BT = 96;   // 反引号

    private SqlSyntaxUtils() {
    }

    /** 去掉文件开头的 UTF-8 BOM，BOM 会让词法分析直接报错 */
    static String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == 65279) {
            return text.substring(1);
        }
        return text;
    }

    /** 判断是否是需要跳过的空白字符（换行不在内，换行由调用方单独计数） */
    static boolean isBlankChar(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\f';
    }

    /** 去掉标识符外面的反引号、方括号、双引号 */
    static String cleanName(String name) {
        if (name == null) {
            return "";
        }
        String s = name.trim();
        s = s.replace(String.valueOf(BT), "");
        s = s.replace("[", "").replace("]", "");
        s = s.replace(String.valueOf(DQ), "");
        return s;
    }

    /** 去掉字符串字面量外层的引号，并把内部的转义还原 */
    static String unquote(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.length() < 2) {
            return s;
        }
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if ((first == SQ || first == DQ) && last == first) {
            String inner = s.substring(1, s.length() - 1);
            // '' 与 "" 是 SQL 里写引号的方式
            inner = inner.replace(String.valueOf(first) + first, String.valueOf(first));
            // MySQL 默认允许反斜杠转义
            inner = inner.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\");
            return inner;
        }
        return s;
    }

    /**
     * 删除 SQL 中的注释（行注释 -- / # 与块注释），字符串与反引号标识符里的内容原样保留。
     * 保留换行，行号不会因为删注释而错位。
     */
    static String stripComments(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        char quote = 0;
        boolean lineComment = false;
        while (i < n) {
            char c = sql.charAt(i);
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    sb.append('\n');
                }
                i++;
                continue;
            }
            if (quote != 0) {
                sb.append(c);
                if (c == '\\' && quote != BT && i + 1 < n) {
                    sb.append(sql.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    if (i + 1 < n && sql.charAt(i + 1) == quote) {
                        sb.append(quote);
                        i += 2;
                        continue;
                    }
                    quote = 0;
                }
                i++;
                continue;
            }
            if (c == SQ || c == DQ || c == BT) {
                quote = c;
                sb.append(c);
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-' && isLineCommentTail(sql, i + 2)) {
                lineComment = true;
                i += 2;
                continue;
            }
            if (c == '#') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                // 块注释整体丢弃；版本条件注释里的内容也一起丢掉，因为 DDL 文档不关心 SET 类语句
                int end = sql.indexOf("*/" , i + 2);
                i = (end < 0) ? n : end + 2;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** "--" 之后必须是空白或行尾才算行注释，避免把 a--b 之类的表达式误判 */
    static boolean isLineCommentTail(String s, int pos) {
        if (pos >= s.length()) {
            return true;
        }
        char c = s.charAt(pos);
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '/';
    }

    /**
     * 按分隔符切分，但忽略括号内部与引号内部的分隔符。
     * 用于拆 CREATE TABLE 的列定义列表、ALTER TABLE 的多个动作。
     */
    static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        if (text == null) {
            return parts;
        }
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == '\\' && quote != BT && i + 1 < text.length()) {
                    cur.append(text.charAt(++i));
                    continue;
                }
                if (c == quote) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                        cur.append(quote);
                        i++;
                        continue;
                    }
                    quote = 0;
                }
                continue;
            }
            if (c == SQ || c == DQ || c == BT) {
                quote = c;
                cur.append(c);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == separator && depth <= 0) {
                parts.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        parts.add(cur.toString());
        return parts;
    }

    /**
     * 找到 from 位置上左括号对应的右括号下标。
     *
     * @return 右括号下标；找不到（括号不配对）返回 -1
     */
    static int matchingParen(String text, int openIndex) {
        if (text == null || openIndex < 0 || openIndex >= text.length() || text.charAt(openIndex) != '(') {
            return -1;
        }
        int depth = 0;
        char quote = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote != BT) {
                    i++;
                    continue;
                }
                if (c == quote) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                        i++;
                        continue;
                    }
                    quote = 0;
                }
                continue;
            }
            if (c == SQ || c == DQ || c == BT) {
                quote = c;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 在关键字列表中找出最早出现在"括号外、引号外"位置的关键字下标。
     * 用于把列定义切成 "类型部分" 和 "约束部分"。
     *
     * @param text      待扫描文本（调用方保证是单条语句/单个列定义）
     * @param keywords  大写关键字集合
     * @return 命中位置；没命中返回 -1
     */
    static int indexOfFirstKeyword(String text, List<String> keywords) {
        int best = -1;
        for (String kw : keywords) {
            int idx = indexOfKeyword(text, kw);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    /** 查找单个大写关键字在括号外、引号外的首次出现位置，要求词边界 */
    static int indexOfKeyword(String text, String upperKeyword) {
        if (text == null || upperKeyword.isEmpty()) {
            return -1;
        }
        int depth = 0;
        char quote = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote != BT) {
                    i++;
                    continue;
                }
                if (c == quote) {
                    if (i + 1 < n && text.charAt(i + 1) == quote) {
                        i++;
                        continue;
                    }
                    quote = 0;
                }
                continue;
            }
            if (c == SQ || c == DQ || c == BT) {
                quote = c;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }
            if (depth != 0) {
                continue;
            }
            if (matchWordAt(text, i, upperKeyword)) {
                return i;
            }
        }
        return -1;
    }

    /** 判断 pos 处是否正好是一个大写关键字（前后要求词边界） */
    static boolean matchWordAt(String text, int pos, String upperWord) {
        int len = upperWord.length();
        if (pos + len > text.length()) {
            return false;
        }
        for (int k = 0; k < len; k++) {
            char expected = upperWord.charAt(k);
            char actual = Character.toUpperCase(text.charAt(pos + k));
            if (expected != actual && !(expected == '_' && actual == '_')) {
                return false;
            }
        }
        if (pos > 0 && isWordChar(text.charAt(pos - 1))) {
            return false;
        }
        int after = pos + len;
        if (after < text.length() && isWordChar(text.charAt(after))) {
            return false;
        }
        return true;
    }

    static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** 关键字是否作为独立单词存在（引号/括号内不算） */
    static boolean containsKeyword(String text, String upperKeyword) {
        return indexOfKeyword(text, upperKeyword) >= 0;
    }

    /**
     * 返回文本中第一个标识符（含引号包裹）结束的位置，方便把 "列名 类型 ..." 切开。
     * 直接按名字再查找一遍是不行的：反引号里的名字清理掉引号后，在原文里找不到正确位置。
     */
    static int firstTokenEnd(String text) {
        if (text == null) {
            return 0;
        }
        int i = 0;
        int n = text.length();
        while (i < n && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= n) {
            return i;
        }
        char c = text.charAt(i);
        if (c == SQ || c == DQ || c == BT) {
            i++;
            while (i < n) {
                char d = text.charAt(i);
                if (d == '\\' && c != BT) {
                    i += 2;
                    continue;
                }
                if (d == c) {
                    if (i + 1 < n && text.charAt(i + 1) == c) {
                        i += 2;
                        continue;
                    }
                    return i + 1;
                }
                i++;
            }
            return n;
        }
        if (c == '[') {
            int close = text.indexOf(']', i);
            return close < 0 ? i + 1 : close + 1;
        }
        while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_'
                || text.charAt(i) == '.' || text.charAt(i) == '$')) {
            i++;
        }
        return i;
    }

    /** 读取第一个标识符的原始文本（含引号） */
    static String firstToken(String text) {
        int end = firstTokenEnd(text);
        return end > 0 ? text.substring(0, end).trim() : "";
    }

    /**
     * 把 SQL Server 风格的方括号标识符转成反引号风格，后面的扫描逻辑就只需要认一种引号。
     * 空方括号（例如 PostgreSQL 的 int[]）不转换，避免造出空标识符。
     */
    static String unbracket(String text) {
        if (text == null || text.indexOf('[') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        char quote = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (quote != 0) {
                sb.append(c);
                if (c == '\\' && quote != BT && i + 1 < n) {
                    sb.append(text.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    if (i + 1 < n && text.charAt(i + 1) == quote) {
                        sb.append(quote);
                        i += 2;
                        continue;
                    }
                    quote = 0;
                }
                i++;
                continue;
            }
            if (c == SQ || c == DQ || c == BT) {
                quote = c;
                sb.append(c);
                i++;
                continue;
            }
            if (c == '[') {
                int close = text.indexOf(']', i + 1);
                String inner = close < 0 ? "" : text.substring(i + 1, close);
                if (close > 0 && !inner.isEmpty() && inner.indexOf(SQ) < 0 && inner.indexOf('\n') < 0) {
                    sb.append(BT).append(inner).append(BT);
                    i = close + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
