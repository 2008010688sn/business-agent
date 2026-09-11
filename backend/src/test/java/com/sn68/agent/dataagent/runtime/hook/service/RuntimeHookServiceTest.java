package com.sn68.agent.dataagent.runtime.hook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookDTO;
import com.sn68.agent.dataagent.runtime.hook.entity.AgentRuntimeHook;
import com.sn68.agent.dataagent.runtime.hook.repository.AgentRuntimeHookLogMapper;
import com.sn68.agent.dataagent.runtime.hook.repository.AgentRuntimeHookMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeHookServiceTest {

	private final AgentRuntimeHookMapper hookMapper = mock(AgentRuntimeHookMapper.class);

	private final AgentRuntimeHookLogMapper logMapper = mock(AgentRuntimeHookLogMapper.class);

	private final RuntimeHookService service = new RuntimeHookService(hookMapper, logMapper, new ObjectMapper(),
			mock(com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService.class));

	@Test
	void dropsUnsafeNotificationIdempotencyKey() {
		RuntimeHookDTO request = hook(Map.of("toolKey", "notification.send", "targetAlias", "target-a",
				"templateCode", "template-a", "idempotencyKey", "fixed-key"));
		AgentRuntimeHook existing = existingHook();
		when(hookMapper.selectById(1L)).thenReturn(existing);
		ArgumentCaptor<AgentRuntimeHook> captor = ArgumentCaptor.forClass(AgentRuntimeHook.class);

		assertDoesNotThrow(() -> service.update(1L, request));

		verify(hookMapper).updateById(captor.capture());
		assertFalse(captor.getValue().getActionConfig().contains("idempotencyKey"));
	}

	@Test
	void savesNotificationHookWithoutCustomIdempotencyKey() {
		RuntimeHookDTO request = hook(
				Map.of("toolKey", "notification.send", "targetAlias", "target-a", "templateCode", "template-a"));
		AgentRuntimeHook existing = existingHook();
		when(hookMapper.selectById(1L)).thenReturn(existing);

		assertDoesNotThrow(() -> service.update(1L, request));

		verify(hookMapper).updateById(any(AgentRuntimeHook.class));
	}

	@Test
	void savesNotificationHookWithSafeCustomIdempotencyKey() {
		RuntimeHookDTO request = hook(Map.of("toolKey", "notification.send", "targetAlias", "target-a",
				"templateCode", "template-a", "idempotencyKey",
				"hook:${eventType}:${runtimeRequestId}:${resourceKey}:notification.send:${targetAlias}"));
		AgentRuntimeHook existing = existingHook();
		when(hookMapper.selectById(1L)).thenReturn(existing);

		assertDoesNotThrow(() -> service.update(1L, request));

		verify(hookMapper).updateById(any(AgentRuntimeHook.class));
	}

	private RuntimeHookDTO hook(Map<String, Object> actionConfig) {
		return new RuntimeHookDTO(null, "hook-fixed-key", "固定幂等键 Hook", "AFTER_SKILL_SUCCESS", 7L,
				"demand-create", 11L, null, "TOOL_CALL", actionConfig, true, true, "enabled", 0, Map.of());
	}

	private AgentRuntimeHook existingHook() {
		AgentRuntimeHook hook = new AgentRuntimeHook();
		hook.setId(1L);
		hook.setHookCode("hook-fixed-key");
		hook.setHookName("固定幂等键 Hook");
		hook.setEventType("AFTER_SKILL_SUCCESS");
		hook.setActionType("TOOL_CALL");
		hook.setStatus("enabled");
		hook.setAsyncEnabled(true);
		hook.setContinueOnError(true);
		return hook;
	}

}
