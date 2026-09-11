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

package com.sn68.agent.framework.redis.plus.sequence;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import com.sn68.agent.framework.commons.times.TimeZoneUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @author Levin
 */
@Slf4j
@RequiredArgsConstructor
public class RedisSequenceHelper {

    public static final String COMMON_INCR_KEY = "sequence:default";
    private static final DateTimeFormatter ISOLATION_PATTERN = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate stringRedisTemplate;

    public String generate(Sequence sequence) {
        DateTimeFormatter formatter = sequence.formatter();
        String localDate = "";
        if (formatter != null) {
            localDate = LocalDateTimeUtil.format(LocalDateTime.now(TimeZoneUtil.getHttpZoneId()), formatter);
        }
        Long increment = stringRedisTemplate.opsForHash().increment(sequence.key(), localDate, 1);
        return sequence.prefix()
                + localDate
                + StrUtil.blankToDefault(sequence.delimiter(), "")
                + StrUtil.padPre(increment + "", sequence.size(), '0');
    }

    public String generate(String isolationKey) {
        Long increment = stringRedisTemplate.opsForHash().increment(COMMON_INCR_KEY, isolationKey, 1);
        return String.valueOf(increment);
    }


    public String generate(int length) {
        var isolationKey = LocalDate.now().format(ISOLATION_PATTERN);
        Long increment = stringRedisTemplate.opsForHash().increment(COMMON_INCR_KEY, isolationKey, 1);
        return StrUtil.padPre(increment + "", length, '0');
    }

    public String generate(String isolationKey, int length) {
        return generate(isolationKey, length, true);
    }

    public String generate(String isolationKey, int length, boolean padZero) {
        Long increment = stringRedisTemplate.opsForHash().increment(COMMON_INCR_KEY, isolationKey, 1);
        if (padZero) {
            return String.valueOf(increment);
        }
        return StrUtil.padPre(increment + "", length, '0');
    }

    /**
     * 按规则生成流水号
     *
     * @param sequence     规则
     * @param isolationKey 隔离（比如 租户ID）
     * @return 流水号 = 前缀 + 日期时间 + 自增流水号
     */
    public String generate(Sequence sequence, Object isolationKey) {
        DateTimeFormatter formatter = sequence.formatter();
        String localDate = "";
        if (formatter != null) {
            localDate = LocalDateTimeUtil.format(LocalDateTime.now(TimeZoneUtil.getHttpZoneId()), formatter);
        }
        Long increment = stringRedisTemplate.opsForHash().increment(sequence.key() + localDate, String.valueOf(isolationKey), 1);
        return sequence.prefix()
                + localDate
                + StrUtil.blankToDefault(sequence.delimiter(), "")
                + StrUtil.padPre(increment + "", sequence.size(), '0');
    }

}
