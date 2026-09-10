package com.zjl.worklog.auth;

import com.zjl.worklog.auth.dto.LoginRequest;
import com.zjl.worklog.auth.dto.LoginResponse;
import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.JwtTokenService;
import com.zjl.worklog.security.PasswordService;
import com.zjl.worklog.security.Role;
import com.zjl.worklog.security.UserContext;
import com.zjl.worklog.user.entity.UserEntity;
import com.zjl.worklog.user.mapper.UserMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserMapper userMapper;
    private final PasswordService passwordService;
    private final JwtTokenService tokenService;

    public AuthController(UserMapper userMapper,
                          PasswordService passwordService,
                          JwtTokenService tokenService) {
        this.userMapper = userMapper;
        this.passwordService = passwordService;
        this.tokenService = tokenService;
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
        if (!passwordService.matchesAndUpgrade(user, req.getPassword())) {
            throw new BizException(4001, "用户名或密码错误");
        }

        Role role = Role.of(user.getRole());
        String token = tokenService.generateToken(user.getId(), user.getUsername(), user.getDeptId(), user.getRealName(), role);

        // 刚签发的 token，生效角色必然等于库里角色，roleStale 恒为 false
        LoginResponse.UserView userView = new LoginResponse.UserView(
                user.getId(), user.getUsername(), user.getRealName(), user.getDeptId(), user.getStatus(),
                role.name(), role.getLabel(), false
        );

        // expireIn 必须回传实际签进 token 的那个值：参数页把有效期调短之后，
        // 还按 yml 报给前端就会出现「前端以为还有 8 小时，第 3 小时就 401」。
        return ApiResponse.ok(new LoginResponse("Bearer", token, tokenService.effectiveExpireSeconds(), userView));
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
        // 生效角色取 token 里的，不取库里的：菜单收口必须和接口收口用同一个值，
        // 否则会出现「界面给了入口、点进去 403」这种比少给入口更糟的体验。
        Role effective = cu.getRole();
        return ApiResponse.ok(new LoginResponse.UserView(
                user.getId(), user.getUsername(), user.getRealName(), user.getDeptId(), user.getStatus(),
                effective.name(), effective.getLabel(),
                // 库里的角色已不同于 token = 登录之后被管理员调整过，前端据此提示重新登录
                Role.of(user.getRole()) != effective));
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout() {
        return ApiResponse.ok(true);
    }
}
