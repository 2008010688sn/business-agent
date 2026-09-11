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

import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationMode;
import com.sn68.agent.dataagent.constant.Constant;
import com.sn68.agent.dataagent.service.llm.LlmServiceEnum;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = Constant.PROJECT_PROPERTIES_PREFIX)
public class DataAgentProperties {

	private LlmServiceEnum llmServiceType = LlmServiceEnum.STREAM;

	/**
	 * spring.ai.alibaba.data-agent.embedding-batch.encoding-type=cl100k_base
	 * spring.ai.alibaba.data-agent.embedding-batch.max-token-count=2000
	 * spring.ai.alibaba.data-agent.embedding-batch.reserve-percentage=0.2
	 * spring.ai.alibaba.data-agent.embedding-batch.max-text-count=10
	 */
	private EmbeddingBatch embeddingBatch = new EmbeddingBatch();

	private VectorStoreProperties vectorStore = new VectorStoreProperties();

	private ReportTemplate reportTemplate = new ReportTemplate();

	private ReportVisualization reportVisualization = new ReportVisualization();

	private Report report = new Report();

	private Security security = new Security();

	private Crypto crypto = new Crypto();

	private DatasourceRuntimeCache datasourceRuntimeCache = new DatasourceRuntimeCache();

	private Memory memory = new Memory();

	private LongTermMemory longTermMemory = new LongTermMemory();

	private ChatAttachment chatAttachment = new ChatAttachment();

	private Multimodal multimodal = new Multimodal();

	private SuiteFile suiteFile = new SuiteFile();

	private Storage storage = new Storage();

	private Evaluation evaluation = new Evaluation();

	private Optimization optimization = new Optimization();

	/**
	 * PR-4：授权执行（PEP）配置组——SHADOW/ENFORCE 模式开关的统一入口（v1.2 清单 PR-4 钦定位置）。
	 * mode/enforceTenantIds 与 pep 包消费视图 {@code PepAuthorizationProperties} 绑定同一配置子树
	 * （spring.ai.agent.authorization.*），单处配置双视图消费，无漂移。
	 */
	private Authorization authorization = new Authorization();

	@Valid
	private Runtime runtime = new Runtime();

	private Orchestration orchestration = new Orchestration();

	private Flow flow = new Flow();

	/**
	 * 公网网页读取与本系统 origin 分类。抽键默认开启，无需开关。
	 * 运维只配 {@code app-origins}（公网域名的内部站点）和 {@code fetch-enabled}。
	 */
	private WebEvidence webEvidence = new WebEvidence();

	/**
	 * sql执行失败重试次数
	 */
	private int maxSqlRetryCount = 10;

	/**
	 * sql优化最多次数
	 */
	private int maxSqlOptimizeCount = 10;

	/**
	 * sql优化分数阈值
	 */
	private double sqlScoreThreshold = 0.95;

	private TextSplitter textSplitter = new TextSplitter();

	/**
	 * 最多保留的对话轮数
	 */
	private int maxturnhistory = 5;

	/**
	 * 单次规划最大长度限制
	 */
	private int maxplanlength = 2000;

	// 每张表的最大预估列数
	private int maxColumnsPerTable = 50;

	/**
	 * 是否启用SQL执行结果图表判断，默认启用
	 */
	private boolean enableSqlResultChart = true;

	/**
	 * 执行SQL结果图表化超时时间，默认3000ms
	 */
	private Long enrichSqlResultTimeout = 3000L;

	@Getter
	@Setter
	public static class Runtime {

		private Duration totalTimeout = Duration.ofSeconds(180);

		/**
		 * CHAT 一轮执行绝对截止。开跑写入 deadline_at；WAITING_* 不吃该截止。
		 */
		private Duration chatDeadline = Duration.ofMinutes(20);

		/**
		 * CHAT 等待输入/审批的独立长超时，到期 CANCELLED(WAIT_TIMEOUT) 释放会话槽。
		 */
		private Duration chatWaitTimeout = Duration.ofDays(7);

		private Duration modelTimeout = Duration.ofSeconds(45);

		private Duration toolTimeout = Duration.ofSeconds(30);

		private Duration finishBuffer = Duration.ofSeconds(2);

		private Duration modelHttpConnectTimeout = Duration.ofSeconds(5);

		private int reactMaxIterations = 16;

