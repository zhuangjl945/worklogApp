package com.zjl.worklog.ticket;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.oss.OssPolicyService;
import com.zjl.worklog.ticket.dto.PublicSubmitRequest;
import com.zjl.worklog.ticket.dto.TicketMetaView;
import com.zjl.worklog.ticket.dto.TicketPublicView;
import com.zjl.worklog.ticket.entity.ServiceTicketEntity;
import com.zjl.worklog.ticket.entity.TicketChannelEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手机端免登录接口。
 *
 * <p>安全前提是「这里没有任何登录态」：
 * 1) 不读 UserContext；2) 渠道只由 form token 决定；3) 每个动作都有独立限流；
 * 4) 返回给报修人的视图一律走脱敏的 TicketPublicView。
 */
@Validated
@RestController
@RequestMapping("/api/public/tickets")
public class PublicTicketController {

    private final TicketChannelService channelService;
    private final TicketFormTokenService formTokenService;
    private final TicketRateLimiter rateLimiter;
    private final TicketService ticketService;
    private final OssPolicyService ossPolicyService;
    private final com.zjl.worklog.oss.OssObjectReader ossObjectReader;

    public PublicTicketController(TicketChannelService channelService,
                                  TicketFormTokenService formTokenService,
                                  TicketRateLimiter rateLimiter,
                                  TicketService ticketService,
                                  OssPolicyService ossPolicyService,
                                  com.zjl.worklog.oss.OssObjectReader ossObjectReader) {
        this.channelService = channelService;
        this.formTokenService = formTokenService;
        this.rateLimiter = rateLimiter;
        this.ticketService = ticketService;
        this.ossPolicyService = ossPolicyService;
        this.ossObjectReader = ossObjectReader;
    }

    /** 第一步：扫码进来先换填表凭证（申请本身就受单 IP 限流约束） */
    @PostMapping("/form-token")
    public ApiResponse<Map<String, Object>> formToken(@Valid @RequestBody FormTokenRequest req,
                                                      HttpServletRequest request) {
        String ip = clientIp(request);
        if (!rateLimiter.acquire("token:ip:" + ip, 30, 60)) {
            throw new BizException(42901, "操作过于频繁，请稍后再试");
        }
        TicketChannelEntity channel = channelService.requireEnabled(req.getChannelCode());
        String token = formTokenService.issue(channel.getId(), ip);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("formToken", token);
        data.put("expiresInSeconds", formTokenService.ttlSeconds());
        data.put("channelName", channel.getChannelName());
        return ApiResponse.ok(data);
    }

    /** 表单配置：渠道名 + 可选问题类型 + 必填规则 */
    @GetMapping("/meta")
    public ApiResponse<TicketMetaView> meta(@RequestParam String formToken) {
        Long channelId = formTokenService.require(formToken);
        return ApiResponse.ok(ticketService.meta(channelService.requireEnabledById(channelId)));
    }

    /** 图片直传签名：只允许图片、只有 5MB、目录锁死在本渠道 */
    @PostMapping("/upload-policy")
    public ApiResponse<Map<String, Object>> uploadPolicy(@Valid @RequestBody UploadPolicyRequest req) {
        Long channelId = formTokenService.require(req.getFormToken());
        TicketChannelEntity channel = channelService.requireEnabledById(channelId);
        if (!rateLimiter.acquire("upload:ch:" + channelId, 120, 60)) {
            throw new BizException(42901, "上传过于频繁，请稍后再试");
        }
        String dir = TicketImageKeys.channelPrefix(ossDirPrefix(), channel.getChannelCode());
        return ApiResponse.ok(ossPolicyService.imagePolicy(dir, req.getFilename()));
    }

