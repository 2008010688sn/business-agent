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
package com.sn68.agent.dataagent.config;

import com.alibaba.ttl.threadpool.TtlExecutors;
import com.sn68.agent.dataagent.employee.config.DigitalEmployeeProperties;
import com.sn68.agent.dataagent.task.config.TaskSchedulerProperties;
import com.sn68.agent.dataagent.properties.ToolCenterProperties;
import com.sn68.agent.dataagent.properties.AgentSkillProperties;
import com.sn68.agent.dataagent.properties.CodeExecutorProperties;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.vectorstore.SimpleVectorStoreInitialization;
import com.sn68.agent.dataagent.splitter.SentenceSplitter;
import com.sn68.agent.dataagent.splitter.RecursiveOverlapTextSplitter;
import com.sn68.agent.dataagent.splitter.SemanticTextSplitter;
import com.sn68.agent.dataagent.splitter.ParagraphTextSplitter;
import com.sn68.agent.dataagent.util.McpServerToolUtil;
import com.sn68.agent.dataagent.service.aimodelconfig.AiModelRegistry;
import com.sn68.agent.dataagent.strategy.EnhancedTokenCountBatchingStrategy;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.resolution.DelegatingToolCallbackResolver;
import org.springframework.ai.tool.resolution.SpringBeanToolCallbackResolver;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DataAgent的自动配置类
 *
 * @author vlsmb
 * @since 2025/9/28
 */
@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({ DataAgentProperties.class, CodeExecutorProperties.class, AgentSkillProperties.class,
		ToolCenterProperties.class, DigitalEmployeeProperties.class, TaskSchedulerProperties.class })
public class DataAgentConfiguration implements DisposableBean {

	/**
	 * 专用线程池，用于数据库操作的并行处理
	 */
	private static final String XX_CLOUD_OBJECT_MAPPER_BEAN_NAME = "objectMapper";

	private static final String SPRING_BOOT_OBJECT_MAPPER_BEAN_NAME = "jacksonObjectMapper";

	private ExecutorService rawDbOperationExecutor;

	private ExecutorService rawFlowResolverExecutor;

	private ExecutorService rawOrchestrationExecutor;

	private ExecutorService rawRouteRetrievalExecutor;

	private ExecutorService rawRouteModelExecutor;

	@Bean
	public static BeanFactoryPostProcessor dataAgentObjectMapperPrimaryResolver() {
		return beanFactory -> {
			if (!beanFactory.containsBeanDefinition(XX_CLOUD_OBJECT_MAPPER_BEAN_NAME)
					|| !beanFactory.containsBeanDefinition(SPRING_BOOT_OBJECT_MAPPER_BEAN_NAME)) {
				return;
			}
			beanFactory.getBeanDefinition(SPRING_BOOT_OBJECT_MAPPER_BEAN_NAME).setPrimary(false);
			beanFactory.getBeanDefinition(XX_CLOUD_OBJECT_MAPPER_BEAN_NAME).setPrimary(true);
		};
	}

	@Bean
	@ConditionalOnMissingBean(RestClientCustomizer.class)
	public RestClientCustomizer restClientCustomizer(@Value("${rest.connect.timeout:600}") long connectTimeout,
			@Value("${rest.read.timeout:600}") long readTimeout) {
		return restClientBuilder -> restClientBuilder
			.requestFactory(ClientHttpRequestFactoryBuilder.reactor().withCustomizer(factory -> {
				factory.setConnectTimeout(Duration.ofSeconds(connectTimeout));
				factory.setReadTimeout(Duration.ofSeconds(readTimeout));
			}).build());
	}

	@Bean
	@ConditionalOnMissingBean(WebClient.Builder.class)
	public WebClient.Builder webClientBuilder(@Value("${webclient.response.timeout:600}") long responseTimeout) {

		return WebClient.builder()
			.clientConnector(new ReactorClientHttpConnector(
					HttpClient.create().responseTimeout(Duration.ofSeconds(responseTimeout))));
	}