		/** 业务 SEARCH 未指定数量时的默认结果行数。 */
		private int defaultResultRows = 20;

		/** 业务 SEARCH 单次允许返回的最大结果行数。 */
		private int maxResultRows = 200;

		private int maxModelCalls = 16;

		private int maxToolCalls = 16;

		private long maxPromptTokens = 5_000_000L;

		private int noProgressMaxCompletedIdenticalCalls = 2;

		/**
		 * 连续 N 次 datasource SEARCH 空结果后拦截下一次 SEARCH。默认关闭，避免误杀合法探表。
		 */
		private boolean emptySearchNoProgressEnabled = false;

		/**
		 * 连续空 SEARCH 次数阈值；仅 {@link #emptySearchNoProgressEnabled} 为 true 时生效。
		 */
		private int emptySearchNoProgressMaxConsecutive = 2;

		/**
		 * 单次运行允许 {@code GET_TABLE_SCHEMA} 探查的不同表数量。同一张表再读不占新名额。
		 * 默认 7 以覆盖账单结算等多表关联的复杂场景；超限后不再放行探查，直接返回
		 * schemaBudgetExceededPayload 引导模型基于已加载结构直接 SEARCH。
		 */
		private int maxSchemaTablesPerRequest = 7;

		private boolean staleTurnRecoveryEnabled = false;

		private Duration staleTurnGrace = Duration.ofSeconds(60);

		private int staleTurnRecoveryBatchSize = 100;

		@Valid
		private Routing routing = new Routing();

		@Valid
		private Deterministic deterministic = new Deterministic();

		@Getter
		@Setter
		public static class Deterministic {

			@NotNull
			private Duration totalTimeout = Duration.ofSeconds(20);

			@NotNull
			private Duration plannerTimeout = Duration.ofSeconds(8);

			@NotNull
			private Duration sqlTimeout = Duration.ofSeconds(6);

			private int maxOutputTokens = 2048;

			private int maxAttempts = 2;

			@NotNull
			private Duration finishBuffer = Duration.ofSeconds(1);

			@AssertTrue(message = "deterministic policy values must be greater than zero")
			public boolean isConfigurationPositive() {
				return positive(totalTimeout) && positive(plannerTimeout) && positive(sqlTimeout)
						&& maxOutputTokens > 0 && maxAttempts > 0 && positive(finishBuffer);
			}

			@AssertTrue(message = "deterministic stage timeouts must fit within total-timeout after finish-buffer")
			public boolean isRuntimeBudgetConfigurationValid() {
				if (totalTimeout == null || plannerTimeout == null || sqlTimeout == null || finishBuffer == null) {
					return true;
				}
				Duration stageBudget = totalTimeout.minus(finishBuffer);
				return positive(stageBudget) && plannerTimeout.plus(sqlTimeout).compareTo(stageBudget) <= 0;
			}

			private boolean positive(Duration value) {
				return value != null && !value.isNegative() && !value.isZero();
			}

		}

	}

	@Getter
	@Setter
	public static class Routing {

		@NotNull
		private Duration totalTimeout = Duration.ofMillis(1800);

		@NotNull
		private Duration modelTimeout = Duration.ofMillis(1100);

		@NotNull
		private Duration vectorTimeout = Duration.ofMillis(250);

		@NotNull
		private Duration modelMinStart = Duration.ofMillis(400);

		@NotNull
		private Duration finishBuffer = Duration.ofMillis(150);

		@NotNull
		private Duration modelProbeTimeout = Duration.ofSeconds(30);

		@NotNull
		private Duration modelProbeConnectTimeout = Duration.ofSeconds(3);

		@NotNull
		private Duration embeddingProbeTimeout = Duration.ofSeconds(30);

		/** 路由模型是否优先使用仅提交路由计划的 Function Calling。 */
		private boolean functionCallingEnabled = false;

		@AssertTrue(message = "routing timeouts must be greater than zero")
		public boolean isTimeoutConfigurationPositive() {
			return positive(totalTimeout) && positive(modelTimeout) && positive(vectorTimeout)
					&& positive(modelMinStart) && positive(finishBuffer) && positive(modelProbeTimeout)
					&& positive(modelProbeConnectTimeout) && positive(embeddingProbeTimeout);
		}

