/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.framework.commons.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM 文本加解密工具。
 */
public final class AesGcmTextCrypto {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	private static final String KEY_ALGORITHM = "AES";

	private static final int IV_LENGTH_BYTES = 12;

	private static final int TAG_LENGTH_BITS = 128;

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private AesGcmTextCrypto() {
	}

	public static String encrypt(String plainText, String key) {
		if (plainText == null) {
			return null;
		}
		try {
			byte[] iv = new byte[IV_LENGTH_BYTES];
			SECURE_RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, secretKey(key), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + cipherBytes.length)
				.put(iv)
				.put(cipherBytes)
				.array());
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("AES-GCM encrypt failed", ex);
		}
	}

	public static String decrypt(String cipherText, String key) {
		if (cipherText == null) {
			return null;
		}
		try {
			byte[] payload = Base64.getDecoder().decode(cipherText);
			if (payload.length <= IV_LENGTH_BYTES) {
				throw new IllegalArgumentException("AES-GCM payload is too short");
			}
			byte[] iv = new byte[IV_LENGTH_BYTES];
			byte[] cipherBytes = new byte[payload.length - IV_LENGTH_BYTES];
			ByteBuffer.wrap(payload).get(iv).get(cipherBytes);
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, secretKey(key), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException | GeneralSecurityException ex) {
			throw new IllegalStateException("AES-GCM decrypt failed", ex);
		}
	}

	public static void validateKey(String key) {
		secretKey(key);
	}

	private static SecretKeySpec secretKey(String key) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("AES-GCM key must not be blank");
		}
		byte[] rawKey = decodeKey(key.trim());
		if (rawKey.length != 16 && rawKey.length != 24 && rawKey.length != 32) {
			throw new IllegalArgumentException("AES-GCM key length must be 16, 24 or 32 bytes");
		}
		return new SecretKeySpec(rawKey, KEY_ALGORITHM);
	}

	private static byte[] decodeKey(String key) {
		try {
			byte[] decoded = Base64.getDecoder().decode(key);
			if (decoded.length == 16 || decoded.length == 24 || decoded.length == 32) {
				return decoded;
			}
		}
		catch (IllegalArgumentException ignored) {
			// 非 Base64 时按普通 UTF-8 密钥处理。
		}
		return key.getBytes(StandardCharsets.UTF_8);
	}

}
