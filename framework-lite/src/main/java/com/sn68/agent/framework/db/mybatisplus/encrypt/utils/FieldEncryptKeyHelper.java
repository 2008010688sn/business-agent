package com.sn68.agent.framework.db.mybatisplus.encrypt.utils;

import com.sn68.agent.framework.db.mybatisplus.encrypt.encryptor.AESEncryptor;
import com.sn68.agent.framework.db.mybatisplus.encrypt.encryptor.Encryptor;
import com.sn68.agent.framework.db.properties.DatabaseProperties;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 * @author 钱丁君-chandler 2025/12/15
 */
@Slf4j
public class FieldEncryptKeyHelper {
    @Setter
    @Getter
    private DatabaseProperties.EncryptField encryptProperties;
    private Encryptor encryptor;
    private static final AtomicBoolean IS_INIT = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        IS_INIT.compareAndSet(false,encryptProperties.isEnabled() && StringUtils.isNotBlank(encryptProperties.getKey()));
        if (IS_INIT.getAcquire()) {
            // 初始化加密算法
            this.encryptor = new AESEncryptor();;
            this.encryptor.validateKey(encryptProperties.getKey());
            log.info("FieldEncryptKeyHelper 初始化完成");
        }

    }

    /**
     * 加密
     *
     * @param plainText 明文
     * @return 密文
     */
    public String encrypt(String plainText) {
        if (StringUtils.isBlank(plainText) ||
                !IS_INIT.getAcquire()||
                isEncrypted(plainText)) {
            return plainText;
        }
        return encryptProperties.getKeyTagPrefix() + encryptor.encrypt(plainText, encryptProperties.getKey());
    }

    /**
     * 解密方法(先尝试当前密钥，失败则尝试旧密钥)
     *
     * @param cipherText 密文
     * @return 明文
     */
    public String decrypt(String cipherText) {
        if (StringUtils.isBlank(cipherText) ||
                !IS_INIT.getAcquire()||
                !isEncrypted(cipherText)) {
            return cipherText;
        }

        //去除加密标识
        cipherText = cipherText.substring(encryptProperties.getKeyTagPrefix().length());

        try {
            // 先用当前密钥解密
            return encryptor.decrypt(cipherText, encryptProperties.getKey());
        } catch (Exception e) {
            // 当前密钥解密失败，尝试旧密钥
            String oldKey = getOldKey();
            if (oldKey != null) {
                return encryptor.decrypt(cipherText, oldKey);
            }
            // 无旧密钥或旧密钥也解密失败，抛出异常
            throw e;
        }
    }

    /**
     * 判断是否已经加密过了，加密过了不能再次加密。
     *
     * @param value 值
     * @return true：已加密；false：未加密
     */
    public boolean isEncrypted(String value) {
        return value.startsWith(encryptProperties.getKeyTagPrefix());
    }

    private String getOldKey() {
        if (StringUtils.isBlank(encryptProperties.getOldKey())) {
            return encryptProperties.getKey();
        }
        return encryptProperties.getOldKey();
    }
}
