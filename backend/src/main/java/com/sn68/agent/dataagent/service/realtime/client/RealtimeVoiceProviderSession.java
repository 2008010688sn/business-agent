/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.realtime.client;

/**
 * 实时语音平台会话服务契约。
 */
public interface RealtimeVoiceProviderSession extends AutoCloseable {

	/**
	 * 处理实时语音平台会话。
	 */
	void appendAudio(byte[] bytes);

	/**
	 * 处理实时语音平台会话。
	 */
	void commit();

	/**
	 * 处理实时语音平台会话。
	 */
	void cancel();

	/**
	 * 关闭供应商会话并释放底层连接，可重复调用。
	 */
	@Override
	void close();

}
