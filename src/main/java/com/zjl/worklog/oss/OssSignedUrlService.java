package com.zjl.worklog.oss;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.zjl.worklog.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 短时签名读地址。
 *
 * <p>存在的意义：bucket 一旦收敛成私有读，前端就不能再拿 https://bucket.host/key 直接渲染，
 * 否则等于把「谁能读到文件」交给了一条会被人转发、会进浏览器历史的永久地址。
 *
 * <p>与 OssController#previewUrl 的分工：那条要下载整个对象来嗅探 Content-Type 并决定
 * inline/attachment，适合「点开一个附件预览」；而列表页一屏可能有几十张缩略图，
 * 每张都全量拉进堆内存是必然要出事的，所以这里只做「校验 + 签名」，一个字节都不读。
 *
 * <p>签名不改写响应头：图片上传时已经把 Content-Type 一起签进 policy，
 * OSS 返回的就是正确的 image/*，浏览器能直接渲染。
 */
@Service
public class OssSignedUrlService {

    private static final Logger log = LoggerFactory.getLogger(OssSignedUrlService.class);

    /** 下限：太短会在页面还没渲染完就过期 */
    private static final int MIN_TTL_SECONDS = 60;
    /** 上限：签名地址会被写进浏览器历史与代理日志，不给它长时间有效 */
    private static final int MAX_TTL_SECONDS = 600;
    private static final int DEFAULT_TTL_SECONDS = 300;

    @Value("${aliyun.oss.endpoint:}")
    private String endpoint;

    @Value("${aliyun.oss.bucket:}")
    private String bucket;

    @Value("${aliyun.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret:}")
    private String accessKeySecret;

    @Value("${aliyun.oss.dir-prefix:}")
    private String dirPrefix;

        /**
     * 批量换签名读地址。入参可以是完整地址或 objectKey；越权/非法的条目返回 null 而不是整体报错，
     * 因为列表页里混进一条脏数据不该让整屏图片都显示不出来。
     *
     * @return 原始入参 -> 签名地址（越权或地址非法时为 null），顺序与入参一致
     */
    public Map<String, String> signBatch(Collection<String> urlOrKeys, Integer ttlSeconds) {
        Map<String, String> out = new LinkedHashMap<>();
        if (urlOrKeys == null || urlOrKeys.isEmpty()) {
            return out;
        }
        ensureConfigured();
        final int ttl = clampTtl(ttlSeconds);

        // 先按入参归类，再对去重后的 key 逐个签名，最后按原始入参回填：
        // 同一条记录在列表页和编辑框里各出现一次时也只签一次。
        Map<String, String> inputToKey = new LinkedHashMap<>();
        Set<String> toSign = new LinkedHashSet<>();
        for (String input : urlOrKeys) {
            if (input == null) {
                continue;
            }
            String key = OssKeys.toKey(input);
            if (key.isEmpty() || !allowed(key)) {
                inputToKey.put(input, null);
                continue;
            }
            inputToKey.put(input, key);
            toSign.add(key);
        }

        Map<String, String> signedByKey = new LinkedHashMap<>();
        OSS ossClient = new OSSClientBuilder().build(clientEndpoint(), accessKeyId, accessKeySecret);
        try {
            Date expiration = new Date(System.currentTimeMillis() + ttl * 1000L);
            for (String key : toSign) {
                GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key, HttpMethod.GET);
                request.setExpiration(expiration);
                signedByKey.put(key, OssKeys.preferHttps(ossClient.generatePresignedUrl(request).toString()));
            }
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("生成签名地址失败", ex);
            throw new BizException(500, "生成文件地址失败");
        } finally {
            try {
                ossClient.shutdown();
            } catch (Exception ignored) {
            }
        }

        for (Map.Entry<String, String> e : inputToKey.entrySet()) {
            String key = e.getValue();
            if (key == null) {
                log.warn("拒绝签名越权或非法对象地址 input={}", e.getKey());
                out.put(e.getKey(), null);
            } else {
                out.put(e.getKey(), signedByKey.get(key));
            }
        }
        return out;
    }

    private int clampTtl(Integer ttlSeconds) {
        if (ttlSeconds == null) {
            return DEFAULT_TTL_SECONDS;
        }
        if (ttlSeconds < MIN_TTL_SECONDS) {
            return MIN_TTL_SECONDS;
        }
        return Math.min(ttlSeconds, MAX_TTL_SECONDS);
    }

    private void ensureConfigured() {
        if (!StringUtils.hasText(accessKeyId) || !StringUtils.hasText(accessKeySecret)) {
            throw new BizException(500, "OSS AccessKey 未配置");
        }
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(bucket)) {
            throw new BizException(500, "OSS endpoint/bucket 未配置");
        }
    }

    /** 与 OssController 一致：只允许本站目录前缀下的对象，避免拿任意 key 换地址 */
    private boolean allowed(String key) {
        String prefix = dirPrefix == null ? "" : dirPrefix.trim();
        if (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        if (!StringUtils.hasText(prefix)) {
            return true;
        }
        return key.startsWith(prefix);
    }

    private String clientEndpoint() {
        String e = endpoint == null ? "" : endpoint.trim();
        if (e.startsWith("http://") || e.startsWith("https://")) {
            return e;
        }
        return "https://" + e;
    }
}
