/*
 * Copyright (c) sn68. All Rights Reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sn68.agent;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Repository;

import java.net.InetAddress;

/**
 * Standalone Business Agent application.
 *
 * @author sn68
 */
@Slf4j
@SpringBootApplication(scanBasePackages = "com.sn68.agent", exclude = MongoAutoConfiguration.class)
@MapperScan(value = "com.sn68.agent.**.repository", annotationClass = Repository.class)
public class AgentApplication {

	@SneakyThrows
	public static void main(String[] args) {
		final ConfigurableApplicationContext applicationContext = SpringApplication.run(AgentApplication.class, args);
		Environment env = applicationContext.getEnvironment();
		final String appName = env.getProperty("spring.application.name");
		String host = InetAddress.getLocalHost().getHostAddress();
		String port = env.getProperty("server.port");
		log.info("""
						----------------------------------------------------------
						\tApplication '{}' is running! Access URLs:
						\tDoc: \thttp://{}:{}/doc.html
						----------------------------------------------------------""",
				appName, host, port);
	}

}
