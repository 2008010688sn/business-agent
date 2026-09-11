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
package com.sn68.agent.dataagent.service.code.impls;

import com.sn68.agent.dataagent.properties.CodeExecutorProperties;
import com.sn68.agent.dataagent.service.code.CodePoolExecutorService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.github.dockerjava.api.model.HostConfig.newHostConfig;

/**
 * 运行Python任务的容器池（Docker实现类）
 *
 * @author vlsmb
 * @since 2025/7/12
 */
@Slf4j
public class DockerCodePoolExecutorService extends AbstractCodePoolExecutorService implements CodePoolExecutorService {

	private static final String TLS_VERIFY_PROPERTY = CodeExecutorProperties.CONFIG_PREFIX + ".tls-verify";

	private static final String CERT_PATH_PROPERTY = CodeExecutorProperties.CONFIG_PREFIX + ".cert-path";

	static final String DOCKER_UNAVAILABLE_MESSAGE = "Docker 守护进程不可用，代码沙箱已拒绝执行。请确认 Docker 已启动且可访问；code-pool-executor=docker 不会回退到本机无沙箱执行。";

	private final String resolvedDockerHost;

	private final boolean isRemote;

	private final boolean clientInjected;

	private final ConcurrentHashMap<String, Path> containerTempPath;

	private volatile DockerClient dockerClient;

	private volatile boolean imageReady;

	public DockerCodePoolExecutorService(CodeExecutorProperties properties) {
		this(properties, null, checkIsRemote(properties.getHost()));
	}

	/**
	 * 测试用构造器：注入 {@link DockerClient}，构造期不连接 daemon。
	 */
	DockerCodePoolExecutorService(CodeExecutorProperties properties, DockerClient dockerClient, boolean isRemote) {
		super(properties);
		this.resolvedDockerHost = this.getDockerHostForCurrentOS(properties.getHost());
		this.isRemote = isRemote;
		this.clientInjected = dockerClient != null;
		this.dockerClient = dockerClient;
		this.containerTempPath = new ConcurrentHashMap<>();
		if (properties.isReadonlyRootfs()) {
			log.warn("只读根文件系统已开启：携带 requirements.txt 的任务会因 pip 无法写入 site-packages 而整体失败，"
					+ "远程 daemon 模式还会因脚本无法写入 /app 而无法执行。请确认镜像已预装全部依赖。");
		}
		log.info("Docker Code Pool initialized. Mode: {}",
				this.isRemote ? "Remote (Copy Files)" : "Local (Bind Mounts)");
	}

	@Override
	public TaskResponse runTask(TaskRequest request) {
		ensureDockerReady();
		return super.runTask(request);
	}

	@Override
	protected boolean isContainerTracked(String containerId) {
		return this.containerTempPath.containsKey(containerId);
	}

	/**
	 * 执行期探测 daemon / 镜像。构造期不 ping，避免开发环境无 Docker 时无法启动。
	 */
	private void ensureDockerReady() {
		try {
			DockerClient client = this.obtainClient();
			client.pingCmd().exec();
			this.ensureImagePresent(client);
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			this.discardClient();
			throw dockerUnavailable(ex);
		}
	}

	/**
	 * ping 失败时关掉旧客户端，禁止只把字段置空让并发中的 kill 找不到引用。
	 */
	private void discardClient() {
		this.imageReady = false;
		if (this.clientInjected) {
			return;
		}
		synchronized (this) {
			DockerClient stale = this.dockerClient;
			this.dockerClient = null;
			if (stale == null) {
				return;
			}
			try {
				stale.close();
			}
			catch (IOException ex) {
				log.warn("Failed to close Docker client after ping failure", ex);
			}
		}
	}

	private DockerClient obtainClient() {
		DockerClient client = this.dockerClient;
		if (client != null) {
			return client;
		}
		synchronized (this) {
			client = this.dockerClient;
			if (client != null) {
				return client;
			}
			client = this.createDockerClientWithFallback(this.buildClientConfig(this.resolvedDockerHost));
			this.dockerClient = client;
			return client;
		}
	}

