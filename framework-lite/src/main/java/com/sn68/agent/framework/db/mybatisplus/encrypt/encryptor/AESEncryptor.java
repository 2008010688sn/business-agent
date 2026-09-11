package com.sn68.agent.framework.db.mybatisplus.encrypt.encryptor;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.symmetric.SymmetricAlgorithm;
import com.sn68.agent.framework.db.mybatisplus.encrypt.exception.FieldEncryptException;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;


/**
 *
 * @author 钱丁君-chandler 2025/12/15
 */
@Slf4j
public class AESEncryptor implements Encryptor {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    @Override
    public String encrypt(String plaintext, String key) {
        if (StrUtil.isEmpty(plaintext)) return plaintext;
        try {
            byte[] keyBytes = getRawKey(key);
            byte[] iv = new byte[IV_LENGTH];
            //能够保证 IV 的不可预测性
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, SymmetricAlgorithm.AES.getValue()), new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(CharsetUtil.CHARSET_UTF_8));
            byte[] combined = ArrayUtil.addAll(iv, encrypted);
            return Base64.encode(combined);
        } catch (Exception e) {
            throw new FieldEncryptException("AES加密失败", e);
        }
    }

    @Override
    public String decrypt(String ciphertext, String key) {
        if (StrUtil.isEmpty(ciphertext)) return ciphertext;
        try {
            byte[] keyBytes = getRawKey(key);
            byte[] combined = Base64.decode(ciphertext);
            // 截取前12位作为IV
            byte[] iv = ArrayUtil.sub(combined, 0, IV_LENGTH);
            // 截取剩余部分
            byte[] actualCiphertext = ArrayUtil.sub(combined, IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, SymmetricAlgorithm.AES.getValue()), new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(actualCiphertext), CharsetUtil.CHARSET_UTF_8);
        } catch (Exception e) {
            throw new FieldEncryptException("AES解密失败", e);
        }
    }

    /**
     * 如果是 Base64 格式，进行解码
     */
    private byte[] getRawKey(String key) {
        if (StrUtil.isEmpty(key)) {
            throw new FieldEncryptException("密钥不能为空");
        }
        int keyLength;
        if (Base64.isBase64(key)) {
            byte[] decoded = Base64.decode(key);
            keyLength = decoded.length;
            if (keyLength == 16 || keyLength == 24 || keyLength == 32) {
                return decoded;
            }
        }
        keyLength = key.getBytes().length;
        if (keyLength == 16 || keyLength == 24 || keyLength == 32) {
            return key.getBytes(CharsetUtil.CHARSET_UTF_8);
        }
        throw new FieldEncryptException("AES密钥长度必须为16/24/32字节");
    }

    @Override
    public void validateKey(String key) {
        getRawKey(key);
    }

}