		@AssertTrue(message = "routing.model-timeout + routing.vector-timeout must be <= routing.total-timeout - routing.finish-buffer; do not copy runtime.total-timeout onto routing.total-timeout")
		public boolean isRuntimeBudgetConfigurationValid() {
			if (totalTimeout == null || modelTimeout == null || vectorTimeout == null || modelMinStart == null
					|| finishBuffer == null) {
				return true;
			}
			Duration stageBudget = totalTimeout.minus(finishBuffer);
			return positive(stageBudget) && modelTimeout.compareTo(stageBudget) <= 0
					&& vectorTimeout.compareTo(stageBudget) <= 0
					&& vectorTimeout.plus(modelTimeout).compareTo(stageBudget) <= 0
					&& modelMinStart.compareTo(modelTimeout) <= 0;
		}

		@AssertTrue(message = "routing model-probe-connect-timeout must not exceed model-probe-timeout")
		public boolean isProbeBudgetConfigurationValid() {
			return modelProbeTimeout == null || modelProbeConnectTimeout == null
					|| modelProbeConnectTimeout.compareTo(modelProbeTimeout) <= 0;
		}

		private boolean positive(Duration value) {
			return value != null && !value.isNegative() && !value.isZero();
		}

	}

	@Getter
	@Setter
	public static class Report {

		/** 报告模型可见的结构化结果预览行数，不限制后端表格和图表数据量。 */
		private int promptPreviewRows = 50;

		/** 专业叙事（LLM）单次生成超时时间（毫秒），超时按降级处理回落确定性报告。 */
		private long narrativeTimeoutMs = 60000;

		/** 报告编译耗时告警阈值（毫秒），仅打日志，不中断。 */
		private int compileWarnMs = 80;

	}

	@Getter
	@Setter
	public static class Orchestration {

		/**
		 * 协作者执行调度模式：EVENT_DRIVEN（事件驱动 DAG 调度，单分支终态立即释放就绪下游，
		 * 不等整批）或 LEGACY（整批 barrier）。事件驱动依赖权威运行镜像（agent_runtime_run/step），
		 * 镜像不可用时自动回落整批执行，行为与 LEGACY 一致。
		 */
		private String scheduler = "EVENT_DRIVEN";

		private int defaultMaxCollaboratorsPerRun;

		private String defaultFailureStrategy;

		private boolean defaultExposeTrace;

		private boolean defaultEnabled;

		private OrchestrationExecutor executor = new OrchestrationExecutor();

	}

	@Getter
	@Setter
	public static class OrchestrationExecutor {

		private int corePoolSize;

		private int maxPoolSize;

		private int queueCapacity;

		private Duration keepAlive;

	}

	@Getter
	@Setter
	public static class Flow {

		/** Flow 字段稀疏抽取模型单次调用超时时间；实际值还受 Flow 版本和请求剩余 deadline 限制。 */
		private Duration extractTimeout = Duration.ofSeconds(15);

		/**
		 * Optional technical ceiling for one structured extraction response. Zero
		 * derives the budget from the selected model context window and output cap.
		 */
		private int extractMaxTokens;

		/** 平台允许的 Resolver 最大并发数；Flow 版本配置不能超过平台硬上限。 */
		private int maxParallelResolvers = 4;

		/** 单次 Flow 允许的 Resolver 最大波次数。 */
		private int maxResolverWaves = 8;

		/** 单批 Resolver 或 forEach 的最大条目数。 */
		private int maxFanOutItems = 20;

		/** Flow Resolver 专用有界线程池的队列容量。 */
		private int executorQueueCapacity = 32;

	}

	@Getter
	@Setter
	public static class WebEvidence {

		/** 是否允许模型调用 web_fetch 读公网页面。默认关。打开后仍不抓本系统页面。 */
		private boolean fetchEnabled = false;

		/**
		 * 本环境前端/网关/API 的 origin，逗号分隔。公网域名的内部站点必须配。
		 * RFC1918 / localhost 不用配，抽键时自动当本系统。
		 */
		private List<String> appOrigins = new ArrayList<>();

		public void setAppOrigins(List<String> appOrigins) {
			List<String> normalized = new ArrayList<>();
			if (appOrigins != null) {
				for (String origin : appOrigins) {
					if (origin != null && !origin.isBlank()) {
						normalized.add(origin.trim());
					}
				}
			}
			this.appOrigins = normalized;
		}

	}

