package com.zjl.worklog.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.OSSObject;
import com.zjl.worklog.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;

/**
 * 服务端代理读取 OSS 对象。
 *
 * <p>报修人查进度时不给 OSS 直链：直链一旦被人转发出去就是长期可访问的凭证，
 * 与「凭单号 + 一次性令牌访问」的授权模型不符。所有图片一律经服务端校验后再吐出去。
 */
@Service
public class OssObjectReader {

    @Value("${aliyun.oss.endpoint:}")
    private String endpoint;

    @Value("${aliyun.oss.bucket:}")
    private String bucket;

    @Value("${aliyun.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret:}")
    private String accessKeySecret;

    /** 读出对象字节；调用方必须先完成权限校验 */
    public byte[] read(String key) {
        if (!StringUtils.hasText(key)) {
            throw new BizException(40001, "缺少文件标识");
        }
        if (!StringUtils.hasText(accessKeyId) || !StringUtils.hasText(accessKeySecret)) {
            throw new BizException(500, "OSS AccessKey 未配置");
        }
        String e = endpoint == null ? "" : endpoint.trim();
        String clientEndpoint = (e.startsWith("http://") || e.startsWith("https://")) ? e : "https://" + e;

        OSS ossClient = new OSSClientBuilder().build(clientEndpoint, accessKeyId, accessKeySecret);
        try (OSSObject object = ossClient.getObject(bucket, key);
             InputStream in = object.getObjectContent()) {
            return in.readAllBytes();
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(40404, "文件不存在或已被清理");
        } finally {
            try {
                ossClient.shutdown();
            } catch (Exception ignored) {
            }
        }
    }
}
