package com.zjl.worklog.oss;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.MatchMode;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PolicyConditions;
import com.aliyun.oss.model.ResponseHeaderOverrides;
import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.config.SysConfigService;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.UserContext;
import com.zjl.worklog.security.RequireRole;
import com.zjl.worklog.security.Role;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
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

    private static final Logger log = LoggerFactory.getLogger(OssController.class);

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

    /**
     * 只用于「这个 key 是否还在被业务记录引用」的一次性只读检查（见 countObjectReferences），
     * 不值得为它在四个模块里各加一个 mapper 方法。
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SysConfigService sysConfig;


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

        OSS ossClient = new OSSClientBuilder().build(ossClientEndpoint(), accessKeyId, accessKeySecret);
        try {
            PolicyConditions conditions = new PolicyConditions();
            conditions.addConditionItem(PolicyConditions.COND_KEY, key);
            // 大小上限取「参数配置 → 文件上传」的当前值（已夹到 1~50MB），不再硬编码
            long maxBytes = sysConfig.maxUploadBytes();
            conditions.addConditionItem(PolicyConditions.COND_CONTENT_LENGTH_RANGE, 0, maxBytes);
            conditions.addConditionItem(MatchMode.Exact, "Content-Type", resolvedType);
            conditions.addConditionItem(MatchMode.Exact, "Content-Disposition", contentDisposition);
            conditions.addConditionItem(MatchMode.Exact, "success_action_status", "200");

            String postPolicy = ossClient.generatePostPolicy(expiration, conditions);
            byte[] binaryData = postPolicy.getBytes(StandardCharsets.UTF_8);
            String encodedPolicy = Base64.getEncoder().encodeToString(binaryData);
            String postSignature = ossClient.calculatePostSignature(postPolicy);

            String host = "https://" + bucket + "." + endpointHost();
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
        assertKeyAllowed(key);

        OSS ossClient = new OSSClientBuilder().build(ossClientEndpoint(), accessKeyId, accessKeySecret);
        try {
            byte[] data = readObjectBytes(ossClient, key);
            String contentType = resolveContentType(data, key);
            String ext = extensionOf(key);
            String kind = kindOf(contentType, ext);
            boolean previewable = "pdf".equals(kind) || "image".equals(kind);
            String downloadName = suggestedFilename(key, ext, contentType);

            Date expiration = new Date(System.currentTimeMillis() + 10 * 60 * 1000L);
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key, HttpMethod.GET);
            request.setExpiration(expiration);
            ResponseHeaderOverrides overrides = new ResponseHeaderOverrides();
            overrides.setContentType(contentType);
            overrides.setContentDisposition(previewable ? "inline" : "attachment");
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
            log.warn("生成预览地址失败", e);
            throw new BizException(500, "生成预览地址失败");
        } finally {
            shutdownQuietly(ossClient);
        }
    }

    @GetMapping("/file")
    public void file(@RequestParam String url, HttpServletResponse response) {
        requireLogin();
        ensureOssConfigured();

        String key = extractKey(url);
        assertKeyAllowed(key);

        OSS ossClient = new OSSClientBuilder().build(ossClientEndpoint(), accessKeyId, accessKeySecret);
        try {
            byte[] data = readObjectBytes(ossClient, key);
            String contentType = resolveContentType(data, key);
            String ext = extensionOf(key);
            String kind = kindOf(contentType, ext);
            boolean previewable = "pdf".equals(kind) || "image".equals(kind);
            String downloadName = suggestedFilename(key, ext, contentType);

            response.setStatus(200);
            response.setContentType(contentType);
            response.setContentLength(data.length);
            response.setHeader("Content-Disposition",
                    (previewable ? "inline" : "attachment")
                            + "; filename=\"" + asciiFilename(downloadName) + "\"");
            response.setHeader("Cache-Control", "private, max-age=60");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.getOutputStream().write(data);
            response.getOutputStream().flush();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("读取预览文件失败 key={}", key, e);
            throw new BizException(500, "读取文件失败");
        } finally {
            shutdownQuietly(ossClient);
        }
    }

    /**
     * 删除 OSS 对象。
     *
     * <p>这是全站唯一一个「删了就没法恢复」的接口，所以门槛刻意比其他 OSS 接口更高：
     * 1) 只有系统管理员能调——附件一旦删掉，工单/合同/工作记录里的引用全成死链，这类事故找回成本最高；
     * 2) 仍被业务记录引用的 key 一律拒删，把「清理没提交的孤儿文件」和「误删在用文件」彻底分开；
     * 3) 成功和被拒都记日志，事后可追责到具体的人。
     */
    @DeleteMapping("/object")
    @RequireRole(value = Role.ADMIN, message = "仅系统管理员可删除已上传的文件")
    public ApiResponse<Boolean> deleteObject(@RequestParam String key) {
        CurrentUser cu = requireLogin();
        ensureOssConfigured();

        if (!StringUtils.hasText(key)) {
            throw new BizException(400, "key 不能为空");
        }

        String k = key.trim();
        if (k.startsWith("/")) k = k.substring(1);

        assertKeyAllowed(k);

        long refs = countObjectReferences(k);
        if (refs > 0) {
            log.warn("拒绝删除仍被引用的 OSS 对象: operator={} userId={} key={} refs={}", cu.getUsername(), cu.getId(), k, refs);
            throw new BizException(40005, "该文件仍被业务记录引用，不能直接删除；请先在对应记录里移除附件");
        }

        OSS ossClient = new OSSClientBuilder().build(ossClientEndpoint(), accessKeyId, accessKeySecret);
        try {
            ossClient.deleteObject(bucket, k);
            log.info("OSS 对象已删除: operator={} userId={} key={}", cu.getUsername(), cu.getId(), k);
            return ApiResponse.ok(true);
        } catch (Exception e) {
            log.error("删除 OSS 对象失败: operator={} key={}", cu.getUsername(), k, e);
            throw new BizException(500, "删除 OSS 对象失败");
        } finally {
            shutdownQuietly(ossClient);
        }
    }

    /**
     * 这个 key 是否仍被业务记录引用。
     *
     * <p>四张表都是「JSON 数组文本」与「单个 URL」混存的历史格式，只能按子串匹配；
     * 宁可把误判方向留在「认为它还在用」而拒绝删除，也绝不反过来放行。
     * 为一次低频删除检查在四个模块各加一个 mapper 方法不划算，这里直接用一条只读 SQL。
     */
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

    /** 转义 LIKE 通配符：key 里出现 % 或 _ 时不该把匹配范围放大 */
    private static String escapeLike(String raw) {
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

    private String endpointHost() {
        String e = endpoint == null ? "" : endpoint.trim();
        if (e.startsWith("http://") || e.startsWith("https://")) {
            try {
                String host = URI.create(e).getHost();
                return host != null ? host : e.replaceFirst("^https?://", "");
            } catch (Exception ignored) {
                return e.replaceFirst("^https?://", "");
            }
        }
        return e;
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
        String path = uri.getRawPath();
        if (!StringUtils.hasText(path)) {
            throw new BizException(400, "非法文件地址");
        }
        if (path.startsWith("/")) path = path.substring(1);
        try {
            path = URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        if (!StringUtils.hasText(path)) {
            throw new BizException(400, "非法文件地址");
        }

        String hostLc = host == null ? "" : host.toLowerCase(Locale.ROOT);
        String virtualHost = (bucket + "." + endpointHost()).toLowerCase(Locale.ROOT);
        String endpointLc = endpointHost().toLowerCase(Locale.ROOT);
        String bucketLc = bucket.toLowerCase(Locale.ROOT);

        if (hostLc.equals(virtualHost) || hostLc.startsWith(bucketLc + ".")) {
            return path;
        }
        if (hostLc.equals(endpointLc) || hostLc.endsWith(".aliyuncs.com")) {
            String prefix = bucket + "/";
            if (path.startsWith(prefix) || path.startsWith(bucketLc + "/")) {
                return path.substring(path.indexOf('/') + 1);
            }
            if (path.startsWith(normalizeDir(dirPrefix))) {
                return path;
            }
        }
        log.warn("拒绝预览地址 host={} expected={} path={}", host, virtualHost, path);
        throw new BizException(400, "非法文件地址");
    }

    private byte[] readObjectBytes(OSS ossClient, String key) {
        OSSObject object = null;
        try {
            object = ossClient.getObject(bucket, key);
            try (InputStream in = object.getObjectContent()) {
                return in.readAllBytes();
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("读取 OSS 对象失败 key={}", key, e);
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

    private String resolveContentType(byte[] data, String key) {
        String sniffed = detectMime(data);
        String ext = extensionOf(key);
        return firstNonBlank(sniffed, mimeFromExt(ext), "application/octet-stream");
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
        int q = n.indexOf('?');
        if (q >= 0) n = n.substring(0, q);
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