	@Getter
	@Setter
	public static class ChatAttachment {

		private int maxImageCount = 9;

		private int maxModelImageCount = 6;

		private long maxImageSize = 5L * 1024 * 1024;

		private long maxTotalSize = 20L * 1024 * 1024;

		private int downloadTimeoutMs = 5000;

		private List<String> allowedContentTypes = List.of("image/png", "image/jpeg", "image/webp");

	}

	@Getter
	@Setter
	public static class Multimodal {

		private boolean enabled = true;

		private int imageMaxEdgePx = 1024;

		private int pdfMaxPagesInContext = 15;

		/** 扫描件/关键图最多保留页数，与 pdfMaxPagesInContext 取较小值。 */
		private int pdfMaxKeptCharts = 5;

		private int sqlKeepAllRows = 30;

		private int sqlMaxCellChars = 80;

		private long fusionTimeoutMs = 8000L;

		/** 扫描件按页渲染给已有视觉模型；false 时仍提示 OCR 未开放。不是独立 OCR 产品。 */
		private boolean ocrEnabled = true;

		private int maxDocumentCount = 5;

		private long maxDocumentSize = 20L * 1024 * 1024;

		private List<String> allowedDocumentContentTypes = List.of("application/pdf",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel",
				"text/csv", "text/plain", "text/markdown", "audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp3",
				"audio/mp4", "audio/ogg", "audio/webm", "video/webm");

		private boolean visionExtractEnabled = true;

		private long visionExtractTimeoutMs = 8000L;

		private int visionExtractMaxOutputTokens = 640;

		private int visionExtractMaxImagesPerCall = 4;

		private int visionExtractMaxCardChars = 2000;

	}

	@Getter
	@Setter
	public static class SuiteFile {

		private long maxDownloadSize = 50L * 1024 * 1024;

		private int downloadTimeoutMs = 5000;

	}

	@Getter
	@Setter
	public static class Storage {

		private String publicBaseUrl = "http://localhost:10108/ai";

		private String localDir = "./data/files";

	}

	@Getter
	@Setter
	public static class Evaluation {

		private Executor executor = new Executor();

	}

	@Getter
	@Setter
	public static class Optimization {

		/**
		 * 是否允许 LLM 自动生成自进化候选。Java 兜底 false，禁止默认烧预算。
		 */
		private boolean autoGenerateEnabled = false;

		/**
		 * 送入生成器的失败轨迹最大字符数（脱敏后截断）。
		 */
		private int maxTraceChars = 2000;

		/**
		 * 是否开启 DataAgent 生产问答的只读 Shadow 镜像。Java 兜底 false。
		 * 开启后仍必须命中 {@link #shadowTenantIds}，空名单不镜像任何租户。
		 */
		private boolean shadowEnabled = false;

		/**
		 * Shadow 租户白名单。未列入的租户不做镜像。
		 */
		private List<String> shadowTenantIds = new ArrayList<>();

	}

	@Getter
	@Setter
	public static class Executor {

		/**
		 * 评估后台执行最大并发数。
		 */
		private int maxConcurrency = 2;

		/**
		 * 评估后台执行队列容量。
		 */
		private int queueCapacity = 100;

	}

	@Getter
	@Setter
	public static class Memory {

		private AutoContext autoContext = new AutoContext();

	}

	@Getter
	@Setter
	public static class AutoContext {

		/**
		 * 是否启用当前会话自动上下文压缩。
		 */
		private boolean enabled = true;

		private List<String> agentTypes = List.of("knowledge_base");

		/**
		 * 是否启用接口手动上下文压缩。
		 */
		private boolean manualEnabled = true;

		/**
		 * 上下文使用比例达到该阈值后触发压缩。
		 */
		private double tokenRatio = 0.7;

		/**
		 * 给系统提示词、工具调用、长期记忆和运行时结构预留的 token 数。
		 */
		private int reserveTokens = 2000;

		/**
		 * 消息数达到该阈值时允许触发压缩。
		 */
		private int msgThreshold = 60;

		/**
		 * 始终保留最近 N 条 AgentScope 消息。
		 */
		private int lastKeep = 30;

		/**
		 * 手动压缩时始终保留最近 N 条 AgentScope 消息。
		 */
		private int manualLastKeep = 10;

