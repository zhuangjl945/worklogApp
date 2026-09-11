package com.zjl.worklog.oss;

/**
 * OSS 对象标识归一化。
 *
 * <p>历史原因：前端 uploadToOss() 一直把 https://bucket.host/dir/x.jpg 这种完整地址写进业务字段，
 * 而权限校验、签名、删除都只能按 objectKey 做。bucket 换成自定义域名或改 endpoint 时，
 * 存量完整地址就会失效，所以新数据一律存 key，读取侧统一用这里做兼容归一化。
 */
public final class OssKeys {

    private OssKeys() {
    }

    /** 把「完整访问地址」或「objectKey」统一还原成 objectKey；无法识别时返回空串，由上层拒绝 */
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
            // 只有域名没有路径：视为非法输入
            return slash < 0 ? "" : stripQueryAndSlash(v.substring(slash + 1));
        }
        return stripQueryAndSlash(v);
    }

    /**
     * 阿里云 Java SDK 在未显式指定 Protocol.HTTPS 时，generatePresignedUrl 经常给出 http://。
     * 管理端页面走 https，浏览器会把 http 图片当混合内容直接拦掉，看起来就是「加载失败」。
     * 签名串签的是 method/expires/resource，改 scheme 不影响验签。
     */
    public static String preferHttps(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        if (url.regionMatches(true, 0, "http://", 0, 7)) {
            return "https://" + url.substring(7);
        }
        return url;
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
}
