package com.zjl.worklog.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.PolicyConditions;
import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/oss")
public class OssController {

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

    @GetMapping("/policy")
    public ApiResponse<Map<String, Object>> policy(@RequestParam(required = false) String dir) {
        requireLogin();

        if (!StringUtils.hasText(accessKeyId) || !StringUtils.hasText(accessKeySecret)) {
            throw new BizException(500, "OSS AccessKey 未配置");
        }
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(bucket)) {
            throw new BizException(500, "OSS endpoint/bucket 未配置");
        }

        String resolvedDir = normalizeDir(dirPrefix) + normalizeDir(dir);

        LocalDate now = LocalDate.now();
        String objectKeyPrefix = resolvedDir
                + now.getYear() + "/"
                + String.format("%02d", now.getMonthValue()) + "/";

        String fileName = UUID.randomUUID().toString().replace("-", "");
        String key = objectKeyPrefix + fileName;

        long expireSeconds = 30;
        long expireEndTime = System.currentTimeMillis() + expireSeconds * 1000;
        var expiration = new java.util.Date(expireEndTime);

        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            PolicyConditions conditions = new PolicyConditions();
            conditions.addConditionItem(PolicyConditions.COND_KEY, key);
            // 限制直传 OSS 的文件大小（单位：byte）。前端目前提示为 5MB，这里保持一致。
            conditions.addConditionItem(PolicyConditions.COND_CONTENT_LENGTH_RANGE, 0, 5L * 1024 * 1024);

            String postPolicy = ossClient.generatePostPolicy(expiration, conditions);
            byte[] binaryData = postPolicy.getBytes(StandardCharsets.UTF_8);
            String encodedPolicy = Base64.getEncoder().encodeToString(binaryData);
            String postSignature = ossClient.calculatePostSignature(postPolicy);

            String host = "https://" + bucket + "." + endpoint;
            String url = host + "/" + key;

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("host", host);
            resp.put("accessKeyId", accessKeyId);
            resp.put("policy", encodedPolicy);
            resp.put("signature", postSignature);
            resp.put("key", key);
            resp.put("expire", expireEndTime / 1000);
            resp.put("url", url);

            return ApiResponse.ok(resp);
        } finally {
            try {
                ossClient.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    @DeleteMapping("/object")
    public ApiResponse<Boolean> deleteObject(@RequestParam String key) {
        requireLogin();

        if (!StringUtils.hasText(accessKeyId) || !StringUtils.hasText(accessKeySecret)) {
            throw new BizException(500, "OSS AccessKey 未配置");
        }
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(bucket)) {
            throw new BizException(500, "OSS endpoint/bucket 未配置");
        }
        if (!StringUtils.hasText(key)) {
            throw new BizException(400, "key 不能为空");
        }

        String k = key.trim();
        if (k.startsWith("/")) k = k.substring(1);

        String prefix = normalizeDir(dirPrefix);
        if (StringUtils.hasText(prefix) && !k.startsWith(prefix)) {
            throw new BizException(403, "不允许删除该对象");
        }

        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            ossClient.deleteObject(bucket, k);
            return ApiResponse.ok(true);
        } catch (Exception e) {
            throw new BizException(500, "删除 OSS 对象失败");
        } finally {
            try {
                ossClient.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    private String normalizeDir(String dir) {
        if (!StringUtils.hasText(dir)) return "";
        String d = dir.trim();
        if (d.startsWith("/")) d = d.substring(1);
        if (!d.isEmpty() && !d.endsWith("/")) d = d + "/";
        return d;
    }

    private CurrentUser requireLogin() {
        CurrentUser cu = UserContext.get();
        if (cu == null) {
            throw new BizException(401, "未登录或登录已过期");
        }
        return cu;
    }
}