		/**
		 * 单条工具结果或消息超过该字符数时优先摘要化。
		 */
		private long largePayloadThreshold = 5000;

		/**
		 * 大 payload 摘要后保留的预览字符数。
		 */
		private int offloadSinglePreview = 200;

		/**
		 * 连续工具消息达到该数量时优先合并压缩。
		 */
		private int minConsecutiveToolMessages = 4;

		/**
		 * 低于该 token 规模不触发压缩。
		 */
		private int minCompressionTokenThreshold = 3000;

		/**
		 * 当前轮可压缩内容的目标压缩比例。
		 */
		private double currentRoundCompressionRatio = 0.3;

	}

	@Getter
	@Setter
	public static class LongTermMemory {

		/**
		 * 是否启用长期记忆能力总开关。
		 */
		private boolean enabled = true;

		/**
		 * Agent 新建或未配置时是否默认开启长期记忆召回。
		 */
		private boolean recallEnabledDefault = false;

		/**
		 * Agent 新建或未配置时是否默认开启自动写入。
		 */
		private boolean writeEnabledDefault = false;

		/**
		 * 单次问答最多召回多少条长期记忆。
		 */
		private int defaultTopK = 5;

		/**
		 * 长期记忆召回相似度阈值。
		 */
		private double defaultSimilarityThreshold = 0.75;

		/**
		 * 长期记忆最多占用的上下文 token 预算。
		 */
		private int defaultInjectionTokenBudget = 1200;

		/**
		 * 低于该重要度的记忆默认不召回。
		 */
		private double defaultMinImportance = 0.5;

		/**
		 * 候选记忆低于该置信度不落库。
		 */
		private double minExtractionConfidence = 0.7;

		/**
		 * 是否允许自动写入历史结论类记忆。
		 */
		private boolean autoWriteEpisodicEnabled = false;

		/**
		 * 是否允许自动写入敏感业务结果。
		 */
		private boolean autoWriteSensitiveResultEnabled = false;

		/**
		 * 自动抽取长期记忆的最大耗时。
		 */
		private long extractionTimeoutMs = 30000;

		/**
		 * 长期记忆召回检索最大耗时。
		 */
		private long recallTimeoutMs = 3000;

	}

	@Getter
	@Setter
	public static class Security {

		/**
		 * Thinking查看按钮权限码，未配置时默认拒绝。
		 */
		private String thinkingPermission;

		private String answerSourcePermission;

		private String callChainPermission;

		private String usageViewPermission = "data-agent:usage:view";

		private String usageManagePermission = "data-agent:usage:manage";

	}

	@Getter
	@Setter
	public static class Crypto {

		/**
		 * 数据源口令、模型 API Key 等敏感配置的加密总开关，默认开启。
		 * <p>
		 * 默认值刻意是失败关闭的：关闭时租户口令以明文入库且没有任何信号，漏配环境变量的代价远大于
		 * 显式关闭的成本。开启而未配置 {@code key} 时启动直接失败，见
		 * {@code SensitiveConfigCryptoService#validate()}。
		 */
		private boolean enabled = true;

		private String key;

		private String oldKey;

		private Migration migration = new Migration();

	}

	@Getter
	@Setter
	public static class Migration {

		private boolean enabled = false;

	}

	@Getter
	@Setter
	public static class DatasourceRuntimeCache {

		private boolean enabled = true;

		private Duration ttl = Duration.ofMinutes(5);

	}

	@Getter
	@Setter
	public static class ReportTemplate {

		// Marked.js (Markdown 解析器) 南方科技大学开源软件镜像站
		private String markedUrl = "https://mirrors.sustech.edu.cn/cdnjs/ajax/libs/marked/12.0.0/marked.min.js";

		// ECharts (图表库) 南方科技大学开源软件镜像站
		private String echartsUrl = "https://mirrors.sustech.edu.cn/cdnjs/ajax/libs/echarts/5.5.0/echarts.min.js";

	}

	@Getter
	@Setter
	public static class ReportVisualization {

		/**
		 * 是否启用分析报告图表候选。
		 */
		private boolean enabled = true;

		/**
		 * 单份报告最多生成的图表候选数量。
		 */
		private int maxCharts = 5;

		/**
		 * 分类图最多展示的分类数量，超出部分汇总为“其他”。
		 */
		private int topN = 12;

		/**
		 * 趋势图最多展示的数据点数量。
		 */
		private int maxTrendPoints = 120;

