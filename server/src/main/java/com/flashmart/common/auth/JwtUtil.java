package com.flashmart.common.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    /** application.yml 里 flashmart.jwt.secret 的默认值：随仓库公开，等同密钥已泄露，启动期必须拒绝 */
    private static final String DEFAULT_SECRET = "flashmart-dev-secret-key-please-change-in-production";

    /** HS256 的密钥下限（字节）：JJWT 签发时也会拒绝更短的 key，这里提前到启动期报错 */
    private static final int MIN_SECRET_BYTES = 32;

    @Value("${flashmart.jwt.secret:" + DEFAULT_SECRET + "}")
    private String secret;

    @Value("${flashmart.jwt.expire-hours:24}")
    private long expireHours;

    @PostConstruct
    void validateSecret() {
        if (DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException("flashmart.jwt.secret 仍是仓库内置的默认密钥，等同已经泄露；"
                    + "请用环境变量 JWT_SECRET 提供至少 " + MIN_SECRET_BYTES + " 字节的随机密钥");
        }
        int length = secret == null ? 0 : secret.getBytes(StandardCharsets.UTF_8).length;
        if (length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("flashmart.jwt.secret 只有 " + length + " 字节，HS256 要求至少 "
                    + MIN_SECRET_BYTES + " 字节；请用环境变量 JWT_SECRET 提供更长的随机密钥");
        }
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generate(Long userId, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireHours * 3600_000L))
                .signWith(key())
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }
}
