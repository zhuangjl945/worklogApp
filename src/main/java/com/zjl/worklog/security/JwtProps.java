package com.zjl.worklog.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "jwt")
public class JwtProps {

    private String secret;
    private long expireSeconds = 7200;
}