	/**
	 * 为了不必要的重复手动配置，不要在此添加其他向量的手动配置，如果扩展其他向量，请阅读spring ai文档
	 * <a href="https://springdoc.cn/spring-ai/api/vectordbs.html">...</a>
	 * 根据自己想要的向量，在pom文件引入 Boot Starter 依赖即可。
	 * <p>
	 * 内存实现只在 {@code spring.ai.vectorstore.type=simple} 显式声明时生效，不再作为属性缺失时的兜底：
	 * 它的索引在进程内、仅落盘为本地 JSON，多副本部署时 A 副本写入的知识 B 副本检索不到（PRD F-1）。
	 * 属性缺失时交由 Spring AI 的 starter 自动装配决定，宁可启动失败也不要静默退化成单机内存索引。
	 */
	@Primary
	@Bean
	@ConditionalOnMissingBean(VectorStore.class)
	@ConditionalOnProperty(name = "spring.ai.vectorstore.type", havingValue = "simple")
	public SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
		return SimpleVectorStore.builder(embeddingModel).build();
	}

	@Bean
	@ConditionalOnBean(SimpleVectorStore.class)
	public SimpleVectorStoreInitialization simpleVectorStoreInitialization(SimpleVectorStore vectorStore,
			DataAgentProperties properties) {
		return new SimpleVectorStoreInitialization(vectorStore, properties);
	}

	@Bean
	@ConditionalOnMissingBean(BatchingStrategy.class)
	public BatchingStrategy customBatchingStrategy(DataAgentProperties properties) {
		// 使用增强的批处理策略，同时考虑token数量和文本数量限制
		EncodingType encodingType;
		try {
			Optional<EncodingType> encodingTypeOptional = EncodingType
				.fromName(properties.getEmbeddingBatch().getEncodingType());
			encodingType = encodingTypeOptional.orElse(EncodingType.CL100K_BASE);
		}
		catch (Exception e) {
			log.warn("Unknown encodingType '{}', falling back to CL100K_BASE",
					properties.getEmbeddingBatch().getEncodingType());
			encodingType = EncodingType.CL100K_BASE;
		}

		return new EnhancedTokenCountBatchingStrategy(encodingType, properties.getEmbeddingBatch().getMaxTokenCount(),
				properties.getEmbeddingBatch().getReservePercentage(),
				properties.getEmbeddingBatch().getMaxTextCount());
	}

	@Bean
	public ToolCallbackResolver toolCallbackResolver(GenericApplicationContext context) {
		List<ToolCallback> allFunctionAndToolCallbacks = new ArrayList<>(
				McpServerToolUtil.excludeMcpServerTool(context, ToolCallback.class));
		McpServerToolUtil.excludeMcpServerTool(context, ToolCallbackProvider.class)
			.stream()
			.map(pr -> List.of(pr.getToolCallbacks()))
			.forEach(allFunctionAndToolCallbacks::addAll);

		var staticToolCallbackResolver = new StaticToolCallbackResolver(allFunctionAndToolCallbacks);

		var springBeanToolCallbackResolver = SpringBeanToolCallbackResolver.builder()
			.applicationContext(context)
			.build();

		return new DelegatingToolCallbackResolver(List.of(staticToolCallbackResolver, springBeanToolCallbackResolver));
	}

	/**
	 * 动态生成 EmbeddingModel 的代理 Bean。 原理： 1. 这是一个 Bean，Milvus/PgVector Starter 能看到它，启动不会报错。
	 * 2. 它是动态代理，内部没有写死任何方法。 3. 每次被调用时，它会执行 getTarget() -> registry.getEmbeddingModel()。
	 */
	@Bean
	@Primary
	public EmbeddingModel embeddingModel(AiModelRegistry registry) {

		// 1. 定义目标源 (TargetSource)
		TargetSource targetSource = new TargetSource() {
			@Override
			public Class<?> getTargetClass() {
				return EmbeddingModel.class;
			}

			@Override
			public boolean isStatic() {
				// 关键：声明是动态的，每次都要重新获取目标
				return false;
			}

			@Override
			public Object getTarget() {
				// 每次方法调用，都去注册表拿最新的
				return registry.getEmbeddingModel();
			}

			@Override
			public void releaseTarget(Object target) {
				// 无需释放
			}
		};

		// 2. 创建代理工厂
		ProxyFactory proxyFactory = new ProxyFactory();
		proxyFactory.setTargetSource(targetSource);
		// 代理接口
		proxyFactory.addInterface(EmbeddingModel.class);

		// 3. 返回动态生成的代理对象
		return (EmbeddingModel) proxyFactory.getProxy();
	}

	@Bean(name = "dbOperationExecutor")
	public ExecutorService dbOperationExecutor() {
		// 初始化专用线程池，用于数据库操作
		// 线程数量设置为CPU核心数的2倍，但不少于4个，不超过16个
		int corePoolSize = Math.max(4, Math.min(Runtime.getRuntime().availableProcessors() * 2, 16));
		log.info("Database operation executor initialized with {} threads", corePoolSize);

		// 自定义线程工厂
		ThreadFactory threadFactory = new ThreadFactory() {
			private final AtomicInteger threadNumber = new AtomicInteger(1);

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "db-operation-" + threadNumber.getAndIncrement());
				t.setDaemon(false);
				if (t.getPriority() != Thread.NORM_PRIORITY) {
					t.setPriority(Thread.NORM_PRIORITY);
				}
				return t;
			}
		};

		// 创建原生线程池
		ThreadPoolExecutor rawExecutor = new ThreadPoolExecutor(corePoolSize, corePoolSize, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(500), threadFactory, new ThreadPoolExecutor.CallerRunsPolicy());
		this.rawDbOperationExecutor = rawExecutor;

		return TtlExecutors.getTtlExecutorService(rawExecutor);
	}

	@Bean(name = "flowResolverExecutor")
	public ExecutorService flowResolverExecutor(DataAgentProperties properties) {
		DataAgentProperties.Flow flow = properties.getFlow() == null ? new DataAgentProperties.Flow()
				: properties.getFlow();
		int threads = clamp(flow.getMaxParallelResolvers(), 1, 16);
		int queueCapacity = clamp(flow.getExecutorQueueCapacity(), 1, 1024);
		ThreadFactory threadFactory = new ThreadFactory() {
			private final AtomicInteger threadNumber = new AtomicInteger(1);

			@Override
			public Thread newThread(Runnable runnable) {
				Thread thread = new Thread(runnable, "flow-resolver-" + threadNumber.getAndIncrement());
				thread.setDaemon(false);
				return thread;
			}
		};
		ThreadPoolExecutor rawExecutor = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(queueCapacity), threadFactory, new ThreadPoolExecutor.AbortPolicy());
		this.rawFlowResolverExecutor = rawExecutor;
		log.info("Flow resolver executor initialized with {} threads and queue capacity {}", threads, queueCapacity);
		return TtlExecutors.getTtlExecutorService(rawExecutor);
	}

	@Bean(name = "orchestrationExecutor")
	public ExecutorService orchestrationExecutor(DataAgentProperties properties) {
		DataAgentProperties.Orchestration orchestration = properties.getOrchestration();
		DataAgentProperties.OrchestrationExecutor executor = orchestration == null ? null : orchestration.getExecutor();
		if (executor == null || executor.getCorePoolSize() < 1 || executor.getMaxPoolSize() < executor.getCorePoolSize()
				|| executor.getQueueCapacity() < 1 || executor.getKeepAlive() == null
				|| executor.getKeepAlive().isNegative()) {
			throw new IllegalStateException("编排线程池配置无效");
		}
		ThreadFactory threadFactory = new ThreadFactory() {
			private final AtomicInteger threadNumber = new AtomicInteger(1);

			@Override
			public Thread newThread(Runnable runnable) {
				Thread thread = new Thread(runnable, "agent-orchestration-" + threadNumber.getAndIncrement());
				thread.setDaemon(false);
				return thread;
			}
		};
		ThreadPoolExecutor rawExecutor = new ThreadPoolExecutor(executor.getCorePoolSize(), executor.getMaxPoolSize(),
				executor.getKeepAlive().toMillis(), TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(executor.getQueueCapacity()), threadFactory, new ThreadPoolExecutor.AbortPolicy());
		this.rawOrchestrationExecutor = rawExecutor;
		log.info("Orchestration executor initialized with core={}, max={}, queueCapacity={}",
				executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
		return TtlExecutors.getTtlExecutorService(rawExecutor);
	}

	@Bean(name = "routeRetrievalExecutor")
	public ExecutorService routeRetrievalExecutor() {
		this.rawRouteRetrievalExecutor = routeExecutor("route-retrieval-", 200);
		return TtlExecutors.getTtlExecutorService(rawRouteRetrievalExecutor);
	}

	@Bean(name = "routeModelExecutor")
	public ExecutorService routeModelExecutor() {
		this.rawRouteModelExecutor = routeExecutor("route-model-", 100);
		return TtlExecutors.getTtlExecutorService(rawRouteModelExecutor);
	}

	private ThreadPoolExecutor routeExecutor(String threadPrefix, int queueCapacity) {
		ThreadFactory threadFactory = new ThreadFactory() {
			private final AtomicInteger threadNumber = new AtomicInteger(1);

			@Override
			public Thread newThread(Runnable runnable) {
				Thread thread = new Thread(runnable, threadPrefix + threadNumber.getAndIncrement());
				thread.setDaemon(false);
				return thread;
			}
		};
		return new ThreadPoolExecutor(4, 8, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(queueCapacity), threadFactory, new ThreadPoolExecutor.AbortPolicy());
	}

	private int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	@Override
	public void destroy() {
		shutdownExecutor(rawRouteModelExecutor, "Route model");
		shutdownExecutor(rawRouteRetrievalExecutor, "Route retrieval");
		shutdownExecutor(rawOrchestrationExecutor, "Agent orchestration");
		shutdownExecutor(rawFlowResolverExecutor, "Flow resolver");
		shutdownExecutor(rawDbOperationExecutor, "Database operation");
	}

	private void shutdownExecutor(ExecutorService executor, String name) {
		if (executor != null && !executor.isShutdown()) {
			log.info("Shutting down {} executor...", name.toLowerCase(Locale.ROOT));

			// 记录关闭前的状态，便于排查问题
			if (executor instanceof ThreadPoolExecutor tpe) {
				log.info("Executor Status before shutdown: [Queue Size: {}], [Active Count: {}], [Completed Tasks: {}]",
						tpe.getQueue().size(), tpe.getActiveCount(), tpe.getCompletedTaskCount());
			}

			// 1. 停止接收新任务
			executor.shutdown();

			try {
				// 2. 等待现有任务完成（包括队列中的）
				if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
					log.warn("Executor did not terminate in 60s. Forcing shutdown...");

					// 3. 超时强行关闭
					executor.shutdownNow();

					// 4. 再次确认是否关闭
					if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
						log.error("Executor failed to terminate completely.");
					}
				}
				else {
					log.info("{} executor terminated gracefully.", name);
				}
			}
			catch (InterruptedException e) {
				log.warn("Interrupted during executor shutdown. Forcing immediate shutdown.");
				executor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}

	@Bean(name = "token")
	public TextSplitter textSplitter(DataAgentProperties properties) {
		DataAgentProperties.TextSplitter textSplitterProps = properties.getTextSplitter();
		DataAgentProperties.TextSplitter.TokenTextSplitterConfig config = textSplitterProps.getToken();
		return new TokenTextSplitter(textSplitterProps.getChunkSize(), config.getMinChunkSizeChars(),
				config.getMinChunkLengthToEmbed(), config.getMaxNumChunks(), config.isKeepSeparator());
	}

	/**
	 * 递归字符文本分块器
	 *
	 * <p>用 {@link RecursiveOverlapTextSplitter} 而不是上游 RecursiveCharacterTextSplitter：上游不接收
	 * chunkOverlap，配置项一直是死的，切出来的相邻块零重叠。
	 * @param properties 分块配置
	 * @return RecursiveOverlapTextSplitter实例
	 */
	@Bean(name = "recursive")
	public TextSplitter recursiveTextSplitter(DataAgentProperties properties) {
		DataAgentProperties.TextSplitter textSplitterProps = properties.getTextSplitter();
		DataAgentProperties.TextSplitter.RecursiveTextSplitterConfig config = textSplitterProps.getRecursive();
		String[] separators = config.getSeparators();
		// 空数组会让上游跳过全部分隔符直接按 chunkSize 硬切，归一成 null 才能落到它的默认分隔符表
		return new RecursiveOverlapTextSplitter(textSplitterProps.getChunkSize(), config.getChunkOverlap(),
				separators == null || separators.length == 0 ? null : separators);
	}

	/**
	 * 句子分块器
	 * @param properties 分块配置
	 * @return SentenceSplitter实例
	 */
	@Bean(name = "sentence")
	public TextSplitter sentenceSplitter(DataAgentProperties properties) {
		DataAgentProperties.TextSplitter textSplitterConfig = properties.getTextSplitter();
		DataAgentProperties.TextSplitter.SentenceTextSplitterConfig sentenceConfig = textSplitterConfig.getSentence();

		return SentenceSplitter.builder()
			.withChunkSize(textSplitterConfig.getChunkSize())
			.withSentenceOverlap(sentenceConfig.getSentenceOverlap())
			.build();
	}

	/**
	 * 语义分块器
	 * @param properties 分块配置
	 * @param embeddingModel Embedding 模型
	 * @return SemanticTextSplitter实例
	 */
	@Bean(name = "semantic")
	public TextSplitter semanticSplitter(DataAgentProperties properties, EmbeddingModel embeddingModel) {
		DataAgentProperties.TextSplitter textSplitterProps = properties.getTextSplitter();
		DataAgentProperties.TextSplitter.SemanticTextSplitterConfig config = textSplitterProps.getSemantic();
		return SemanticTextSplitter.builder()
			.embeddingModel(embeddingModel)
			.minChunkSize(config.getMinChunkSize())
			.maxChunkSize(config.getMaxChunkSize())
			.similarityThreshold(config.getSimilarityThreshold())
			.build();
	}

	/**
	 * 段落分块器
	 * @param properties 分块配置
	 * @return ParagraphTextSplitter实例
	 */
	@Bean(name = "paragraph")
	public TextSplitter paragraphSplitter(DataAgentProperties properties) {
		DataAgentProperties.TextSplitter textSplitterProps = properties.getTextSplitter();
		DataAgentProperties.TextSplitter.ParagraphTextSplitterConfig config = textSplitterProps.getParagraph();
		return ParagraphTextSplitter.builder()
			.chunkSize(textSplitterProps.getChunkSize())
			.paragraphOverlapChars(config.getParagraphOverlapChars())
			.build();
	}

}
