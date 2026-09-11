/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.service.code.impls;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.KillContainerCmd;
import com.github.dockerjava.api.command.ListImagesCmd;
import com.github.dockerjava.api.command.PingCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Image;
import com.sn68.agent.dataagent.properties.CodeExecutorProperties;
import com.sn68.agent.dataagent.service.code.CodePoolExecutorService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 沙箱约束是这里唯一能在无 Docker daemon 的环境下验证的东西：断言产出的 {@link HostConfig} 真的带上了每一条限制。
 */
class DockerCodePoolExecutorServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void hostConfigCarriesEveryDefaultSandboxRestriction() {
		HostConfig config = DockerCodePoolExecutorService.createHostConfig(new CodeExecutorProperties(), tempDir, false);

		// fork 炸弹防线：线程同样计入 pids 限额
		assertEquals(128L, config.getPidsLimit());
		// 阻断容器内经 setuid 程序提权
		assertEquals(List.of("no-new-privileges:true"), config.getSecurityOpts());
		assertArrayEquals(new Capability[] { Capability.ALL }, config.getCapDrop());
		assertEquals(500L * 1024L * 1024L, config.getMemory());
		assertEquals(1L, config.getCpuCount());
		assertEquals(Map.of("/tmp", ""), config.getTmpFs());
		assertEquals("none", config.getNetworkMode());
	}

	/**
	 * 容器池靠重启同一个容器复用，自动删除会让容器在第一个任务结束后消失；回收改由 removeContainer 的 finally 兜底。
	 */
	@Test
	void autoRemoveStaysDisabledBecauseThePoolRestartsContainers() {
		HostConfig config = DockerCodePoolExecutorService.createHostConfig(new CodeExecutorProperties(), tempDir, false);

		assertFalse(config.getAutoRemove());
	}

	@Test
	void pidsLimitIsConfigurable() {
		CodeExecutorProperties properties = new CodeExecutorProperties();
		properties.setPidsLimit(64L);

		assertEquals(64L, DockerCodePoolExecutorService.createHostConfig(properties, tempDir, false).getPidsLimit());
	}

	@Test
	void nonPositivePidsLimitLeavesTheDaemonDefaultInPlace() {
		CodeExecutorProperties properties = new CodeExecutorProperties();
		properties.setPidsLimit(0L);

		assertNull(DockerCodePoolExecutorService.createHostConfig(properties, tempDir, false).getPidsLimit());
	}

	@Test
	void noNewPrivilegesCanBeTurnedOff() {
		CodeExecutorProperties properties = new CodeExecutorProperties();
		properties.setNoNewPrivileges(false);

		assertNull(DockerCodePoolExecutorService.createHostConfig(properties, tempDir, false).getSecurityOpts());
	}

	/**
	 * 只读根文件系统默认关闭：pip 要写镜像内的 site-packages，远程模式还要把脚本拷进容器 /app，
	 * 打开它会让这两条路径整体失败。所以它只能是显式选择。
	 */
	@Test
	void readonlyRootfsIsOptInAndOffByDefault() {
		assertFalse(new CodeExecutorProperties().isReadonlyRootfs());
		assertFalse(DockerCodePoolExecutorService.createHostConfig(new CodeExecutorProperties(), tempDir, false)
			.getReadonlyRootfs());

		CodeExecutorProperties properties = new CodeExecutorProperties();
		properties.setReadonlyRootfs(true);

		assertTrue(DockerCodePoolExecutorService.createHostConfig(properties, tempDir, false).getReadonlyRootfs());
	}

	@Test
	void localModeMountsContextFilesReadOnly() {
		HostConfig config = DockerCodePoolExecutorService.createHostConfig(new CodeExecutorProperties(), tempDir, false);

		List<String> mounted = Arrays.stream(config.getBinds()).map(bind -> bind.getVolume().getPath()).toList();
		assertEquals(List.of("/app/script.py", "/app/requirements.txt", "/app/input_data.txt"), mounted);
		assertTrue(Arrays.stream(config.getBinds()).allMatch(bind -> bind.getAccessMode() == AccessMode.ro));
	}

	@Test
	void remoteModeShipsFilesByCopyInsteadOfBinds() {
		HostConfig config = DockerCodePoolExecutorService.createHostConfig(new CodeExecutorProperties(), tempDir, true);

		assertEquals(0, config.getBinds().length);
		// 加固项与传输方式无关，远程模式同样带齐
		assertEquals(128L, config.getPidsLimit());
		assertEquals(List.of("no-new-privileges:true"), config.getSecurityOpts());
	}

	@Test
	void tlsVerificationDefaultsToEnabledForRemoteDaemons() {
		CodeExecutorProperties properties = new CodeExecutorProperties();

		assertTrue(DockerCodePoolExecutorService.resolveTlsVerify(properties, "tcp://10.0.0.9:2376"));
		assertTrue(DockerCodePoolExecutorService.resolveTlsVerify(properties, "tcp://docker.internal:2375"));
	}

	@Test
	void tlsVerificationDefaultsToDisabledForLocalDaemons() {
		CodeExecutorProperties properties = new CodeExecutorProperties();

		assertFalse(DockerCodePoolExecutorService.resolveTlsVerify(properties, "unix:///var/run/docker.sock"));
		assertFalse(DockerCodePoolExecutorService.resolveTlsVerify(properties, "npipe://./pipe/docker_engine"));
		assertFalse(DockerCodePoolExecutorService.resolveTlsVerify(properties, "tcp://localhost:2375"));
		assertFalse(DockerCodePoolExecutorService.resolveTlsVerify(properties, "tcp://127.0.0.1:2375"));
	}

	@Test
	void explicitTlsVerifySettingWinsOverAutoDetection() {
		CodeExecutorProperties disabled = new CodeExecutorProperties();
		disabled.setTlsVerify(false);
		assertFalse(DockerCodePoolExecutorService.resolveTlsVerify(disabled, "tcp://10.0.0.9:2376"));

		CodeExecutorProperties enabled = new CodeExecutorProperties();
		enabled.setTlsVerify(true);
		assertTrue(DockerCodePoolExecutorService.resolveTlsVerify(enabled, "unix:///var/run/docker.sock"));
	}

	@Test
	void pingFailureRefusesExecutionAndDoesNotUseLocal() {
		DockerClient dockerClient = mock(DockerClient.class);
		PingCmd pingCmd = mock(PingCmd.class);
		when(dockerClient.pingCmd()).thenReturn(pingCmd);
		when(pingCmd.exec()).thenThrow(new RuntimeException("Connection refused"));

		DockerCodePoolExecutorService service = new DockerCodePoolExecutorService(new CodeExecutorProperties(),
				dockerClient, false);

		assertInstanceOf(DockerCodePoolExecutorService.class, service);
		CheckedException failure = assertThrows(CheckedException.class,
				() -> service.runTask(new CodePoolExecutorService.TaskRequest("print(1)", "", null)));
		assertEquals(DockerCodePoolExecutorService.DOCKER_UNAVAILABLE_MESSAGE, failure.getMessage());
	}

	@Test
	void missingImageRefusesExecution() {
		DockerClient dockerClient = mock(DockerClient.class);
		PingCmd pingCmd = mock(PingCmd.class);
		when(dockerClient.pingCmd()).thenReturn(pingCmd);

		ListImagesCmd listCmd = mock(ListImagesCmd.class);
		when(dockerClient.listImagesCmd()).thenReturn(listCmd);
		when(listCmd.withImageNameFilter(any())).thenReturn(listCmd);
		when(listCmd.exec()).thenReturn(List.of());

		PullImageCmd pullCmd = mock(PullImageCmd.class);
		when(dockerClient.pullImageCmd(any())).thenReturn(pullCmd);
		when(pullCmd.exec(any())).thenThrow(new RuntimeException("pull failed"));

		DockerCodePoolExecutorService service = new DockerCodePoolExecutorService(new CodeExecutorProperties(),
				dockerClient, false);

		CheckedException failure = assertThrows(CheckedException.class,
				() -> service.runTask(new CodePoolExecutorService.TaskRequest("print(1)", "", null)));
		assertTrue(failure.getMessage().contains("continuumio/anaconda3:latest"));
		assertTrue(failure.getMessage().contains("不会回退"));
	}

	@Test
	void pullTimeoutRefusesExecution() throws Exception {
		DockerClient dockerClient = mock(DockerClient.class);
		PingCmd pingCmd = mock(PingCmd.class);
		when(dockerClient.pingCmd()).thenReturn(pingCmd);

		ListImagesCmd listCmd = mock(ListImagesCmd.class);
		when(dockerClient.listImagesCmd()).thenReturn(listCmd);
		when(listCmd.withImageNameFilter(any())).thenReturn(listCmd);
		when(listCmd.exec()).thenReturn(List.of());

		PullImageCmd pullCmd = mock(PullImageCmd.class);
		PullImageResultCallback callback = mock(PullImageResultCallback.class);
		when(dockerClient.pullImageCmd(any())).thenReturn(pullCmd);
		when(pullCmd.exec(any())).thenReturn(callback);
		when(callback.awaitCompletion(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);

		CodeExecutorProperties properties = new CodeExecutorProperties();
		DockerCodePoolExecutorService service = new DockerCodePoolExecutorService(properties, dockerClient, false);

		CheckedException failure = assertThrows(CheckedException.class,
				() -> service.runTask(new CodePoolExecutorService.TaskRequest("print(1)", "", null)));
		assertEquals(DockerCodePoolExecutorService.dockerImageUnavailableMessage(properties.getImageName()),
				failure.getMessage());
	}

	@Test
	void waitTimeoutKillsAndRemovesContainer() throws Exception {
		String containerId = "cid-timeout";
		DockerClient dockerClient = mock(DockerClient.class);
		stubStartAndWait(dockerClient, containerId, false, null);
		KillContainerCmd killCmd = stubKill(dockerClient, containerId);
		RemoveContainerCmd removeCmd = stubRemove(dockerClient, containerId);

		CodeExecutorProperties properties = new CodeExecutorProperties();
		properties.setContainerTimeout(1L);
		DockerCodePoolExecutorService service = new DockerCodePoolExecutorService(properties, dockerClient, false);
		service.putContainerTempPath(containerId, tempDir);

		CodePoolExecutorService.TaskResponse response = service.execTaskInContainer(
				new CodePoolExecutorService.TaskRequest("print(1)", "", null), containerId);

		assertFalse(response.isSuccess());
		assertTrue(response.stdErr().contains("超时"));
		verify(killCmd).exec();
		verify(removeCmd).withForce(true);
		verify(removeCmd).exec();
	}

	@Test
	void waitInterruptKillsAndRemovesContainer() throws Exception {
		String containerId = "cid-cancel";
		DockerClient dockerClient = mock(DockerClient.class);
		stubStartAndWait(dockerClient, containerId, null, new InterruptedException("cancelled"));
		KillContainerCmd killCmd = stubKill(dockerClient, containerId);
		RemoveContainerCmd removeCmd = stubRemove(dockerClient, containerId);

		DockerCodePoolExecutorService service = new DockerCodePoolExecutorService(new CodeExecutorProperties(),
				dockerClient, false);
		service.putContainerTempPath(containerId, tempDir);

		try {
			CodePoolExecutorService.TaskResponse response = service.execTaskInContainer(
					new CodePoolExecutorService.TaskRequest("print(1)", "", null), containerId);

			assertFalse(response.isSuccess());
			assertTrue(response.stdErr().contains("已取消"));
			verify(killCmd).exec();
			verify(removeCmd).withForce(true);
			verify(removeCmd).exec();
		}
		finally {
			Thread.interrupted();
		}
	}

	@Test
	void timeoutStillKillsIfClientFieldNulledConcurrently() throws Exception {
		String containerId = "cid-racy-null";
		DockerClient dockerClient = mock(DockerClient.class);
		stubStartAndWait(dockerClient, containerId, false, null);
		KillContainerCmd killCmd = stubKill(dockerClient, containerId);
		RemoveContainerCmd removeCmd = stubRemove(dockerClient, containerId);

		DockerCodePoolExecutorService service = new DockerCodePoolExecutorService(new CodeExecutorProperties(),
				dockerClient, false);
		service.putContainerTempPath(containerId, tempDir);

		WaitContainerCmd waitCmd = mock(WaitContainerCmd.class);
		WaitContainerResultCallback callback = mock(WaitContainerResultCallback.class);
		when(dockerClient.waitContainerCmd(containerId)).thenReturn(waitCmd);
		when(waitCmd.start()).thenReturn(callback);
		when(callback.awaitCompletion(anyLong(), eq(TimeUnit.SECONDS))).thenAnswer(invocation -> {
			Field field = DockerCodePoolExecutorService.class.getDeclaredField("dockerClient");
			field.setAccessible(true);
			field.set(service, null);
			return false;
		});

		CodePoolExecutorService.TaskResponse response = service.execTaskInContainer(
				new CodePoolExecutorService.TaskRequest("print(1)", "", null), containerId);

		assertFalse(response.isSuccess());
		assertFalse(service.isContainerTracked(containerId));
		verify(killCmd).exec();
		verify(removeCmd).withForce(true);
		verify(removeCmd).exec();
	}

	@Test
	void runTaskTimeoutKillsRemovesAndDoesNotRequeue() throws Exception {
		String containerId = "cid-pool-timeout";
		DockerClient dockerClient = mock(DockerClient.class);
		CodeExecutorProperties properties = new CodeExecutorProperties();
		properties.setContainerTimeout(1L);
		properties.setCoreContainerNum(1);
		properties.setTempContainerNum(1);
		stubDockerReady(dockerClient, properties.getImageName());
		stubStartAndWait(dockerClient, containerId, false, null);
		KillContainerCmd killCmd = stubKill(dockerClient, containerId);
		RemoveContainerCmd removeCmd = stubRemove(dockerClient, containerId);
		stubStop(dockerClient);

		AtomicInteger creates = new AtomicInteger();
		DockerCodePoolExecutorService service = new DockerCodePoolExecutorService(properties, dockerClient, false) {
			@Override
			protected String createNewContainer() {
				creates.incrementAndGet();
				putContainerTempPath(containerId, tempDir);
				return containerId;
			}
		};

		CodePoolExecutorService.TaskResponse response = service
			.runTask(new CodePoolExecutorService.TaskRequest("print(1)", "", null));

		assertFalse(response.isSuccess());
		assertTrue(response.stdErr().contains("超时"));
		assertFalse(service.isContainerTracked(containerId));
		assertTrue(service.readyCoreContainer.isEmpty());
		assertTrue(service.readyTempContainer.isEmpty());
		assertEquals(1, creates.get());
		verify(killCmd).exec();
		verify(removeCmd, atLeastOnce()).exec();
	}

	@Test
	void timeoutDrainsQueuedWaiter() throws Exception {
		DockerClient dockerClient = mock(DockerClient.class);
		CodeExecutorProperties properties = new CodeExecutorProperties();
		properties.setContainerTimeout(1L);
		properties.setCoreContainerNum(1);
		properties.setTempContainerNum(1);
		properties.setTaskQueueSize(2);
		stubDockerReady(dockerClient, properties.getImageName());
		stubLifecycleAnyId(dockerClient);
		stubStop(dockerClient);

		CountDownLatch twoRunning = new CountDownLatch(2);
		CountDownLatch queuedReady = new CountDownLatch(1);
		WaitContainerCmd waitCmd = mock(WaitContainerCmd.class);
		WaitContainerResultCallback callback = mock(WaitContainerResultCallback.class);
		when(dockerClient.waitContainerCmd(anyString())).thenReturn(waitCmd);
		when(waitCmd.start()).thenReturn(callback);
		when(callback.awaitCompletion(anyLong(), eq(TimeUnit.SECONDS))).thenAnswer(invocation -> {
			twoRunning.countDown();
			queuedReady.await(5, TimeUnit.SECONDS);
			return false;
		});

		AtomicInteger creates = new AtomicInteger();
		DockerCodePoolExecutorService service = new DockerCodePoolExecutorService(properties, dockerClient, false) {
			@Override
			protected String createNewContainer() throws Exception {
				String id = "cid-waiter-" + creates.incrementAndGet();
				putContainerTempPath(id, Files.createTempDirectory(id));
				return id;
			}
		};

		AtomicReference<CodePoolExecutorService.TaskResponse> third = new AtomicReference<>();
		Thread firstThread = new Thread(
				() -> service.runTask(new CodePoolExecutorService.TaskRequest("print(1)", "", null)), "pr8-first");
		Thread secondThread = new Thread(
				() -> service.runTask(new CodePoolExecutorService.TaskRequest("print(2)", "", null)), "pr8-second");
		Thread thirdThread = new Thread(
				() -> third.set(service.runTask(new CodePoolExecutorService.TaskRequest("print(3)", "", null))),
				"pr8-third");
		firstThread.start();
		secondThread.start();
		assertTrue(twoRunning.await(5, TimeUnit.SECONDS));
		thirdThread.start();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (service.taskQueue.isEmpty() && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertFalse(service.taskQueue.isEmpty());
		queuedReady.countDown();
		firstThread.join(5000);
		secondThread.join(5000);
		thirdThread.join(5000);
		assertFalse(firstThread.isAlive());
		assertFalse(secondThread.isAlive());
		assertFalse(thirdThread.isAlive());
		assertEquals(3, creates.get());
		assertFalse(third.get().isSuccess());
	}

	private static void stubDockerReady(DockerClient dockerClient, String imageName) {
		PingCmd pingCmd = mock(PingCmd.class);
		when(dockerClient.pingCmd()).thenReturn(pingCmd);
		ListImagesCmd listCmd = mock(ListImagesCmd.class);
		when(dockerClient.listImagesCmd()).thenReturn(listCmd);
		when(listCmd.withImageNameFilter(any())).thenReturn(listCmd);
		Image image = mock(Image.class);
		when(image.getRepoTags()).thenReturn(new String[] { imageName });
		when(listCmd.exec()).thenReturn(List.of(image));
	}

	private static void stubLifecycleAnyId(DockerClient dockerClient) {
		StartContainerCmd startCmd = mock(StartContainerCmd.class);
		when(dockerClient.startContainerCmd(anyString())).thenReturn(startCmd);
		KillContainerCmd killCmd = mock(KillContainerCmd.class);
		when(dockerClient.killContainerCmd(anyString())).thenReturn(killCmd);
		RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
		when(dockerClient.removeContainerCmd(anyString())).thenReturn(removeCmd);
		when(removeCmd.withForce(true)).thenReturn(removeCmd);
	}

	private static void stubStop(DockerClient dockerClient) {
		StopContainerCmd stopCmd = mock(StopContainerCmd.class);
		when(dockerClient.stopContainerCmd(anyString())).thenReturn(stopCmd);
	}

	private static void stubStartAndWait(DockerClient dockerClient, String containerId, Boolean completed,
			Exception waitFailure) throws InterruptedException {
		StartContainerCmd startCmd = mock(StartContainerCmd.class);
		when(dockerClient.startContainerCmd(containerId)).thenReturn(startCmd);

		WaitContainerCmd waitCmd = mock(WaitContainerCmd.class);
		WaitContainerResultCallback callback = mock(WaitContainerResultCallback.class);
		when(dockerClient.waitContainerCmd(containerId)).thenReturn(waitCmd);
		when(waitCmd.start()).thenReturn(callback);
		if (waitFailure != null) {
			when(callback.awaitCompletion(anyLong(), eq(TimeUnit.SECONDS))).thenThrow(waitFailure);
		}
		else {
			when(callback.awaitCompletion(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(completed);
		}
	}

	private static KillContainerCmd stubKill(DockerClient dockerClient, String containerId) {
		KillContainerCmd killCmd = mock(KillContainerCmd.class);
		when(dockerClient.killContainerCmd(containerId)).thenReturn(killCmd);
		return killCmd;
	}

	private static RemoveContainerCmd stubRemove(DockerClient dockerClient, String containerId) {
		RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
		when(dockerClient.removeContainerCmd(containerId)).thenReturn(removeCmd);
		when(removeCmd.withForce(true)).thenReturn(removeCmd);
		return removeCmd;
	}

}
