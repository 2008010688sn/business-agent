package com.sn68.agent.framework.db.mybatisplus.encrypt.encryptor;

/**
 *
 * @author 钱丁君-chandler 2025/12/15
 */
public interface Encryptor {
    /**
     * 校验密钥的格式
     * @param key 秘钥
     */
    void validateKey(String key) ;

    /**
     * 加密
     * @param plainText 明文
     * @param key 秘钥
     * @return 密文
     */
    String encrypt(String plainText, String key);

    /**
     * 解密
     * @param cipherText 密文
     * @param key 秘钥
     * @return 明文
     */
    String decrypt(String cipherText, String key);
}