		/**
		 * 多指标图最多展示的指标数量。
		 */
		private int maxMetricsPerChart = 4;

		/**
		 * 饼图最多展示的分类数量。
		 */
		private int pieMaxCategories = 10;

	}

	@Getter
	@Setter
	public static class TextSplitter {

		/**
		 * 默认分块大小，基于token数量 默认值：1000
		 */
		private int chunkSize = 1000;

		/**
		 * TokenTextSplitter 策略配置
		 */
		private TokenTextSplitterConfig token = new TokenTextSplitterConfig();

		/**
		 * RecursiveCharacterTextSplitter 策略配置
		 */
		private RecursiveTextSplitterConfig recursive = new RecursiveTextSplitterConfig();

		/**
		 * SentenceTextSplitter 策略配置
		 */
		private SentenceTextSplitterConfig sentence = new SentenceTextSplitterConfig();

		/**
		 * SemanticTextSplitter 策略配置
		 */
		private SemanticTextSplitterConfig semantic = new SemanticTextSplitterConfig();

		/**
		 * ParagraphTextSplitter 策略配置
		 */
		private ParagraphTextSplitterConfig paragraph = new ParagraphTextSplitterConfig();

		/**
		 * TokenTextSplitter 策略配置
		 */
		@Getter
		@Setter
		public static class TokenTextSplitterConfig {

			/**
			 * 最小分块字符数 默认值：400
			 */
			private int minChunkSizeChars = 400;

			/**
			 * 嵌入最小分块长度 默认值：10
			 */
			private int minChunkLengthToEmbed = 10;

			/**
			 * 最大分块数量 默认值：5000
			 */
			private int maxNumChunks = 5000;

			/**
			 * 是否保留分隔符 默认值：true
			 */
			private boolean keepSeparator = true;

		}

		/**
		 * RecursiveCharacterTextSplitter 策略配置
		 */
		@Getter
		@Setter
		public static class RecursiveTextSplitterConfig {

			/**
			 * 重叠区域字符数 默认值：200（0 表示相邻分块不重叠）
			 */
			private int chunkOverlap = 200;

			/**
			 * 分隔符列表（如果为 null，该类内部有默认的分隔符列表）
			 */
			private String[] separators = null;

		}

		/**
		 * SentenceTextSplitter 策略配置
		 */
		@Getter
		@Setter
		public static class SentenceTextSplitterConfig {

			/**
			 * 句子重叠数量 默认值：1（保留前一个分块的最后1个句子）
			 */
			private int sentenceOverlap = 1;

		}

		/**
		 * SemanticTextSplitter 策略配置
		 */
		@Getter
		@Setter
		public static class SemanticTextSplitterConfig {

			/**
			 * 最小分块大小 默认值：200
			 */
			private int minChunkSize = 200;

			/**
			 * 最大分块大小 默认值：1000
			 */
			private int maxChunkSize = 1000;

			/**
			 * 语义相似度阈值 默认值：0.5（0-1之间，越低越容易分块）
			 */
			private double similarityThreshold = 0.5;

		}

		/**
		 * ParagraphTextSplitter 策略配置
		 */
		@Getter
		@Setter
		public static class ParagraphTextSplitterConfig {

			/**
			 * 段落重叠字符数 默认值：200（保留前一个分块的最后200个字符，而非段落数量）
			 */
			private int paragraphOverlapChars = 200;

		}

	}

	@Getter
	@Setter
	public static class EmbeddingBatch {

		/**
		 * encodingType 默认值：cl100k_base，适用于OpenAI等模型
		 */
		private String encodingType = "cl100k_base";

		/**
		 * 每批次最大令牌数 值越小，每批次文档越少，但更安全 值越大，处理效率越高，但可能超出API限制 建议值：2000-8000，根据实际API限制调整
		 */
		private int maxTokenCount = 8000;

		/**
		 * 预留百分比 用于预留缓冲空间，避免超出限制 建议值：0.1-0.2（10%-20%）
		 */
		private double reservePercentage = 0.2;

		/**
		 * 每批次最大文本数量 适用于DashScope等有文本数量限制的API DashScope限制为10
		 */
		private int maxTextCount = 10;

	}

	@Getter
	@Setter
	public static class VectorStoreProperties {

