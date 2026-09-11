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
package com.sn68.agent.dataagent.properties;

import com.sn68.agent.dataagent.enums.CodePoolExecutorEnum;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import static com.sn68.agent.dataagent.constant.Constant.PROJECT_PROPERTIES_PREFIX;

/**
 * @author vlsmb
 * @since 2025/7/12
 */
@Getter
@Setter
@ConfigurationProperties(prefix = CodeExecutorProperties.CONFIG_PREFIX)
public class CodeExecutorProperties {

	public static final String CONFIG_PREFIX = PROJECT_PROPERTIES_PREFIX + ".code-executor";

	/**
	 * Specify implementation class of code container pool runtime service.
	 * <p>
	 * 默认 {@link CodePoolExecutorEnum#DOCKER}。Docker 守护进程不可用、ping 失败或镜像缺失时拒绝执行，
	 * 不会静默回退到 {@link CodePoolExecutorEnum#LOCAL}。
	 */
	CodePoolExecutorEnum codePoolExecutor = CodePoolExecutorEnum.DOCKER;

	/**
	 * 是否允许以无沙箱的 {@link CodePoolExecutorEnum#LOCAL} 模式执行模型生成的代码。
	 * <p>
	 * {@code LOCAL} 以服务账号身份直接起宿主机进程：无用户隔离、无文件系统限制、无内存/CPU/PID 限额、
	 * 网络全通，且执行前会安装模型给出的 {@code requirements.txt}。因此它必须是一次显式选择，
	 * 而不是漏配环境变量后的静默默认值——保持 {@code false} 时工厂拒绝构造该实现。
	 */
	boolean allowUnsafeLocalExecution = false;

	/**
	 * Service host, use default address if null
	 */
	String host = null;

	/**
	 * 是否对 Docker daemon 启用 TLS 双向校验。{@code null} 表示按 {@link #host} 自动判定：
	 * 远程 daemon 开启、本地 unix socket / 命名管道关闭。
	 * <p>
	 * 能操作远程 daemon 就等同于拿到宿主机 root，因此远程场景不允许静默明文连接；确需关闭必须显式置 {@code false}。
	 * 开启后证书目录取自 {@link #certPath} 或环境变量 {@code DOCKER_CERT_PATH}，缺失时客户端构造直接失败。
	 */
	Boolean tlsVerify = null;

	/**
	 * Docker TLS 证书目录（需含 ca.pem / cert.pem / key.pem）；留空则回退到环境变量 {@code DOCKER_CERT_PATH}
	 */
	String certPath = null;

	/**
	 * Image name, can customize image with common third-party dependencies to replace
	 * this configuration
	 */
	String imageName = "continuumio/anaconda3:latest";

	/**
	 * Container name prefix
	 */
	String containerNamePrefix = "nl2sql-python-exec-";

	/**
	 * Task blocking queue size
	 */
	Integer taskQueueSize = 5;

	/**
	 * Maximum number of core containers
	 */
	Integer coreContainerNum = 2;

	/**
	 * Maximum number of temporary containers
	 */
	Integer tempContainerNum = 2;

	/**
	 * Core thread count of thread pool
	 */
	Integer coreThreadSize = 5;

	/**
	 * Maximum thread count of thread pool
	 */
	Integer maxThreadSize = 5;

	/**
	 * Survival time of temporary containers, in minutes
	 */
	Integer tempContainerAliveTime = 5;

	/**
	 * Task survival time of thread pool, in seconds
	 */
	Long keepThreadAliveTime = 60L;

	/**
	 * Task blocking queue size of thread pool
	 */
	Integer threadQueueSize = 10;

	/**
	 * Maximum container memory, in MB
	 */
	Long limitMemory = 500L;

	/**
	 * Number of container CPU cores
	 */
	Long cpuCore = 1L;

	/**
	 * Python code execution time limit
	 */
	String codeTimeout = "60s";

	/**
	 * Maximum container runtime
	 */
	Long containerTimeout = 3000L;

	/**
	 * Container network mode
	 */
	String networkMode = "none";

	/**
	 * 容器内最大进程/线程数（cgroup pids 限额），用于挡住 fork 炸弹；置 0 或负数表示不限制。
	 * <p>
	 * 线程同样计入该限额，而 numpy / OpenBLAS 等库常按宿主机核数起线程，因此默认值取得比典型线程数高一个量级，
	 * 既能拦住失控 fork，又不会误杀正常的数据分析脚本。
	 */
	Long pidsLimit = 128L;

	/**
	 * 是否以只读根文件系统启动容器。默认关闭，因为它会打断当前的执行流程：
	 * <ul>
	 * <li>{@code pip install} 要写入镜像内的 site-packages，只读根文件系统下必然失败，而执行命令是
	 * {@code pip ... && python ...}，一旦失败 Python 根本不会运行；</li>
	 * <li>远程 daemon 模式经 {@code copyArchiveToContainer} 把脚本写进容器 {@code /app}，同样需要可写根文件系统。</li>
	 * </ul>
	 * 仅当镜像已预装全部依赖（任务不再携带 requirements）且使用本地绑定挂载时，才可置 {@code true}。
	 */
	boolean readonlyRootfs = false;

	/**
	 * 是否为容器追加 {@code no-new-privileges} 安全选项，阻断容器内经 setuid 程序提权
	 */
	boolean noNewPrivileges = true;

	/**
	 * Python执行的最大重试次数
	 */
	Integer pythonMaxTriesCount = 5;

}
