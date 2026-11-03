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
 * 压根没配（仓库不提供内置默认值，照文档启动就会踩到），以及密钥不足 32 字节
 * （JJWT 签发时才报 WeakKeyException，线上才暴露）。
 */
class JwtUtilTest {

    private JwtUtil jwtUtilWith(String secret) {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", secret);
        ReflectionTestUtils.setField(jwtUtil, "expireHours", 24L);
        return jwtUtil;
    }

    @Test
    @DisplayName("密钥未配置（空白）：启动校验失败，报错要点明设 JWT_SECRET，而不是说“请提供 JWT_SECRET”")
    void blankSecretIsRejected() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> jwtUtilWith("").validateSecret());

        assertTrue(error.getMessage().contains("未配置"), error.getMessage());
        assertTrue(error.getMessage().contains("JWT_SECRET"), error.getMessage());
    }

    @Test
    @DisplayName("密钥未配置（null）：与空白走同一条启动失败分支")
    void nullSecretIsRejected() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> jwtUtilWith(null).validateSecret());

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