		// 专门给召回Table 用的配置
		private int tableTopkLimit = 10;

		// 设置低尽可能保证表不会召回漏掉
		private double tableSimilarityThreshold = 0.2;

		// 全局默认配置（给 BusinessTerm、SkillKnowledge 等使用）
		/**
		 * 相似度阈值配置，用于过滤相似度分数大于等于此阈值的文档
		 */
		private double defaultSimilarityThreshold = 0.4;

		/**
		 * 查询时返回的最大文档数量
		 */
		private int defaultTopkLimit = 8;

		/**
		 * 一次删除操作中，最多删除的文档数量
		 */
		private int batchDelTopkLimit = 5000;

		/**
		 * 是否启用混合搜索
		 */
		private boolean enableHybridSearch = false;

		/**
		 * Elasticsearch最小分数阈值，用于es执行关键词搜索时过滤相关性较低的文档
		 */
		private double elasticsearchMinScore = 0.5;

		/**
		 * SimpleVectorStore本地序列化文件地址
		 */
		private String filePath = "./vectorstore/vectorstore.json";

	}

	/**
	 * PR-4：授权执行（PEP）配置组（v1.2 清单 PR-4/PR-9 钦定开关集）。
	 *
	 * <p>上线默认 SHADOW：只记录影子日志不拦截，普通 DataAgent 行为不变（验收 1.5/6.6）；
	 * ENFORCE 按 enforceTenantIds 白名单逐租户灰度（PR-9 门禁，差异归零后开启）。
	 * mode/enforceTenantIds 的 pep 消费视图见
	 * {@code com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties}。</p>
	 */
	@Getter
	@Setter
	public static class Authorization {

		/**
		 * 全局默认模式：SHADOW 只记录不拦截；ENFORCE 为强制模式（白名单租户优先命中）。
		 */
		private PepAuthorizationMode mode = PepAuthorizationMode.SHADOW;

		/**
		 * ENFORCE 租户白名单：命中租户按 ENFORCE 处理，未命中租户一律 SHADOW（灰度切换最小单元）。
		 */
		private List<String> enforceTenantIds = new ArrayList<>();

		/**
		 * SQL 线缺策略拒绝开关（PR-9 ENFORCE 租户开启）：true 时规则缺失策略按 DENY 处理；
		 * 默认 false 保持 ALLOW_ON_MISSING——SHADOW 下 DataAgent SQL 行为不变。
		 */
		private boolean denyOnMissingSql = false;

		/**
		 * 旧 visibility 写接口冻结开关（PR-9 消费）：true 时旧写接口拒绝、读仍走适配器，
		 * 等价验证后再评估 Grant 迁移。默认 false 不影响现网旧接口。
		 */
		private boolean freezeLegacyVisibilityWrites = false;

		/**
		 * PR-9 灰度 ENFORCE 门禁配置组：租户进入 enforceTenantIds 白名单前的差异率达标校验，
		 * 数据源为 PR-10 最新影子差异报告（latestReport 的 enforceGatePassed）。
		 */
		private EnforceGate enforceGate = new EnforceGate();
	
		/**
		 * PR-9 门禁配置项（spring.ai.agent.authorization.enforce-gate.*）。
		 *
		 * <p>门禁是灰度放行决策的守卫而非灰度开关本身：enabled 不改变 DataAgent 运行时行为，
		 * 只影响放行校验是否提供担保；关闭后所有准入评估按 fail-closed 拒绝（验收 6.6 的
		 * “灰度开关默认关闭”指 mode/enforceTenantIds，与本开关不同层）。</p>
		 */
		@Getter
		@Setter
		public static class EnforceGate {
	
			/**
			 * 门禁总开关，默认开启（fail-closed 保护默认在）：false 时准入评估一律拒绝，
			 * 仅应急场景显式关闭并同步回滚白名单。
			 */
			private boolean enabled = true;
	
			/**
			 * 租户可比样本下限（matched+mismatched，ORIGINAL_ONLY 不计）：低于该值 fail-closed 拒绝放行，
			 * 挡住「有报告但零可比样本」的误放（观测窗口内 SHADOW 无事件时 PR-10 口径 gatePassed=true）。
			 * v1.2 清单未钦定数值，取保守默认 1（必须有可比样本）；管理方可按租户规模上调。
			 */
			private long minComparable = 1;
	
		}
	
	}

}
