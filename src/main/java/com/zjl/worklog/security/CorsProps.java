package com.zjl.worklog.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨域来源配置。
 *
 * <p>留空表示「完全不允许跨域」，此时前后端必须同源部署（当前生产就是这种：
 * 前端 dist 与后端同域，Nginx 只做静态托管和反代）。
 * 只有当手机端 H5 或管理端被部署到另一个域名/端口时，才需要填写来源，
 * 例如 CORS_ALLOWED_ORIGINS=https://work.example.cn 。
 */
@Data
@ConfigurationProperties(prefix = "cors")
public class CorsProps {

    /** 允许的前端来源，逗号分隔；不要使用 * 同时又允许携带凭证 */
    private List<String> allowedOrigins = new ArrayList<>();
}
