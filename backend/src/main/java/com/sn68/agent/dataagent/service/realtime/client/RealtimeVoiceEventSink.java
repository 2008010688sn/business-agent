/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.realtime.client;

import java.io.IOException;
import java.util.Map;

/**
 * Sends normalized realtime voice events back to the browser WebSocket.
 */
public interface RealtimeVoiceEventSink {

	/**
	 * 执行实时语音EventSink。
	 */
	void sendEvent(String event, Map<String, Object> data) throws IOException;

	/**
	 * 执行实时语音EventSink。
	 */
	void sendAudio(byte[] bytes) throws IOException;

}
