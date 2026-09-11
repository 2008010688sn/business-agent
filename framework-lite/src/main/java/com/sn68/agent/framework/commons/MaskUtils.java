/*
 * Copyright (c) 2023 xx-cloud Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sn68.agent.framework.commons;

import cn.hutool.core.util.DesensitizedUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 脱敏工具类
 * <p>
 * 基于 Hutool DesensitizedUtil 封装，提供统一的脱敏方法。
 * 适用于日志输出、审计记录等场景的手动脱敏。
 * <p>
 * 注意：API 响应脱敏请使用 @Sensitive 注解
 *
 * @author framework-team
 * @since 4.0.0
 * @see cn.hutool.core.util.DesensitizedUtil
 */
public final class MaskUtils {
    
    private static final String MASK = "****";
    private static final String NOT_AVAILABLE = "N/A";
    private static final int SHORT_THRESHOLD = 8;
    private static final int FINGERPRINT_THRESHOLD = 12;
    private static final int FINGERPRINT_PREFIX = 8;
    private static final int FINGERPRINT_SUFFIX = 4;
    
    private MaskUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
    
    // ==================== 通用脱敏 ====================
    
    /**
     * 通用值脱敏
     * <p>
     * 保留前4位和后4位，中间用****替换
     *
     * @param value 原始值
     * @return 脱敏后的值
     */
    public static String maskValue(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        if (value.length() <= SHORT_THRESHOLD) {
            return MASK;
        }
        return value.substring(0, 4) + MASK + value.substring(value.length() - 4);
    }
    
    /**
     * 设备指纹脱敏
     * <p>
     * 保留前8位和后4位，中间用****替换
     *
     * @param fingerprint 原始设备指纹
     * @return 脱敏后的设备指纹
     */
    public static String maskFingerprint(String fingerprint) {
        if (StrUtil.isBlank(fingerprint)) {
            return null;
        }
        if (fingerprint.length() <= FINGERPRINT_THRESHOLD) {
            return fingerprint;
        }
        return fingerprint.substring(0, FINGERPRINT_PREFIX) + MASK 
                + fingerprint.substring(fingerprint.length() - FINGERPRINT_SUFFIX);
    }
    
    /**
     * Key 脱敏（用于日志输出）
     *
     * @param key 原始 key
     * @return 脱敏后的 key
     */
    public static String maskKey(String key) {
        if (key == null || key.length() <= SHORT_THRESHOLD) {
            return key;
        }
        return key.substring(0, 4) + MASK + key.substring(key.length() - 4);
    }
    
    // ==================== 基于 Hutool 的标准脱敏 ====================
    
    /**
     * 手机号脱敏
     * <p>
     * 前3后4：138****5678
     *
     * @param phone 手机号
     * @return 脱敏后的手机号
     */
    public static String maskPhone(String phone) {
        if (StrUtil.isBlank(phone)) {
            return null;
        }
        return DesensitizedUtil.mobilePhone(phone);
    }
    
    /**
     * 邮箱脱敏
     * <p>
     * 保留首字符和@后域名：t***@example.com
     *
     * @param email 邮箱
     * @return 脱敏后的邮箱
     */
    public static String maskEmail(String email) {
        if (StrUtil.isBlank(email)) {
            return null;
        }
        return DesensitizedUtil.email(email);
    }
    
    /**
     * 身份证号脱敏
     * <p>
     * 保留前6后4：110101********1234
     *
     * @param idCard 身份证号
     * @return 脱敏后的身份证号
     */
    public static String maskIdCard(String idCard) {
        if (StrUtil.isBlank(idCard)) {
            return null;
        }
        return DesensitizedUtil.idCardNum(idCard, 6, 4);
    }
    
    /**
     * 银行卡号脱敏
     * <p>
     * 保留前4后4：6222****7890
     *
     * @param bankCard 银行卡号
     * @return 脱敏后的银行卡号
     */
    public static String maskBankCard(String bankCard) {
        if (StrUtil.isBlank(bankCard)) {
            return null;
        }
        return DesensitizedUtil.bankCard(bankCard);
    }
    
    /**
     * 中文姓名脱敏
     * <p>
     * 两字保留首字：张*
     * 三字及以上保留首尾：张*三
     *
     * @param name 姓名
     * @return 脱敏后的姓名
     */
    public static String maskName(String name) {
        if (StrUtil.isBlank(name)) {
            return null;
        }
        return DesensitizedUtil.chineseName(name);
    }
    
    /**
     * 地址脱敏
     * <p>
     * 保留前6个字符，其余用*替换
     *
     * @param address 地址
     * @return 脱敏后的地址
     */
    public static String maskAddress(String address) {
        if (StrUtil.isBlank(address)) {
            return null;
        }
        return DesensitizedUtil.address(address, 6);
    }
    
    /**
     * IPv4 地址脱敏
     * <p>
     * 保留前两段：192.168.*.*
     *
     * @param ip IP地址
     * @return 脱敏后的IP地址
     */
    public static String maskIpv4(String ip) {
        if (StrUtil.isBlank(ip)) {
            return null;
        }
        return DesensitizedUtil.ipv4(ip);
    }
    
    /**
     * IPv6 地址脱敏
     *
     * @param ip IPv6地址
     * @return 脱敏后的IPv6地址
     */
    public static String maskIpv6(String ip) {
        if (StrUtil.isBlank(ip)) {
            return null;
        }
        return DesensitizedUtil.ipv6(ip);
    }
    
    /**
     * 密码脱敏
     * <p>
     * 全部替换为 ******
     *
     * @param password 密码
     * @return 脱敏后的密码
     */
    public static String maskPassword(String password) {
        if (StrUtil.isBlank(password)) {
            return null;
        }
        return DesensitizedUtil.password(password);
    }
    
    /**
     * 车牌号脱敏
     *
     * @param carLicense 车牌号
     * @return 脱敏后的车牌号
     */
    public static String maskCarLicense(String carLicense) {
        if (StrUtil.isBlank(carLicense)) {
            return null;
        }
        return DesensitizedUtil.carLicense(carLicense);
    }
    
    /**
     * 座机号脱敏
     *
     * @param phone 座机号
     * @return 脱敏后的座机号
     */
    public static String maskFixedPhone(String phone) {
        if (StrUtil.isBlank(phone)) {
            return null;
        }
        return DesensitizedUtil.fixedPhone(phone);
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 空值安全处理
     *
     * @param value 原始值
     * @return 如果为空返回 N/A，否则返回原值
     */
    public static String nullSafe(String value) {
        return StrUtil.isNotBlank(value) ? value : NOT_AVAILABLE;
    }
    
    /**
     * 自定义脱敏
     * <p>
     * 保留指定前缀和后缀长度，中间用****替换
     *
     * @param value        原始值
     * @param prefixLength 前缀保留长度
     * @param suffixLength 后缀保留长度
     * @return 脱敏后的值
     */
    public static String mask(String value, int prefixLength, int suffixLength) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        int minLength = prefixLength + suffixLength;
        if (value.length() <= minLength) {
            return MASK;
        }
        return value.substring(0, prefixLength) + MASK + value.substring(value.length() - suffixLength);
    }
}
