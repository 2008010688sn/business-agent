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

package com.sn68.agent.framework.boot.async;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Nonnull;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.Executor;

/**
 * 异步线程支持
 *
 * @author Levin
 */
@EnableAsync
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(AsyncProperties.class)
public class AsyncConfiguration implements AsyncConfigurer {

    private final AsyncProperties properties;

    @Override
    public Executor getAsyncExecutor() {
        // 具体可以自己写成 properties 的方式
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setKeepAliveSeconds(properties.getKeepAliveSeconds());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix(properties.getThreadNamePrefix());
        executor.setTaskDecorator(new RequestAttributesTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * 异步线程池的时候 request 上下文复制
     */
    private static class RequestAttributesTaskDecorator implements TaskDecorator {
        private static final String TRACE_ID_HEADER = "x-request-id";

        @Override
        @Nonnull
        public Runnable decorate(@Nonnull Runnable runnable) {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            // 兼容非web上下文
            if (attributes == null) {
                return () -> {
                    try {
                        runnable.run();
                    } finally {
                        // 清理可能的MDC上下文
                        MDC.remove(TRACE_ID_HEADER);
                    }
                };
            }
            HttpServletRequest request = attributes.getRequest();
            String traceId = request.getHeader(TRACE_ID_HEADER);
            return () -> {
                try {
                    // 在异步方法执行前将 RequestAttributes 绑定到当前线程的 ThreadLocal 中
                    RequestContextHolder.setRequestAttributes(attributes);
                    if (StrUtil.isNotBlank(traceId)) {
                        MDC.put(TRACE_ID_HEADER, traceId);
                    }
                    runnable.run();
                } finally {
                    MDC.remove(TRACE_ID_HEADER);
                    // 在异步方法执行后清除当前线程的 ThreadLocal
                    RequestContextHolder.resetRequestAttributes();
                }
            };
        }
    }
}