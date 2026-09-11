/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.shadow;

import com.alibaba.ttl.threadpool.TtlExecutors;
import com.sn68.agent.dataagent.routing.RouteEngineModeProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shadow 路由对比装配：注册引擎切流配置与影子对比专用线程池。
 *
 * <p>线程池沿用 DataAgentConfiguration 中 route-* 执行器的既有模式（命名线程 + 有界队列
 * + AbortPolicy + TTL 上下文透传）；独立成池是为了不与延迟敏感的路由检索 / 路由模型执行器
 * 争抢线程，队列打满即拒绝本次影子任务（提交侧 warn 后丢弃），绝不反压主链路。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RouteEngineModeProperties.class)
public class ShadowRouteConfiguration implements DisposableBean {

	/** 影子对比执行器 Bean 名，供 ShadowRouteCompileService 按名注入。 */
	public static final String SHADOW_EXECUTOR_BEAN = "routeShadowExecutor";

	private static final int CORE_POOL_SIZE = 1;

	private static final int MAX_POOL_SIZE = 2;

	private static final int QUEUE_CAPACITY = 200;

	private ThreadPoolExecutor rawShadowExecutor;

	@Bean(name = SHADOW_EXECUTOR_BEAN)
	public ExecutorService routeShadowExecutor() {
		ThreadFactory threadFactory = new ThreadFactory() {
			private final AtomicInteger threadNumber = new AtomicInteger(1);

			@Override
			public Thread newThread(Runnable runnable) {
				Thread thread = new Thread(runnable, "route-shadow-" + threadNumber.getAndIncrement());
				thread.setDaemon(false);
				return thread;
			}
		};
		rawShadowExecutor = new ThreadPoolExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(QUEUE_CAPACITY), threadFactory, new ThreadPoolExecutor.AbortPolicy());
		log.info("Route shadow executor initialized with core={}, max={}, queueCapacity={}", CORE_POOL_SIZE,
				MAX_POOL_SIZE, QUEUE_CAPACITY);
		return TtlExecutors.getTtlExecutorService(rawShadowExecutor);
	}

	/** 影子任务可丢弃：短等待后直接强制关闭，不阻塞应用停机。 */
	@Override
	public void destroy() {
		if (rawShadowExecutor == null || rawShadowExecutor.isShutdown()) {
			return;
		}
		rawShadowExecutor.shutdown();
		try {
			if (!rawShadowExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
				rawShadowExecutor.shutdownNow();
			}
		}
		catch (InterruptedException ex) {
			rawShadowExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

}
