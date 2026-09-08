package com.zjl.worklog.ticket;

/**
 * 工单图片的 objectKey 约定，集中一处，避免「上传时一个路径、校验时另一个路径」。
 *
 * <p>关键点：工单图片放在既有 aliyun.oss.dir-prefix 之下（如 work-records/tickets/渠道码/2026/09/xxx.jpg），
 * 这样 OssController 现有的 assertKeyAllowed（要求 key 以 dir-prefix 开头）不用改就能放行，
 * 员工在受理台预览报修图片走的还是原来那条已验证过的通路。
 */
public final class TicketImageKeys {

    /** 渠道目录名，落在 dir-prefix 之下 */
    public static final String CHANNEL_ROOT = "tickets/";

    private TicketImageKeys() {
    }

    /** 某渠道的图片目录前缀，例如 work-records/tickets/AB12CD34EF/ */
    public static String channelPrefix(String dirPrefix, String channelCode) {
        return normalizeDir(dirPrefix) + CHANNEL_ROOT + safeSegment(channelCode) + "/";
    }

    /**
     * 把「完整访问地址」或「objectKey」统一还原成 objectKey。
     *
     * <p>前端 utils/oss.js 的 uploadToOss() 返回的是 https://bucket.host/dir/xx.jpg 这种完整地址
     * （工作记录一直就是这么存的），而权限校验只能按 key 前缀判断。
     * 不做这层归一化，受理人给自己工单附图会被误判成越权。
     */
    public static String toKey(String urlOrKey) {
        if (urlOrKey == null) {
            return "";
        }
        String v = urlOrKey.trim();
        if (v.isEmpty()) {
            return "";
        }
        int scheme = v.indexOf("://");
        if (scheme >= 0) {
            int slash = v.indexOf('/', scheme + 3);
            // 只有域名没有路径：视为非法输入，返回空串由上层拒绝
            return slash < 0 ? "" : stripQueryAndSlash(v.substring(slash + 1));
        }
        return stripQueryAndSlash(v);
    }

    /**
     * 校验图片是否属于该渠道。入参可以是完整 URL 或 objectKey。
     *
     * <p>不做这层校验，攻击者就能把别人的 objectKey 塞进自己的工单，
     * 借报修人的合法视图读到别人的附件。
     */
    public static boolean belongsToChannel(String dirPrefix, String channelCode, String urlOrKey) {
        String key = toKey(urlOrKey);
        if (key.isEmpty()) {
            return false;
        }
        return key.startsWith(channelPrefix(dirPrefix, channelCode));
    }

    private static String stripQueryAndSlash(String value) {
        String v = value;
        int query = v.indexOf('?');
        if (query >= 0) {
            v = v.substring(0, query);
        }
        while (v.startsWith("/")) {
            v = v.substring(1);
        }
        return v;
    }

    /** 去掉路径穿越与非法字符，渠道码本身是十六进制，这里只兜底 */
    private static String safeSegment(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "");
    }

    private static String normalizeDir(String dir) {
        if (dir == null || dir.isBlank()) {
            return "";
        }
        String d = dir.trim();
        if (d.startsWith("/")) {
            d = d.substring(1);
        }
        if (!d.isEmpty() && !d.endsWith("/")) {
            d = d + "/";
        }
        return d;
    }
}
