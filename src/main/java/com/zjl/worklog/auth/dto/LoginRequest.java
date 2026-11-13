package com.zjl.worklog.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "username不能为空")
    private String username;

    @NotBlank(message = "password不能为空")
    private String password;

    @NotBlank(message = "请刷新验证码")
    @Size(max = 64, message = "请刷新验证码")
    private String captchaId;

    @NotBlank(message = "请输入验证码")
    @Size(max = 8, message = "验证码不正确")
    private String captchaCode;
}
