package com.zjl.worklog.oss;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 私有读 bucket 下的「换地址」接口。
 *
 * <p>单独开一个 controller 而不是往 OssController 里加方法：那个文件此刻有在途改动，
 * 且它的授权语义是「登录 + 全站目录前缀」，与这里完全一致，只是不读对象内容。
 * 路径不冲突（/api/oss/sign-urls 与 /policy、/preview-url、/file、/object 互不重叠）。
 */
@Validated
@RestController
@RequestMapping("/api/oss")
public class OssSignController {

    /** 一次最多换 200 条：列表页一页 20 行、每行最多 5 张，留出足够余量又不至于被人拿来批量刷签名 */
    private static final int MAX_BATCH = 200;

    private final OssSignedUrlService signedUrlService;

    public OssSignController(OssSignedUrlService signedUrlService) {
        this.signedUrlService = signedUrlService;
    }

    @PostMapping("/sign-urls")
    public ApiResponse<Map<String, Object>> signUrls(@Valid @RequestBody SignRequest req) {
        requireLogin();
        List<String> inputs = new ArrayList<>(new java.util.LinkedHashSet<>(req.getUrls()));
        if (inputs.size() > MAX_BATCH) {
            throw new BizException(40001, "一次最多换 " + MAX_BATCH + " 个文件地址");
        }
        int ttl = req.getTtlSeconds() == null ? 300 : req.getTtlSeconds();
        Map<String, String> urls = signedUrlService.signBatch(inputs, ttl);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ttlSeconds", Math.max(60, Math.min(ttl, 600)));
        data.put("urls", urls);
        return ApiResponse.ok(data);
    }

    private CurrentUser requireLogin() {
        CurrentUser cu = UserContext.get();
        if (cu == null) {
            throw new BizException(401, "未登录或登录已过期");
        }
        return cu;
    }

    /** 请求体：url 支持完整地址与 objectKey 混传，兼容存量数据 */
    public static class SignRequest {
        @NotEmpty(message = "urls 不能为空")
        @Size(max = MAX_BATCH, message = "一次最多换 200 个文件地址")
        private List<String> urls;
        private Integer ttlSeconds;

        public List<String> getUrls() {
            return urls;
        }

        public void setUrls(List<String> urls) {
            this.urls = urls;
        }

        public Integer getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(Integer ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }
}
