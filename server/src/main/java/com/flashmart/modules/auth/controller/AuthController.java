package com.flashmart.modules.auth.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.flashmart.common.exception.BizException;
import com.flashmart.common.limiter.SlidingWindowLimit;
import com.flashmart.common.result.R;
import com.flashmart.modules.auth.entity.SysUser;
import com.flashmart.modules.auth.mapper.SysUserMapper;
import com.flashmart.common.auth.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Tag(name = "认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String SESSION_KEY_PREFIX = "session:token:";

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    @Operation(summary = "注册（买家 / 商家）")
    @PostMapping("/register")
    public R<Long> register(@RequestBody @Validated RegisterRequest request) {
        Long exists = sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getPhone, request.phone()));
        if (exists != null && exists > 0) {
            throw new BizException("该手机号已注册");
        }
        SysUser user = new SysUser();
        user.setPhone(request.phone());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname());
        user.setRole(request.role() == null ? SysUser.ROLE_BUYER : request.role());
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        sysUserMapper.insert(user);
        return R.ok(user.getId());
    }

    /** 登录按 IP 维度限流：防止撞库 */
    @Operation(summary = "登录")
    @SlidingWindowLimit(key = "login", windowSeconds = 1, maxRequests = 20,
            dimension = SlidingWindowLimit.Dimension.IP, message = "登录过于频繁，请稍后再试")
    @PostMapping("/login")
    public R<LoginResult> login(@RequestBody @Validated LoginRequest request) {
        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getPhone, request.phone()).last("limit 1"));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BizException("手机号或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException("账号已被禁用");
        }
        String token = jwtUtil.generate(user.getId(), user.getRole());
        // 会话落 Redis：支持服务端踢人、滑动续期
        redis.opsForValue().set(SESSION_KEY_PREFIX + user.getId(), token, 24, TimeUnit.HOURS);
        return R.ok(new LoginResult(token, user.getId(), user.getNickname(), user.getRole()));
    }

    public record RegisterRequest(@NotBlank String phone, @NotBlank String password,
                                  String nickname, String role) {
    }

    public record LoginRequest(@NotBlank String phone, @NotBlank String password) {
    }

    public record LoginResult(String token, Long userId, String nickname, String role) {
    }
}
