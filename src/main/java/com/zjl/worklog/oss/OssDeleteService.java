package com.zjl.worklog.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.zjl.worklog.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 删除 OSS 对象的唯一入口：先查业务引用，确认没人用再删。
 *
 * <p>登录用户和报修人（公开接口）都走这里，避免两套引用检查各写各的。
 */
@Service
public class OssDeleteService {

    private static final Logger log = LoggerFactory.getLogger(OssDeleteService.class);

    private final JdbcTemplate jdbcTemplate;

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.bucket}")
    private String bucket;

    @Value("${aliyun.oss.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.dir-prefix:}")
    private String dirPrefix;

    public OssDeleteService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param keyOrUrl 完整地址或 objectKey
     * @param operator 日志用的操作者标识（用户名或 visitor:渠道码）
     */
    public void deleteUnreferenced(String keyOrUrl, String operator) {
        ensureOssConfigured();

        String k = OssKeys.toKey(keyOrUrl);
        if (!StringUtils.hasText(k)) {
            throw new BizException(400, "key 不能为空");
        }

        assertKeyAllowed(k);

        long refs = countObjectReferences(k);
        if (refs > 0) {
            log.warn("拒绝删除仍被引用的 OSS 对象: operator={} key={} refs={}", operator, k, refs);
            throw new BizException(40005, "该文件仍被业务记录引用，不能直接删除；请先在对应记录里移除附件");
        }

        OSS ossClient = new OSSClientBuilder().build(ossClientEndpoint(), accessKeyId, accessKeySecret);
        try {
            ossClient.deleteObject(bucket, k);
            log.info("OSS 对象已删除: operator={} key={}", operator, k);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("删除 OSS 对象失败: operator={} key={}", operator, k, e);
            throw new BizException(500, "删除 OSS 对象失败");
        } finally {
            try {
                ossClient.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    private long countObjectReferences(String key) {
        String like = "%" + escapeLike(key) + "%";
        Long total = jdbcTemplate.queryForObject(
                "SELECT (SELECT COUNT(1) FROM work_record WHERE deleted = 0 AND image_urls LIKE ?) "
                        + "+ (SELECT COUNT(1) FROM contract_main WHERE deleted = 0 AND contract_file_url LIKE ?) "
                        + "+ (SELECT COUNT(1) FROM service_ticket WHERE deleted = 0 AND image_urls LIKE ?) "
                        + "+ (SELECT COUNT(1) FROM service_ticket_log WHERE image_urls LIKE ?)",
                Long.class, like, like, like, like);
        return total == null ? 0L : total;
    }

    static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private void ensureOssConfigured() {
        if (!StringUtils.hasText(accessKeyId) || !StringUtils.hasText(accessKeySecret)) {
            throw new BizException(500, "OSS AccessKey 未配置");
        }
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(bucket)) {
            throw new BizException(500, "OSS endpoint/bucket 未配置");
        }
    }

    private void assertKeyAllowed(String key) {
        String prefix = normalizeDir(dirPrefix);
        if (StringUtils.hasText(prefix) && !key.startsWith(prefix)) {
            throw new BizException(403, "不允许访问该对象");
        }
    }

    private String ossClientEndpoint() {
        String e = endpoint == null ? "" : endpoint.trim();
        if (e.startsWith("http://") || e.startsWith("https://")) return e;
        return "https://" + e;
    }

    private static String normalizeDir(String dir) {
        if (!StringUtils.hasText(dir)) return "";
        String d = dir.trim();
        if (d.startsWith("/")) d = d.substring(1);
        if (!d.isEmpty() && !d.endsWith("/")) d = d + "/";
        return d;
    }
}