    /** 提交问题 */
    @PostMapping
    public ApiResponse<Map<String, Object>> submit(@Valid @RequestBody PublicSubmitRequest req,
                                                   HttpServletRequest request) {
        TicketService.SubmitResult result = ticketService.submit(req, clientIp(request), request.getHeader("User-Agent"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketNo", result.getTicketNo());
        // accessToken 只在这一次响应里出现，库里只有它的 SHA-256；丢了只能找科室按单号查
        data.put("accessToken", result.getAccessToken());
        data.put("saved", !result.isFake());
        return ApiResponse.ok(data);
    }

    /** 凭单号 + 访问令牌查进度 */
    @GetMapping("/{ticketNo}")
    public ApiResponse<TicketPublicView> detail(@PathVariable String ticketNo,
                                                @RequestHeader(value = "X-Ticket-Auth", required = false) String auth) {
        ServiceTicketEntity ticket = ticketService.requireVisitorAccess(ticketNo, auth);
        return ApiResponse.ok(ticketService.publicDetail(ticket));
    }

    /** 报修人补充说明 */
    @PostMapping("/{ticketNo}/reply")
    public ApiResponse<Boolean> reply(@PathVariable String ticketNo,
                                      @RequestHeader(value = "X-Ticket-Auth", required = false) String auth,
                                      @Valid @RequestBody ReporterReplyRequest req) {
        ServiceTicketEntity ticket = ticketService.requireVisitorAccess(ticketNo, auth);
        ticketService.reporterReply(ticket, req.getRemark(), req.getImageKeys());
        return ApiResponse.ok(true);
    }

    /** 报修人确认已解决 */
    @PostMapping("/{ticketNo}/confirm")
    public ApiResponse<Boolean> confirm(@PathVariable String ticketNo,
                                       @RequestHeader(value = "X-Ticket-Auth", required = false) String auth) {
        ServiceTicketEntity ticket = ticketService.requireVisitorAccess(ticketNo, auth);
        ticketService.reporterConfirm(ticket);
        return ApiResponse.ok(true);
    }

    /** 报修人退回（24 小时内） */
    @PostMapping("/{ticketNo}/reopen")
    public ApiResponse<Boolean> reopen(@PathVariable String ticketNo,
                                       @RequestHeader(value = "X-Ticket-Auth", required = false) String auth,
                                       @Valid @RequestBody ReporterReopenRequest req) {
        ServiceTicketEntity ticket = ticketService.requireVisitorAccess(ticketNo, auth);
        ticketService.reporterReopen(ticket, req.getReason());
        return ApiResponse.ok(true);
    }

    /** 报修人评价 */
    @PostMapping("/{ticketNo}/rate")
    public ApiResponse<Boolean> rate(@PathVariable String ticketNo,
                                     @RequestHeader(value = "X-Ticket-Auth", required = false) String auth,
                                     @Valid @RequestBody ReporterRateRequest req) {
        ServiceTicketEntity ticket = ticketService.requireVisitorAccess(ticketNo, auth);
        ticketService.reporterRate(ticket, req.getRating(), req.getComment());
        return ApiResponse.ok(true);
    }

    /**
     * 图片代理：先认令牌，再把 OSS 对象吐出去。
     * 只允许读取该工单自己登记的 key，避免拿别人的单号读别人的附件。
     */
    @GetMapping("/{ticketNo}/image")
    public ResponseEntity<byte[]> image(@PathVariable String ticketNo,
                                       @RequestParam String key,
                                       @RequestHeader(value = "X-Ticket-Auth", required = false) String auth) {
        ServiceTicketEntity ticket = ticketService.requireVisitorAccess(ticketNo, auth);
        if (ticketService.findImage(ticket, key) == null) {
            throw new BizException(40303, "图片不属于该工单");
        }
        byte[] data = ossObjectReader.read(key);
        MediaType type = key.toLowerCase().endsWith(".png") ? MediaType.IMAGE_PNG
                : key.toLowerCase().endsWith(".gif") ? MediaType.IMAGE_GIF
                : key.toLowerCase().endsWith(".webp") ? MediaType.parseMediaType("image/webp")
                : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok()
                .contentType(type)
                .header("Cache-Control", "private, max-age=300")
                .body(data);
    }

    /** dir-prefix 由 TicketService 统一持有，这里只为拼目录再取一次配置 */
    @org.springframework.beans.factory.annotation.Value("${aliyun.oss.dir-prefix:}")
    private String ossDirPrefixValue;

    private String ossDirPrefix() {
        return ossDirPrefixValue;
    }

    /**
     * 取真实来访 IP。
     * application.yml 里开了 server.forward-headers-strategy=framework，
     * Spring 已经解析过 X-Forwarded-For，所以直接 getRemoteAddr 即可，不要再手工取头部。
     */
    private static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }

    public static class FormTokenRequest {
        @NotBlank(message = "缺少渠道信息，请重新扫码")
        @Size(max = 64)
        private String channelCode;

        public String getChannelCode() {
            return channelCode;
        }

        public void setChannelCode(String channelCode) {
            this.channelCode = channelCode;
        }
    }

    public static class UploadPolicyRequest {
        @NotBlank
        @Size(max = 64)
        private String formToken;
        @Size(max = 255)
        private String filename;

        public String getFormToken() {
            return formToken;
        }

        public void setFormToken(String formToken) {
            this.formToken = formToken;
        }

        public String getFilename() {
            return filename;
        }

        public void setFilename(String filename) {
            this.filename = filename;
        }
    }

    public static class ReporterReplyRequest {
        @NotBlank(message = "请填写补充内容")
        @Size(max = 2000, message = "补充内容不超过 2000 字")
        private String remark;
        @Size(max = 9)
        private List<String> imageKeys;

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }

        public List<String> getImageKeys() {
            return imageKeys;
        }

        public void setImageKeys(List<String> imageKeys) {
            this.imageKeys = imageKeys;
        }
    }

    public static class ReporterReopenRequest {
        @Size(max = 500)
        private String reason;

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    public static class ReporterRateRequest {
        private Integer rating;
        @Size(max = 255)
        private String comment;

        public Integer getRating() {
            return rating;
        }

        public void setRating(Integer rating) {
            this.rating = rating;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }
    }
}