	private void ensureImagePresent(DockerClient client) {
		if (this.imageReady) {
			return;
		}
		String imageName = this.properties.getImageName();
		boolean imageExists = client.listImagesCmd()
			.withImageNameFilter(imageName)
			.exec()
			.stream()
			.anyMatch(image -> {
				String[] tags = image.getRepoTags();
				return tags != null && Arrays.asList(tags).contains(imageName);
			});
		if (imageExists) {
			this.imageReady = true;
			return;
		}
		try {
			boolean pulled = client.pullImageCmd(imageName)
				.exec(new PullImageResultCallback())
				.awaitCompletion(this.properties.getContainerTimeout(), TimeUnit.SECONDS);
			if (!Boolean.TRUE.equals(pulled)) {
				throw CheckedException.fail(dockerImageUnavailableMessage(imageName));
			}
			log.info("pull image {} success", imageName);
			this.imageReady = true;
		}
		catch (CheckedException e) {
			throw e;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.error("pull image {} interrupted", imageName, e);
			throw CheckedException.fail(dockerImageUnavailableMessage(imageName));
		}
		catch (Exception e) {
			log.error("pull image {} error", imageName, e);
			throw CheckedException.fail(dockerImageUnavailableMessage(imageName));
		}
	}

	private static CheckedException dockerUnavailable(Throwable cause) {
		log.error(DOCKER_UNAVAILABLE_MESSAGE, cause);
		return CheckedException.fail(DOCKER_UNAVAILABLE_MESSAGE);
	}

	static String dockerImageUnavailableMessage(String imageName) {
		return "Docker 镜像 " + imageName + " 不存在且拉取失败，代码沙箱已拒绝执行。不会回退到本机无沙箱执行。";
	}

	void putContainerTempPath(String containerId, Path tempDir) {
		this.containerTempPath.put(containerId, tempDir);
	}

