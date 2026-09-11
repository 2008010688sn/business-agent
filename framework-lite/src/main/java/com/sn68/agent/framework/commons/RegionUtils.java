/*
 * Copyright (c) 2023 xx-cloud Authors. All Rights Reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.lionsoul.ip2region.xdb.LongByteArray;
import org.lionsoul.ip2region.xdb.Searcher;
import org.lionsoul.ip2region.xdb.Version;

import java.io.IOException;

/**
 * 根据ip查询地址
 *
 * @author Levin
 * @since 2019/10/30
 */
@Slf4j
public final class RegionUtils {

    private static final String DEFAULT_REGION = "内网";
    /**
     * IP 查询器，启动加载到内存中
     */
    private static Searcher IPV4_SEARCHER;
    private static Searcher IPV6_SEARCHER;

    static {
        try {
            long now = System.currentTimeMillis();
            IPV4_SEARCHER = getSearcher("ip2region_v4.xdb", Version.IPv4);
            IPV6_SEARCHER = getSearcher("ip2region_v6.xdb", Version.IPv6);
            log.info("启动加载 RegionUtils 成功，耗时 ({}) 毫秒", System.currentTimeMillis() - now);
        } catch (IOException e) {
            log.error("启动加载 RegionUtils 失败", e);
        }
    }

    private static Searcher getSearcher(String fileName, Version version) throws IOException {
        byte[] bytes = ResourceUtil.readBytes(fileName);
        LongByteArray longByteArray = new LongByteArray(bytes.length);
        longByteArray.append(bytes);
        return Searcher.newWithBuffer(version, longByteArray);
    }

    /**
     * 解析IP
     *
     * @param ip ip
     * @return 查询结果
     */
    public static String getRegion(String ip) {
        try {
            if (IPV4_SEARCHER == null || IPV6_SEARCHER == null || StrUtil.isEmpty(ip)) {
                log.error("searcher or ip is null");
                return StrUtil.EMPTY;
            }
            long startTime = System.currentTimeMillis();
            Searcher searcher = ip.contains(".") ? IPV4_SEARCHER : IPV6_SEARCHER;
            String result = searcher.search(ip);
            long endTime = System.currentTimeMillis();
            log.debug("region use time[{}] result[{}]", endTime - startTime, result);
            return result;
        } catch (Exception e) {
            log.error("error - {}", e.getLocalizedMessage());
            return DEFAULT_REGION;
        }
    }

    public static String getCity(String ip) {
        String region = getRegion(ip);
        if (StrUtil.isBlank(region) || DEFAULT_REGION.equals(region)) {
            return IpConstant.UNKNOWN;
        }

        String[] parts = StrUtil.splitToArray(region, IpConstant.SEPARATOR_PIPE);
        if (parts.length < 3) {
            return IpConstant.UNKNOWN;
        }

        String country = parts[0];
        String province = parts[1];
        String city = parts[2];

        // 1. 处理国家：如果是 "0" 或内网
        if (IpConstant.ZERO.equals(country)) {
            return IpConstant.UNKNOWN;
        }

        // 2. 特殊逻辑：判定直辖市 (北京, 上海, 天津, 重庆)
        boolean isMunicipality = StrUtil.containsAny(province, IpConstant.MUNICIPALITIES);

        // 3. 处理省份
        if (IpConstant.ZERO.equals(province) || isMunicipality) {
            province = StringUtils.EMPTY;
        } else if (country.contains(IpConstant.CHINA) && !province.endsWith(IpConstant.SUFFIX_PROVINCE) && !province.endsWith(IpConstant.SUFFIX_REGION)) {
            province += IpConstant.SUFFIX_PROVINCE;
        }

        // 4. 处理城市
        if (IpConstant.ZERO.equals(city)) {
            city = StringUtils.EMPTY;
        } else if (country.contains(IpConstant.CHINA) && !city.endsWith(IpConstant.SUFFIX_CITY)) {
            city += IpConstant.SUFFIX_CITY;
        }

        // 5. 组装结果：自动过滤掉空字符串，避免出现多余的连字符
        StringBuilder sb = StrUtil.builder(country);
        if (StrUtil.isNotBlank(province)) {
            sb.append(IpConstant.SEPARATOR_DASH).append(province);
        }
        if (StrUtil.isNotBlank(city)) {
            sb.append(IpConstant.SEPARATOR_DASH).append(city);
        }

        return sb.toString();
    }

    static class IpConstant{
        static String ZERO = "0";
        static String UNKNOWN = "未知";
        static String CHINA = "中国";
        static String SEPARATOR_PIPE = "|";
        static String SEPARATOR_DASH = "-";

        // 逻辑判定词
        static String SUFFIX_PROVINCE = "省";
        static String SUFFIX_CITY = "市";
        static String SUFFIX_REGION = "自治区";

        // 直辖市列表
        static String[] MUNICIPALITIES = {"北京", "上海", "天津", "重庆"};
    }
}
