package com.zjl.worklog.oss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OssKeysTest {

    @Test
    @DisplayName("完整地址去掉域名后得到 objectKey")
    void fullUrlToKey() {
        assertEquals(
                "work-records/work-records/2026/09/abc.jpg",
                OssKeys.toKey("https://worklog-oss.oss-cn-hangzhou.aliyuncs.com/work-records/work-records/2026/09/abc.jpg"));
    }

    @Test
    @DisplayName("签名地址上的查询串不能进 key，否则删对象会对不上")
    void signedUrlQueryIsStripped() {
        assertEquals(
                "work-records/a.jpg",
                OssKeys.toKey("https://worklog-oss.oss-cn-hangzhou.aliyuncs.com/work-records/a.jpg?Expires=1&Signature=x"));
    }

    @Test
    @DisplayName("已经是 objectKey 时原样返回")
    void bareKeyUnchanged() {
        assertEquals("work-records/a.jpg", OssKeys.toKey("work-records/a.jpg"));
    }

    @Test
    @DisplayName("SDK 签出来的 http 地址改成 https，避免页面本身是 https 时被浏览器当混合内容拦掉")
    void preferHttpsRewritesSchemeOnly() {
        assertEquals(
                "https://bucket.oss-cn-hangzhou.aliyuncs.com/k.jpg?Expires=1",
                OssKeys.preferHttps("http://bucket.oss-cn-hangzhou.aliyuncs.com/k.jpg?Expires=1"));
        assertEquals(
                "https://bucket.oss-cn-hangzhou.aliyuncs.com/k.jpg",
                OssKeys.preferHttps("https://bucket.oss-cn-hangzhou.aliyuncs.com/k.jpg"));
        assertEquals("", OssKeys.preferHttps(""));
    }
}
