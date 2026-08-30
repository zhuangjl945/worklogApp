package com.zjl.worklog.oss;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.MatchMode;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PolicyConditions;
import com.aliyun.oss.model.ResponseHeaderOverrides;
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

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/oss")
public class OssController {

    private static final Set<String> ALLOWED_EXT = Set.of(
            "pdf", "jpg", "jpeg", "png", "gif", "webp", "doc", "docx"
    );

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
    public ApiResponse<Map<String, Object>> policy(
            @RequestParam(required = false) String dir,
            @RequestParam(required = false) String filename,
            @RequestParam(required = false) String contentType
    ) {
        requireLogin();
        ensureOssConfigured();

        String ext = extensionOf(filename);
        String resolvedType = firstNonBlank(contentType, mimeFromExt(ext));
        if (!StringUtils.hasText(resolvedType)) {
            resolvedType = "application/octet-stream";
        }
        String contentDisposition = "inline";

        String resolvedDir = normalizeDir(dirPrefix) + normalizeDir(dir);

        LocalDate now = LocalDate.now();
        String objectKeyPrefix = resolvedDir
                + now.getYear() + "/"
                + String.format("%02d", now.getMonthValue()) + "/";

        String objectName = UUID.randomUUID().toString().replace("-", "");
        if (StringUtils.hasText(ext)) {
            objectName = objectName + "." + ext;
        }
        String key = objectKeyPrefix + objectName;

        long expireSeconds = 30;
        long expireEndTime = System.currentTimeMillis() + expireSeconds * 1000;
        var expiration = new Date(expireEndTime);

        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            PolicyConditions conditions = new PolicyConditions();
            conditions.addConditionItem(PolicyConditions.COND_KEY, key);
            conditions.addConditionItem(PolicyConditions.COND_CONTENT_LENGTH_RANGE, 0, 10L * 1024 * 1024);
            conditions.addConditionItem(MatchMode.Exact, "Content-Type", resolvedType);
            conditions.addConditionItem(MatchMode.Exact, "Content-Disposition", contentDisposition);
            conditions.addConditionItem(MatchMode.Exact, "success_action_status", "200");

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
            resp.put("contentType", resolvedType);
            resp.put("contentDisposition", contentDisposition);

            return ApiResponse.ok(resp);
        } finally {
            shutdownQuietly(ossClient);
        }
    }

    @GetMapping("/preview-url")
    public ApiResponse<Map<String, Object>> previewUrl(@RequestParam String url) {
        requireLogin();
        ensureOssConfigured();

        String key = extractKey(url);
        String prefix = normalizeDir(dirPrefix);
        if (StringUtils.hasText(prefix) && !key.startsWith(prefix)) {
            throw new BizException(403, "不允许访问该对象");
        }

        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            String sniffed = sniffContentType(ossClient, key);
            String ext = extensionOf(key);
            String contentType = firstNonBlank(sniffed, mimeFromExt(ext), "application/octet-stream");
            String kind = kindOf(contentType, ext);
            boolean previewable = "pdf".equals(kind) || "image".equals(kind);

            String downloadName = suggestedFilename(key, ext, contentType);
            String disposition = (previewable ? "inline" : "attachment")
                    + "; filename=\"" + asciiFilename(downloadName) + "\""
                    + "; filename*=UTF-8''" + encodeRfc5987(downloadName);

            Date expiration = new Date(System.currentTimeMillis() + 10 * 60 * 1000L);
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key, HttpMethod.GET);
            request.setExpiration(expiration);
            ResponseHeaderOverrides overrides = new ResponseHeaderOverrides();
            overrides.setContentType(contentType);
            overrides.setContentDisposition(disposition);
            request.setResponseHeaders(overrides);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("url", ossClient.generatePresignedUrl(request).toString());
            resp.put("contentType", contentType);
            resp.put("kind", kind);
            resp.put("previewable", previewable);
            resp.put("filename", downloadName);
            return ApiResponse.ok(resp);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(500, "生成预览地址失败");
        } finally {
            shutdownQuietly(ossClient);
        }
    }

    @DeleteMapping("/object")
    public ApiResponse<Boolean> deleteObject(@RequestParam String key) {
        requireLogin();
        ensureOssConfigured();

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
            shutdownQuietly(ossClient);
        }
    }

    private void ensureOssConfigured() {
        if (!StringUtils.hasText(accessKeyId) || !StringUtils.hasText(accessKeySecret)) {
            throw new BizException(500, "OSS AccessKey 未配置");
        }
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(bucket)) {
            throw new BizException(500, "OSS endpoint/bucket 未配置");
        }
    }

    private String extractKey(String fileUrl) {
        if (!StringUtils.hasText(fileUrl)) {
            throw new BizException(400, "url 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(fileUrl.trim());
        } catch (Exception e) {
            throw new BizException(400, "非法文件地址");
        }
        String host = uri.getHost();
        String expected = bucket + "." + endpoint;
        if (host == null || !host.equalsIgnoreCase(expected)) {
            throw new BizException(400, "非法文件地址");
        }
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            throw new BizException(400, "非法文件地址");
        }
        String key = path.startsWith("/") ? path.substring(1) : path;
        if (!StringUtils.hasText(key)) {
            throw new BizException(400, "非法文件地址");
        }
        return key;
    }

    private String sniffContentType(OSS ossClient, String key) {
        GetObjectRequest getReq = new GetObjectRequest(bucket, key);
        getReq.setRange(0, 15);
        OSSObject object = null;
        try {
            object = ossClient.getObject(getReq);
            try (InputStream in = object.getObjectContent()) {
                byte[] head = in.readNBytes(16);
                return detectMime(head);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(404, "文件不存在或无法读取");
        } finally {
            if (object != null) {
                try {
                    object.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String detectMime(byte[] head) {
        if (head == null || head.length < 4) return null;
        if (head[0] == 0x25 && head[1] == 0x50 && head[2] == 0x44 && head[3] == 0x46) {
            return "application/pdf";
        }
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if ((head[0] & 0xFF) == 0x89 && head[1] == 0x50 && head[2] == 0x4E && head[3] == 0x47) {
            return "image/png";
        }
        if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') {
            return "image/gif";
        }
        if (head.length >= 12
                && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return "image/webp";
        }
        if ((head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF
                && (head[2] & 0xFF) == 0x11 && (head[3] & 0xFF) == 0xE0) {
            return "application/msword";
        }
        if (head[0] == 'P' && head[1] == 'K') {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        return null;
    }

    private static String mimeFromExt(String ext) {
        if (!StringUtils.hasText(ext)) return null;
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> null;
        };
    }

    private static String kindOf(String contentType, String ext) {
        String t = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (t.contains("pdf") || "pdf".equals(ext)) return "pdf";
        if (t.startsWith("image/") || Set.of("jpg", "jpeg", "png", "gif", "webp").contains(ext == null ? "" : ext)) {
            return "image";
        }
        if (t.contains("word") || t.contains("officedocument") || "doc".equals(ext) || "docx".equals(ext)) {
            return "office";
        }
        return "other";
    }

    private static String extensionOf(String name) {
        if (!StringUtils.hasText(name)) return "";
        String n = name.trim();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot < 0 || dot == n.length() - 1) return "";
        String ext = n.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXT.contains(ext)) return "";
        return ext;
    }

    private static String suggestedFilename(String key, String ext, String contentType) {
        String base = "attachment";
        if (StringUtils.hasText(key)) {
            int slash = key.lastIndexOf('/');
            String last = slash >= 0 ? key.substring(slash + 1) : key;
            if (StringUtils.hasText(last) && last.contains(".")) {
                return last;
            }
        }
        String inferred = StringUtils.hasText(ext) ? ext : extFromMime(contentType);
        return StringUtils.hasText(inferred) ? base + "." + inferred : base;
    }

    private static String extFromMime(String contentType) {
        if (!StringUtils.hasText(contentType)) return "";
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "application/pdf" -> "pdf";
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "application/msword" -> "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            default -> "";
        };
    }

    private static String asciiFilename(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 32 && c < 127 && c != '"' && c != '\\') sb.append(c);
            else sb.append('_');
        }
        return sb.isEmpty() ? "file" : sb.toString();
    }

    private static String encodeRfc5987(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (StringUtils.hasText(v)) return v.trim();
        }
        return null;
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

    private static void shutdownQuietly(OSS ossClient) {
        if (ossClient == null) return;
        try {
            ossClient.shutdown();
        } catch (Exception ignored) {
        }
    }
}
