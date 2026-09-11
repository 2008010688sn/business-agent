package com.sn68.agent.framework.commons.security;

import cn.hutool.crypto.symmetric.AES;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * AES 加解密工具类
 * <p>Hutool AES 内部每次操作会创建新的 Cipher 实例，本身线程安全，无需 ThreadLocal</p>
 *
 * @author linjun
 * @since 2025/6/4
 **/
@Slf4j
public class AesUtil {

    private static final String DEFAULT_KEY = "xx@2025052900000";

    private static final AES AES_INSTANCE = new AES(DEFAULT_KEY.getBytes(StandardCharsets.UTF_8));

    private AesUtil() {
    }

    public static String encrypt(String content) {
        return Base64.getEncoder().encodeToString(AES_INSTANCE.encrypt(content));
    }

    public static String decrypt(String cipherText) {
        try {
            byte[] decoded = Base64.getDecoder().decode(cipherText);
            return new String(AES_INSTANCE.decrypt(decoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Base64 解码失败，说明不是加密内容
            log.debug("非加密内容，原样返回: {}", cipherText);
            return cipherText;
        } catch (Exception e) {
            // AES 解密失败（密文不完整、密钥不匹配等），原样返回
            log.debug("AES解密失败，原样返回: {}, error: {}", cipherText, e.getMessage());
            return cipherText;
        }
    }

    public static Optional<String> tryDecrypt(String cipherText) {
        try {
            byte[] decoded = Base64.getDecoder().decode(cipherText);
            return Optional.of(new String(AES_INSTANCE.decrypt(decoded), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.debug("AES解密失败: {}, error: {}", cipherText, e.getMessage());
            return Optional.empty();
        }
    }
}
