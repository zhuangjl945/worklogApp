package com.zjl.worklog.auth;

import com.zjl.worklog.auth.dto.LoginRequest;
import com.zjl.worklog.auth.dto.LoginResponse;
import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.JwtProps;
import com.zjl.worklog.security.JwtTokenService;
import com.zjl.worklog.security.UserContext;
import com.zjl.worklog.user.entity.UserEntity;
import com.zjl.worklog.user.mapper.UserMapper;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final JwtProps jwtProps;

    public AuthController(UserMapper userMapper,
                          PasswordEncoder passwordEncoder,
                          JwtTokenService tokenService,
                          JwtProps jwtProps) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.jwtProps = jwtProps;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        UserEntity user = userMapper.selectByUsername(req.getUsername());
        if (user == null) {
            throw new BizException(4001, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(4002, "账号已被禁用");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BizException(4001, "用户名或密码错误");
        }

        String token = tokenService.generateToken(user.getId(), user.getUsername(), user.getDeptId(), user.getRealName());

        LoginResponse.UserView userView = new LoginResponse.UserView(
                user.getId(), user.getUsername(), user.getRealName(), user.getDeptId(), user.getStatus()
        );

        return ApiResponse.ok(new LoginResponse("Bearer", token, jwtProps.getExpireSeconds(), userView));
    }

    @GetMapping("/me")
    public ApiResponse<LoginResponse.UserView> me() {
        CurrentUser cu = UserContext.get();
        if (cu == null) {
            throw new BizException(401, "未登录或登录已过期");
        }
        UserEntity user = userMapper.selectById(cu.getId());
        if (user == null) {
            throw new BizException(401, "未登录或登录已过期");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(4002, "账号已被禁用");
        }
        return ApiResponse.ok(new LoginResponse.UserView(user.getId(), user.getUsername(), user.getRealName(), user.getDeptId(), user.getStatus()));
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout() {
        return ApiResponse.ok(true);
    }
}
