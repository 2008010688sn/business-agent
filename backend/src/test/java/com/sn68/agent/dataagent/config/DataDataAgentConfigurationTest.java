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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataDataAgentConfigurationTest {

	@Test
	void objectMapperPrimaryResolver_prefersXxCloudObjectMapper() {
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		RootBeanDefinition xxCloudObjectMapper = new RootBeanDefinition(ObjectMapper.class);
		RootBeanDefinition springBootObjectMapper = new RootBeanDefinition(ObjectMapper.class);
		xxCloudObjectMapper.setPrimary(true);
		springBootObjectMapper.setPrimary(true);
		beanFactory.registerBeanDefinition("objectMapper", xxCloudObjectMapper);
		beanFactory.registerBeanDefinition("jacksonObjectMapper", springBootObjectMapper);

		BeanFactoryPostProcessor resolver = DataAgentConfiguration.dataAgentObjectMapperPrimaryResolver();
		resolver.postProcessBeanFactory(beanFactory);

		assertTrue(beanFactory.getBeanDefinition("objectMapper").isPrimary());
		assertFalse(beanFactory.getBeanDefinition("jacksonObjectMapper").isPrimary());
	}

	/**
	 * PRD <b>F-1</b> 的规格契约（仓库内可验证的部分）：向量库默认实现必须是 {@code pgvector}，
	 * 且 pgvector starter 在 classpath。
	 * <p>
	 * <b>勿把断言改成 {@code simple} 迁就现状</b>：{@code simple} 是进程内索引 + 本地 JSON 落盘的
	 * 单机实现，多副本部署时 A 副本写入的知识 B 副本检索不到，只能作为本地开发的显式选项。
	 * 三处缺省值的失败关闭语义另由 {@link #vectorStoreDefault_isFailClosedAtEveryDefaultSite()} 钉住。
	 * <p>
	 * 数据源 {@code search_path} 那两条断言拆到 {@link #localPgvectorMode_setsSearchPathOnBothDataSources()}
	 * 并挂 {@code @Disabled}——它断言的配置不在本仓库内，原委见该方法注释。
	 */
	@Test
	void localPgvectorMode_hasPgvectorStarterDependency() throws Exception {
		Path moduleDir = Path.of("").toAbsolutePath();
		Properties properties = loadLocalProperties(moduleDir);

		assertEquals("pgvector", resolvePlaceholderDefault(properties.getProperty("spring.ai.vectorstore.type")));
		assertTrue(hasDependency(moduleDir.resolve("pom.xml"), "org.springframework.ai",
				"spring-ai-starter-vector-store-pgvector"));
	}

	/**
	 * <b>已知缺口（有意保留为 skipped，勿删断言，也不要为了转绿把数据源配置塞进 local.properties）</b>：
	 * {@code 20260618_pgvector_vector_store.sql} 把 {@code vector} 扩展建在 <b>{@code v4_ai} schema</b> 下
	 * （{@code embedding v4_ai.vector(1024)}），因此连接的 {@code search_path} 必须含 {@code v4_ai}，
	 * 否则 PgVectorStore 的 {@code ::vector} 转换会报「type vector does not exist」。这个要求是真实的。
	 * <p>
	 * 但<b>本仓库无从验证、也不该在此声明</b>：
	 * <ol>
	 * <li>全仓库<b>没有任何模块</b>在 {@code src/main/resources} 里声明 {@code spring.datasource.*}
	 * —— 数据源配置来自 {@code classpath:config/datasource.yaml}（见 {@code application.yml} 的
	 * {@code optional:classpath:config/datasource.yaml}）。把
	 * {@code connection-init-sql} 写进本模块的 {@code local.properties}，会让 AI 模块成为唯一的例外，
	 * 还可能与平台级配置相互覆盖。</li>
	 * <li>本条断言自 {@code e9315a613d}（2026-06-18「pgvector引用」）随本测试一同加入，
	 * 而<b>该提交并未改动任何 properties 文件</b>——{@code connection-init-sql} 从未在
	 * {@code local.properties} 中出现过，本断言<b>自诞生起就是红的</b>，只是被 {@code skipTests=true}
	 * 盖了两个月，又被同方法内先失败的 {@code vectorstore.type} 断言挡在后面。</li>
	 * <li>生产已在跑 pgvector（维护者确认 Nacos 已下发 {@code DATA_AGENT_VECTORSTORE_TYPE}），
	 * 说明线上早已有一套可用的 {@code search_path} 安排（可能在 Nacos {@code db.properties}、
	 * 也可能是 JDBC URL 的 {@code currentSchema}）。仓库内看不到它，本地也无 PostgreSQL 可验证，
	 * 照着猜写一份等于用未验证配置换一个绿灯。</li>
	 * </ol>
	 * 需维护者确认 {@code search_path} 归属（共享 {@code db.properties} 还是模块级配置）后再启用本条。
	 */
	@Disabled("""
			断言 local.properties 声明两个数据源的 connection-init-sql=SET search_path TO v4_ai, public，但该配置从未在本仓库出现过：\
			全仓库没有任何模块在 src/main/resources 声明 spring.datasource.*，数据源配置统一来自 Nacos 的共享 db.properties。\
			search_path 含 v4_ai 是 pgvector 的真实要求（vector 扩展建在 v4_ai 下），但归属需维护者确认，且本地无 PostgreSQL 可验证。\
			详见 服务治理/06-ai/AI模块整改治理PRD.md 的 F-1。""")
	@Test
	void localPgvectorMode_setsSearchPathOnBothDataSources() throws Exception {
		Path moduleDir = Path.of("").toAbsolutePath();
		Properties properties = loadLocalProperties(moduleDir);

		assertEquals("SET search_path TO v4_ai, public",
				resolvePgSchemaPlaceholder(properties, properties.getProperty("spring.datasource.hikari.connection-init-sql")));
		assertEquals("SET search_path TO v4_ai, public",
				resolvePgSchemaPlaceholder(properties,
						properties.getProperty("spring.datasource.dynamic.hikari.connection-init-sql")));
	}

	private static Properties loadLocalProperties(Path moduleDir) throws Exception {
		Properties properties = new Properties();
		try (var reader = Files.newBufferedReader(
				moduleDir.resolve("src/main/resources/dataagent/local.properties"), StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		return properties;
	}

	/**
	 * PRD <b>F-1</b> 提示 {@code simple} 的缺省散布在<b>三处</b>，只改其中一处会让上一条用例转绿、
	 * 而属性整体缺失时另外两处仍回落到单机内存索引——PRD 称之为「假绿」。这里逐处钉住失败关闭语义：
	 * {@code simple} 只在显式声明时生效，任何一处改回兜底都会在此报错。
	 */
	@Test
	void vectorStoreDefault_isFailClosedAtEveryDefaultSite() throws Exception {
		Path moduleDir = Path.of("").toAbsolutePath();

		String configuration = normalizeWhitespace(
				Files.readString(moduleDir.resolve("src/main/java/com/xx/cloud/ai/dataagent/config/"
						+ "DataAgentConfiguration.java"), StandardCharsets.UTF_8));
		assertTrue(
				configuration.contains(
						"@ConditionalOnProperty(name = \"spring.ai.vectorstore.type\", havingValue = \"simple\")"),
				"SimpleVectorStore 必须仅在显式声明 simple 时装配");
		assertFalse(configuration.contains("\"spring.ai.vectorstore.type\", havingValue = \"simple\", matchIfMissing"),
				"matchIfMissing 会让属性缺失时静默退化成单机内存索引");

		String hybridFactory = normalizeWhitespace(
				Files.readString(moduleDir.resolve("src/main/java/com/xx/cloud/ai/dataagent/service/hybrid/factory/"
						+ "HybridRetrievalStrategyFactory.java"), StandardCharsets.UTF_8));
		assertFalse(hybridFactory.contains("${spring.ai.vectorstore.type:simple}"),
				"混合检索策略的缺省值不应回落到 simple");
	}

	@Test
	void localPgvectorMode_hasPgvectorSchemaScript() throws Exception {
		Path moduleDir = Path.of("").toAbsolutePath();
		String sql = Files.readString(
				moduleDir.resolve("src/main/resources/dataagent/sql/pg/20260618_pgvector_vector_store.sql"),
				StandardCharsets.UTF_8);

		assertTrue(sql.contains("CREATE EXTENSION vector WITH SCHEMA v4_ai"));
		assertTrue(sql.contains("ALTER EXTENSION vector SET SCHEMA v4_ai"));
		assertTrue(sql.contains("SET search_path TO v4_ai, public"));
		assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS v4_ai.vector_store"));
		assertTrue(sql.contains("embedding v4_ai.vector(1024)"));
	}

	private static String normalizeWhitespace(String source) {
		return source.replaceAll("\\s+", " ");
	}

	private static String resolvePlaceholderDefault(String value) {
		if (value == null || !value.startsWith("${") || !value.endsWith("}")) {
			return value;
		}
		int defaultSeparatorIndex = value.indexOf(':');
		if (defaultSeparatorIndex < 0) {
			return value;
		}
		return value.substring(defaultSeparatorIndex + 1, value.length() - 1);
	}

	private static String resolvePgSchemaPlaceholder(Properties properties, String value) {
		if (value == null) {
			return null;
		}
		return value.replace("${pg.schema:v4_ai}", properties.getProperty("pg.schema", "v4_ai"));
	}

	private static boolean hasDependency(Path pomPath, String groupId, String artifactId) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(false);
		Document document = factory.newDocumentBuilder()
			.parse(new InputSource(new StringReader(Files.readString(pomPath, StandardCharsets.UTF_8))));
		NodeList dependencies = document.getElementsByTagName("dependency");
		for (int i = 0; i < dependencies.getLength(); i++) {
			Element dependency = (Element) dependencies.item(i);
			if (groupId.equals(childText(dependency, "groupId"))
					&& artifactId.equals(childText(dependency, "artifactId"))) {
				return true;
			}
		}
		return false;
	}

	private static String childText(Element element, String tagName) {
		NodeList nodes = element.getElementsByTagName(tagName);
		if (nodes.getLength() == 0) {
			return null;
		}
		return nodes.item(0).getTextContent().trim();
	}

}
