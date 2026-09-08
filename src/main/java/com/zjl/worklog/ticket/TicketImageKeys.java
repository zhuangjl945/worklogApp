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
     * 校验客户端回传的 key 是否属于该渠道。
     * 不做这层校验，攻击者就能把别人的 objectKey 塞进自己的工单，从而借报修人的合法视图读到别人的附件。
     */
    public static boolean belongsToChannel(String dirPrefix, String channelCode, String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return key.startsWith(channelPrefix(dirPrefix, channelCode));
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