	/**
	 * Automatically select appropriate Docker Host address based on current operating
	 * system
	 * @return Docker Host URI
	 */
	private String getDockerHostForCurrentOS(String dockerHost) {
		// If configuration object has value, directly use configuration object's value
		if (StringUtils.hasText(dockerHost)) {
			return dockerHost;
		}
		String osName = System.getProperty("os.name").toLowerCase();
		log.info("Detected operating system: {}", osName);

		if (osName.contains("win")) {
			// Windows系统
			log.info("Using Windows Docker configuration");
			// On Windows, try TCP connection first, more stable
			return "tcp://localhost:2375";
		}
		else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
			// Linux/Unix系统
			log.info("Using Linux/Unix Docker configuration");
			return "unix:///var/run/docker.sock";
		}
		else if (osName.contains("mac")) {
			// macOS系统
			log.info("Using macOS Docker configuration");
			return "unix:///var/run/docker.sock";
		}
		else {
			// Unknown system, use default value from configuration file
			log.warn("Unknown operating system: {}, using default docker host", osName);
			return "unix:///var/run/docker.sock";
		}
	}

	/**
	 * 构造 Docker 客户端配置。TLS 校验不再硬编码关闭：能操作远程 daemon 就等同于拿到那台宿主机的 root，
	 * 因此远程地址默认要求证书校验，只有本地 unix socket / 命名管道才默认关闭。
	 * @param dockerHost 已解析出的 Docker Host 地址
	 * @return Docker 客户端配置
	 */
	private DockerClientConfig buildClientConfig(String dockerHost) {
		boolean tlsVerify = resolveTlsVerify(this.properties, dockerHost);
		if (tlsVerify) {
			log.info("Docker TLS verification is enabled for host {}, certificates are read from {} or DOCKER_CERT_PATH",
					dockerHost, CERT_PATH_PROPERTY);
		}
		else if (checkIsRemote(dockerHost)) {
			log.warn("Docker host {} is remote but TLS verification is disabled by {}=false, "
					+ "controlling a remote daemon is equivalent to root on that host", dockerHost, TLS_VERIFY_PROPERTY);
		}
		DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder()
			.withDockerHost(dockerHost)
			.withDockerTlsVerify(tlsVerify);
		if (StringUtils.hasText(this.properties.getCertPath())) {
			builder.withDockerCertPath(this.properties.getCertPath());
		}
		return builder.build();
	}

	/**
	 * 决策是否启用 Docker daemon 的 TLS 校验：显式配置优先，未配置时按「远程开、本地关」自动判定。
	 * @param properties 代码执行器配置
	 * @param dockerHost 已解析出的 Docker Host 地址
	 * @return 是否启用 TLS 校验
	 */
	static boolean resolveTlsVerify(CodeExecutorProperties properties, String dockerHost) {
		Boolean configured = properties.getTlsVerify();
		return configured != null ? configured : checkIsRemote(dockerHost);
	}

	static boolean checkIsRemote(String host) {
		if (!StringUtils.hasText(host)) {
			// Empty host means using defaults which are local (unix socket or npipe or
			// localhost)
			return false;
		}
		try {
			URI uri = URI.create(host);
			String scheme = uri.getScheme();
			if ("unix".equalsIgnoreCase(scheme) || "npipe".equalsIgnoreCase(scheme)) {
				return false;
			}
			// 处理TCP协议
			if ("tcp".equalsIgnoreCase(scheme)) {
				String h = uri.getHost();
				// 本地地址包括：localhost、127.0.0.1、::1（IPv6本地回环）
				boolean isLocalTcp = "localhost".equalsIgnoreCase(h) || "127.0.0.1".equals(h) || "::1".equals(h);
				return !isLocalTcp; // 不是本地TCP地址则为远程
			}
			return false;
		}
		catch (Exception e) {
			log.warn("Failed to parse Docker host URI: {}, assuming local.", host);
			return false;
		}
	}

	/**
	 * Create Docker client, supports fallback mechanism for multiple connection methods
	 * @param config Docker client configuration
	 * @return DockerClient instance
	 * @throws CheckedException if all connection methods fail; never falls back to LOCAL
	 */
	private DockerClient createDockerClientWithFallback(DockerClientConfig config) {
		String osName = System.getProperty("os.name").toLowerCase();

		if (osName.contains("win")) {
			// Windows System: Try the configured host first, then fallbacks
			List<String> windowsHosts = new ArrayList<>();
			// 1. Priority: The host from configuration (which might be user-provided or
			// auto-detected)
			if (StringUtils.hasText(String.valueOf(config.getDockerHost()))) {
				windowsHosts.add(String.valueOf(config.getDockerHost()));
			}
			// 2. Fallback: Standard Windows Docker Desktop named pipe
			windowsHosts.add("npipe://./pipe/docker_engine");
			// 3. Fallback: Localhost TCP (common setting)
			windowsHosts.add("tcp://localhost:2375");

			Exception lastFailure = null;
			for (String dockerHost : windowsHosts) {
				ZerodepDockerHttpClient httpClient = null;
				try {
					log.info("Attempting to connect to Docker using: {}", dockerHost);

					DockerClientConfig testConfig = this.buildClientConfig(dockerHost);

					httpClient = new ZerodepDockerHttpClient.Builder()
						.dockerHost(testConfig.getDockerHost())
						.sslConfig(testConfig.getSSLConfig())
						.build();

					DockerClient dockerClient = DockerClientImpl.getInstance(testConfig, httpClient);

					// Test if connection is normal
					dockerClient.pingCmd().exec();
					log.info("Successfully connected to Docker using: {}", dockerHost);
					return dockerClient;

				}
				catch (Exception e) {
					lastFailure = e;
					closeProbeHttpClient(httpClient, dockerHost);
					log.warn("Failed to connect using {}: {}", dockerHost, e.getMessage());
				}
			}

			// 连接方式回退（npipe / tcp）已穷尽，禁止再回退到 LOCAL 执行器
			throw dockerUnavailable(lastFailure);

		}
		else {
			// Linux/Unix/macOS系统：使用标准Unix socket
			ZerodepDockerHttpClient httpClient = null;
			try {
				httpClient = new ZerodepDockerHttpClient.Builder()
					.dockerHost(config.getDockerHost())
					.sslConfig(config.getSSLConfig())
					.build();

				DockerClient dockerClient = DockerClientImpl.getInstance(config, httpClient);
				dockerClient.pingCmd().exec(); // Test connection
				log.info("Successfully connected to Docker using: {}", config.getDockerHost());
				return dockerClient;

			}
			catch (Exception e) {
				closeProbeHttpClient(httpClient, String.valueOf(config.getDockerHost()));
				throw dockerUnavailable(e);
			}
		}
	}

	/**
	 * 关闭探测失败的 Docker HTTP 客户端。探测成功的分支已经把客户端交给返回出去的 {@link DockerClient}
	 * 持有，只有失败的候选地址才会走到这里——它的连接池再没有人引用，不关就一直挂着。
	 * @param httpClient 探测用的 HTTP 客户端，可能为 {@code null}（构造阶段就失败）
	 * @param dockerHost 对应的 Docker Host 地址，仅用于日志
	 */
	private void closeProbeHttpClient(ZerodepDockerHttpClient httpClient, String dockerHost) {
		if (httpClient == null) {
			return;
		}
		try {
			httpClient.close();
		}
		catch (IOException ex) {
			log.warn("Failed to close the probing Docker HTTP client for {}", dockerHost, ex);
		}
	}

	/**
	 * Create container's HostConfig
	 * <p>
	 * 沙箱的全部约束都收在这里：内存 / CPU / PID 限额、丢弃所有 capability、禁止提权、只读根文件系统（可选）、
	 * 网络模式。其中 {@code autoRemove} 必须保持 {@code false}——容器池是靠重启同一个容器来复用的
	 * （见 {@link #execTaskInContainer}），自动删除会让容器在第一个任务结束后消失，池随即失效；
	 * 容器回收改由 {@link #removeContainer} 的 {@code finally} 兜底。
	 * @param properties 代码执行器配置
	 * @param tempDir 宿主机侧的任务临时目录
	 * @param isRemote 是否远程 daemon（远程走文件拷贝，不做绑定挂载）
	 * @return 容器的 HostConfig
	 */
	static HostConfig createHostConfig(CodeExecutorProperties properties, Path tempDir, boolean isRemote) {
		HostConfig config = newHostConfig().withMemory(properties.getLimitMemory() * 1024L * 1024L)
			.withCpuCount(properties.getCpuCore())
			.withCapDrop(Capability.ALL)
			.withAutoRemove(false)
			.withReadonlyRootfs(properties.isReadonlyRootfs())
			.withTmpFs(Map.of("/tmp", ""))
			.withNetworkMode(properties.getNetworkMode());

		// 非正数视为不限制，交回 daemon 的默认值
		Long pidsLimit = properties.getPidsLimit();
		if (pidsLimit != null && pidsLimit > 0L) {
			config.withPidsLimit(pidsLimit);
		}
		if (properties.isNoNewPrivileges()) {
			config.withSecurityOpts(List.of("no-new-privileges:true"));
		}

		if (!isRemote) {
			List<Bind> binds = new ArrayList<>();
			binds.add(new Bind(tempDir.resolve("script.py").toAbsolutePath().toString(), new Volume("/app/script.py"),
					AccessMode.ro));
			binds.add(new Bind(tempDir.resolve("requirements.txt").toAbsolutePath().toString(),
					new Volume("/app/requirements.txt"), AccessMode.ro));
			binds.add(new Bind(tempDir.resolve("input_data.txt").toAbsolutePath().toString(),
					new Volume("/app/input_data.txt"), AccessMode.ro));
			config.withBinds(binds.toArray(new Bind[0]));
		}
		return config;
	}

	/**
	 * Clean up existing container with same name
	 */
	private void cleanupExistingResources(DockerClient client, String containName) {
		if (client == null) {
			return;
		}
		try {
			// Try to delete container with same name
			client.removeContainerCmd(containName).withForce(true).exec();
			log.info("Removed existing container: {}", containName);
		}
		catch (Exception e) {
			log.warn("Failed to remove container {}: {}", containName, e.getMessage());
		}
	}

	@Override
	protected String createNewContainer() throws Exception {
		DockerClient client = this.dockerClient;
		if (client == null) {
			throw new IllegalStateException(DOCKER_UNAVAILABLE_MESSAGE);
		}
		String containerName = this.generateContainerName();
		// First clean up possibly existing container with same name
		this.cleanupExistingResources(client, containerName);

		// Generate temporary directory and files
		Path tempDir = Files.createTempDirectory(containerName);
		try {
			Files.createFile(tempDir.resolve("requirements.txt"));
			Files.createFile(tempDir.resolve("script.py"));
			Files.createFile(tempDir.resolve("input_data.txt"));

			// Create container
			HostConfig hostConfig = createHostConfig(this.properties, tempDir, this.isRemote);
			String cmd = this.buildExecutionCommand(tempDir);

			CreateContainerResponse container = client.createContainerCmd(properties.getImageName())
				.withName(containerName)
				.withWorkingDir("/app")
				.withHostConfig(hostConfig)
				.withCmd("sh", "-c", cmd)
				.exec();
			String containerId = container.getId();
			// Save temporary directory object
			this.containerTempPath.put(containerId, tempDir);
			return containerId;
		}
		catch (Exception ex) {
			// 目录要登记进 containerTempPath 之后才由 removeContainer 的 finally 兜底回收；
			// 登记之前失败就再没有人认领它，宿主机每失败一次就多一个孤儿临时目录。
			this.clearTempDir(tempDir);
			throw ex;
		}
	}

	@Override
	protected TaskResponse execTaskInContainer(TaskRequest request, String containerId) {
		DockerClient client = this.dockerClient;
		// Get temporary directory object
		Path tempDir = this.containerTempPath.get(containerId);
		if (tempDir == null) {
			log.error("Container '{}' does not exist work dir", containerId);
			this.killAndRemoveContainer(containerId, client);
			return TaskResponse.exception("Container '" + containerId + "' does not exist work dir");
		}

		boolean completed = false;
		try {
			// 1. Prepare files
			this.writeContextFiles(tempDir, request);
			this.uploadFilesIfRemote(client, containerId, tempDir);

			if (client == null) {
				return TaskResponse.exception(DOCKER_UNAVAILABLE_MESSAGE);
			}

			// 2. Start container and wait。completed 只在日志与退出码都收完后置位，
			// 避免 wait 已结束但 log 流挂死时 finally 跳过 kill。
			client.startContainerCmd(containerId).exec();
			boolean waitFinished = Boolean.TRUE.equals(client.waitContainerCmd(containerId)
				.start()
				.awaitCompletion(this.properties.getContainerTimeout(), TimeUnit.SECONDS));
			if (!waitFinished) {
				log.warn("Docker container {} timed out after {}s, killing", containerId,
						this.properties.getContainerTimeout());
				return TaskResponse.failure("", "代码执行超时，已强制终止并删除容器");
			}

			// 3. Fetch logs
			LogResult logs = this.fetchExecutionLogs(client, containerId, tempDir);
			if (logs == null) {
				log.warn("Reading logs for container {} timed out, killing", containerId);
				return TaskResponse.failure("", "代码执行超时，已强制终止并删除容器");
			}
			String stdout = logs.stdout;
			String stderr = logs.stderr;

			// 4. Check exit code
			InspectContainerResponse inspectResponse = client.inspectContainerCmd(containerId).exec();
			int exitCode = Objects.requireNonNull(inspectResponse.getState().getExitCodeLong()).intValue();
			completed = true;
			if (exitCode != 0) {
				String errorMessage = "Docker exit code " + exitCode + ". Stderr: " + stderr + ". Stdout: " + stdout;
				log.error("Error executing Docker container {}: {}", containerId, errorMessage);
				return TaskResponse.failure(stdout, stderr);
			}
			return TaskResponse.success(stdout);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Docker container {} cancelled, killing", containerId);
			return TaskResponse.failure("", "代码执行已取消，已强制终止并删除容器");
		}
		catch (Exception e) {
			log.error("Error executing task in container: {}", e.getMessage());
			return TaskResponse.exception(e.getMessage());
		}
		finally {
			if (!completed) {
				this.killAndRemoveContainer(containerId, client);
			}
		}
	}

	/**
	 * 用调用方捕获的客户端 kill+remove；客户端已丢失时仍要从跟踪表摘掉，避免失效 ID 回池。
	 */
	private void killAndRemoveContainer(String containerId, DockerClient client) {
		if (client != null) {
			try {
				client.killContainerCmd(containerId).exec();
				log.info("Killed container: {}", containerId);
			}
			catch (Exception e) {
				log.warn("Failed to kill container: {}, message: {}", containerId, e.getMessage());
			}
			try {
				client.removeContainerCmd(containerId).withForce(true).exec();
				log.info("Successfully removed container: {}", containerId);
			}
			catch (Exception e) {
				log.warn("Failed to remove container: {}, message: {}", containerId, e.getMessage());
			}
		}
		this.untrackContainer(containerId);
	}

	private void untrackContainer(String containerId) {
		Path tempDir = this.containerTempPath.remove(containerId);
		if (tempDir != null) {
			this.clearTempDir(tempDir);
		}
	}

	// --- Helper Methods ---

	private String buildExecutionCommand(Path tempDir) {
		return String.format(
				"if [ -s requirements.txt ]; then pip3 install --no-cache-dir -r requirements.txt > /dev/null; fi && timeout -s SIGKILL %s python3 -u script.py < input_data.txt",
				properties.getCodeTimeout());
	}

	private void writeContextFiles(Path tempDir, TaskRequest request) throws IOException {
		Files.write(tempDir.resolve("script.py"),
				StringUtils.hasText(request.code()) ? request.code().getBytes() : "".getBytes());
		Files.write(tempDir.resolve("requirements.txt"),
				StringUtils.hasText(request.requirement()) ? request.requirement().getBytes() : "".getBytes());
		Files.write(tempDir.resolve("input_data.txt"),
				StringUtils.hasText(request.input()) ? request.input().getBytes() : "".getBytes());
	}

	private void uploadFilesIfRemote(DockerClient client, String containerId, Path tempDir) {
		if (!this.isRemote || client == null) {
			return;
		}
		String[] files = { "script.py", "requirements.txt", "input_data.txt" };
		for (String file : files) {
			client.copyArchiveToContainerCmd(containerId)
				.withHostResource(tempDir.resolve(file).toString())
				.withRemotePath("/app/")
				.exec();
		}
	}

	private record LogResult(String stdout, String stderr) {
	}

	/**
	 * @return 日志内容；读取超时返回 {@code null}，由调用方走 kill+remove
	 */
	private LogResult fetchExecutionLogs(DockerClient client, String containerId, Path tempDir)
			throws InterruptedException {
		StringBuilder stdoutBuilder = new StringBuilder();
		StringBuilder stderrBuilder = new StringBuilder();

		final int MAX_LOG_SIZE = 5 * 1024 * 1024; // 5MB limit
		boolean logsDone = client.logContainerCmd(containerId)
			.withStdOut(true)
			.withStdErr(true)
			.exec(new ResultCallback.Adapter<Frame>() {
				@Override
				public void onNext(Frame item) {
					String payload = new String(item.getPayload(), StandardCharsets.UTF_8);
					if (item.getStreamType() == StreamType.STDOUT) {
						appendWithLimit(stdoutBuilder, payload, MAX_LOG_SIZE);
					}
					else if (item.getStreamType() == StreamType.STDERR) {
						appendWithLimit(stderrBuilder, payload, MAX_LOG_SIZE);
					}
				}
			})
			.awaitCompletion(this.properties.getContainerTimeout(), TimeUnit.SECONDS);
		if (!Boolean.TRUE.equals(logsDone)) {
			return null;
		}

		return new LogResult(stdoutBuilder.toString(), stderrBuilder.toString());
	}

	private void appendWithLimit(StringBuilder builder, String payload, int limit) {
		if (builder.length() < limit) {
			builder.append(payload);
		}
		else if (builder.length() == limit) {
			builder.append("\n...[Output truncated due to size limit]...");
			builder.append(" "); // Prevent re-entry
		}
	}

	@Override
	protected void stopContainer(String containerId) throws Exception {
		DockerClient client = this.dockerClient;
		if (client == null) {
			return;
		}
		try {
			client.stopContainerCmd(containerId).exec();
			log.info("Successfully stopped container: {}", containerId);
		}
		catch (Exception e) {
			log.warn("Failed to stop container: {}, message: {}", containerId, e.getMessage());
		}
	}

	@Override
	protected void removeContainer(String containerId) throws Exception {
		DockerClient client = this.dockerClient;
		try {
			if (client != null) {
				client.removeContainerCmd(containerId).withForce(true).exec();
				log.info("Successfully removed container: {}", containerId);
			}
		}
		catch (Exception e) {
			log.warn("Failed to remove container: {}, message: {}", containerId, e.getMessage());
		}
		finally {
			// 宿主机临时目录与映射表的回收不能挂在 removeContainerCmd 成功之上，
			// 否则一次删除失败就同时泄漏磁盘目录和 map 条目（map 无上限，会随失败次数一直涨）。
			this.untrackContainer(containerId);
		}
	}

	@Override
	protected void shutdownPool() throws Exception {
		try {
			super.shutdownPool();
		}
		finally {
			// 容器清理失败要原样抛出让调用方看见，但无论如何都得关掉客户端连接；
			// 二者放在同一个 catch 里会把「容器没删掉」误报成「客户端没关掉」。
			DockerClient client = this.dockerClient;
			if (client != null) {
				try {
					client.close();
				}
				catch (IOException ex) {
					log.warn("Failed to close the Docker client during pool shutdown", ex);
				}
			}
		}
	}

}
