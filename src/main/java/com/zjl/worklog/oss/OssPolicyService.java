package com.zjl.worklog.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.MatchMode;
import com.aliyun.oss.model.PolicyConditions;
import com.zjl.worklog.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 访客态（手机端报修人）图片直传签名。
 *
 * <p>为什么不复用 OssController#policy：那个接口第一件事就是 requireLogin()，
 * 报修人没有账号；更重要的是两者的**授权语义不同**——
 * 登录用户可以上传 pdf/docx 到 work-records/ 下任意子目录，
 * 访客只能把图片写进「自己这个渠道」的目录，且只有 5MB、只能图片。
 * 把两套规则塞进一个方法只会让它充满 if(是否登录)，所以这里独立成一个明显更严的策略。
 *
 * <p>返回结构与 /api/oss/policy 完全一致（host/policy/signature/key/...），
 * 前端 uploadToOss 那段直传代码可以原样复用。
 */
@Service
public class OssPolicyService {

    /** 访客只允许图片，且不允许可内嵌脚本的 svg */
    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private static final Map<String, String> IMAGE_MIME = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "gif", "image/gif",
            "webp", "image/webp"
    );

    /** 单张图片上限：手机压缩后 5MB 足够，再大就是没压缩 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    /** 直传签名有效期：30 秒，只够一次 PUT */
    private static final long POLICY_EXPIRE_SECONDS = 30L;

    @Value("${aliyun.oss.endpoint:}")
    private String endpoint;

    @Value("${aliyun.oss.bucket:}")
    private String bucket;

    @Value("${aliyun.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret:}")
    private String accessKeySecret;

    /**
     * 为指定目录签发图片直传策略。
     *
     * @param dir      目录前缀，必须已经由 TicketImageKeys.channelPrefix 生成（含 dir-prefix）
     * @param filename 原始文件名，只用于取扩展名；objectKey 一律服务端重新生成
     */
    public Map<String, Object> imagePolicy(String dir, String filename) {
        ensureConfigured();

        String ext = extensionOf(filename);
        if (!IMAGE_EXT.contains(ext)) {
            throw new BizException(40001, "只允许上传图片（jpg/png/gif/webp）");
        }
        String contentType = IMAGE_MIME.get(ext);

        LocalDate now = LocalDate.now();
        String key = normalizeDir(dir)
                + now.getYear() + "/"
                + String.format("%02d", now.getMonthValue()) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + ext;

        long expireEndTime = System.currentTimeMillis() + POLICY_EXPIRE_SECONDS * 1000L;
        Date expiration = new Date(expireEndTime);

        OSS ossClient = new OSSClientBuilder().build(clientEndpoint(), accessKeyId, accessKeySecret);
        try {
            PolicyConditions conditions = new PolicyConditions();
            // key 完全由服务端决定：客户端无法指定文件名，也就无法覆盖别人的对象
            conditions.addConditionItem(PolicyConditions.COND_KEY, key);
            conditions.addConditionItem(PolicyConditions.COND_CONTENT_LENGTH_RANGE, 0, MAX_BYTES);
            conditions.addConditionItem(MatchMode.Exact, "Content-Type", contentType);
            conditions.addConditionItem(MatchMode.Exact, "success_action_status", "200");

            String postPolicy = ossClient.generatePostPolicy(expiration, conditions);
            String encodedPolicy = Base64.getEncoder().encodeToString(postPolicy.getBytes(StandardCharsets.UTF_8));
            String postSignature = ossClient.calculatePostSignature(postPolicy);

            String host = "https://" + bucket + "." + endpointHost();

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("host", host);
            resp.put("accessKeyId", accessKeyId);
            resp.put("policy", encodedPolicy);
            resp.put("signature", postSignature);
            resp.put("key", key);
            resp.put("expire", expireEndTime / 1000);
            // url 仅用于前端本地预览定位；真实访问一律走工单代理接口
            resp.put("url", host + "/" + key);
            resp.put("contentType", contentType);
            resp.put("maxBytes", MAX_BYTES);
            return resp;
        } finally {
            try {
                ossClient.shutdown();
            } catch (Exception ignored) {
                // 关闭失败不影响已经签发出的策略
            }
        }
    }

    private void ensureConfigured() {
        if (!StringUtils.hasText(accessKeyId) || !StringUtils.hasText(accessKeySecret)) {
            throw new BizException(500, "OSS AccessKey 未配置，无法接收图片");
        }
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(bucket)) {
            throw new BizException(500, "OSS endpoint/bucket 未配置，无法接收图片");
        }
    }

    private String clientEndpoint() {
        String e = endpoint == null ? "" : endpoint.trim();
        return (e.startsWith("http://") || e.startsWith("https://")) ? e : "https://" + e;
    }

    private String endpointHost() {
        String e = endpoint == null ? "" : endpoint.trim();
        if (e.startsWith("http://") || e.startsWith("https://")) {
            try {
                String host = java.net.URI.create(e).getHost();
                return host != null ? host : e.replaceFirst("^https?://", "");
            } catch (Exception ignored) {
                return e.replaceFirst("^https?://", "");
            }
        }
        return e;
    }

    private static String extensionOf(String name) {
        if (!StringUtils.hasText(name)) {
            return "";
        }
        String n = name.trim();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) {
            n = n.substring(slash + 1);
        }
        int q = n.indexOf('?');
        if (q >= 0) {
            n = n.substring(0, q);
        }
        int dot = n.lastIndexOf('.');
        if (dot < 0 || dot == n.length() - 1) {
            return "";
        }
        return n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeDir(String dir) {
        if (!StringUtils.hasText(dir)) {
            return "";
        }
        String d = dir.trim();
        if (d.startsWith("/")) {
            d = d.substring(1);
        }
        // 目录由服务端拼接，这里再挡一层路径穿越，避免有人把 dir 传成 ../../
        d = d.replace("..", "");
        if (!d.isEmpty() && !d.endsWith("/")) {
            d = d + "/";
        }
        return d;
    }
}
