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
package com.sn68.agent.dataagent.service.code;

import com.sn68.agent.dataagent.enums.CodePoolExecutorEnum;
import com.sn68.agent.dataagent.properties.CodeExecutorProperties;
import com.sn68.agent.dataagent.service.code.impls.AiSimulationCodeExecutorService;
import com.sn68.agent.dataagent.service.code.impls.DockerCodePoolExecutorService;
import com.sn68.agent.dataagent.service.code.impls.LocalCodePoolExecutorService;
import com.sn68.agent.dataagent.service.llm.LlmService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 运行Python任务的容器池（工厂Bean）
 *
 * @author vlsmb
 * @since 2025/7/28
 */
@Slf4j
@Component
public class CodePoolExecutorServiceFactory implements FactoryBean<CodePoolExecutorService> {

	static final String UNSAFE_LOCAL_EXECUTION_PROPERTY = CodeExecutorProperties.CONFIG_PREFIX
			+ ".allow-unsafe-local-execution";

	private static final String EXECUTOR_PROPERTY = CodeExecutorProperties.CONFIG_PREFIX + ".code-pool-executor";

	private final CodeExecutorProperties properties;

	private final ObjectProvider<LlmService> llmServiceProvider;

	public CodePoolExecutorServiceFactory(CodeExecutorProperties properties,
			ObjectProvider<LlmService> llmServiceProvider) {
		this.properties = properties;
		this.llmServiceProvider = llmServiceProvider;
	}

	/**
	 * 在启动期校验执行器选型。放在这里而不是只放在 {@link #getObject()}，是因为 {@code FactoryBean}
	 * 的产品是懒加载的：当前没有任何消费方注入 {@link CodePoolExecutorService}，只靠 {@code getObject()}
	 * 把关的话，误配成 {@code local} 的实例可以一路正常启动，等到有人接线时才炸。
	 */
	@PostConstruct
	void validateExecutorSelection() {
		if (properties.getCodePoolExecutor() != CodePoolExecutorEnum.LOCAL) {
			return;
		}
		requireLocalExecutionAllowed();
		log.warn("代码执行器已启用无沙箱的 LOCAL 模式：模型生成的 Python 与其 requirements.txt 将以本服务账号身份"
				+ "直接在宿主机执行，无隔离、无资源限额、网络全通。仅限受控环境使用，生产请改用 {}=docker。", EXECUTOR_PROPERTY);
	}

	@Override
	public CodePoolExecutorService getObject() {
		return switch (properties.getCodePoolExecutor()) {
			// Docker 守护进程不可用时由执行期 fail-closed 拒绝，禁止回退 LOCAL
			case DOCKER -> new DockerCodePoolExecutorService(properties);
			case LOCAL -> {
				requireLocalExecutionAllowed();
				yield new LocalCodePoolExecutorService(properties);
			}
			case AI_SIMULATION -> new AiSimulationCodeExecutorService(llmServiceProvider.getObject());
			default ->
				throw new IllegalStateException("This option does not have a corresponding implementation class yet.");
		};
	}

	private void requireLocalExecutionAllowed() {
		if (properties.isAllowUnsafeLocalExecution()) {
			return;
		}
		throw CheckedException.fail(EXECUTOR_PROPERTY + "=local 是无沙箱执行模式，已禁止默认启用。请改为 " + EXECUTOR_PROPERTY
				+ "=docker；确需在受控环境使用本地执行时，必须显式设置 " + UNSAFE_LOCAL_EXECUTION_PROPERTY + "=true。");
	}

	@Override
	public Class<?> getObjectType() {
		return CodePoolExecutorService.class;
	}

}
