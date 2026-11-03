package com.flashmart.common.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密钥校验要在启动期挡下两类配置事故：
 * 沿用仓库内置的默认密钥（等同密钥已公开），以及密钥不足 32 字节（JJWT 签发时才报 WeakKeyException，线上才暴露）。
 */
class JwtUtilTest {

    private static final String DEFAULT_SECRET = "flashmart-dev-secret-key-please-change-in-production";

    private JwtUtil jwtUtilWith(String secret) {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", secret);
        ReflectionTestUtils.setField(jwtUtil, "expireHours", 24L);
        return jwtUtil;
    }

    @Test
    @DisplayName("密钥仍是内置默认值：启动校验失败，报错要点明用 JWT_SECRET 覆盖")
    void defaultSecretIsRejected() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> jwtUtilWith(DEFAULT_SECRET).validateSecret());

        assertTrue(error.getMessage().contains("JWT_SECRET"), error.getMessage());
    }

    @Test
    @DisplayName("密钥只有 31 字节：启动校验失败，报错里带上实际字节数")
    void shortSecretIsRejected() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> jwtUtilWith("a".repeat(31)).validateSecret());

        assertTrue(error.getMessage().contains("31"), error.getMessage());
    }

    @Test
    @DisplayName("32 字节密钥：校验通过，且签发/解析链路不受影响")
    void validSecretPassesAndStillSigns() {
        JwtUtil jwtUtil = jwtUtilWith("0123456789abcdef0123456789abcdef");

        assertDoesNotThrow(jwtUtil::validateSecret);
        assertEquals("9001", jwtUtil.parse(jwtUtil.generate(9001L, "BUYER")).getSubject());
    }
}
