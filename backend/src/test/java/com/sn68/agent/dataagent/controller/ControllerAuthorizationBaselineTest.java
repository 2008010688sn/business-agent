/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Open-access: controllers must not reintroduce Sa-Token permission annotations.
 */
class ControllerAuthorizationBaselineTest {

	@Test
	@DisplayName("主代码不得残留 SaCheckPermission")
	void noSaCheckPermissionInMainSources() throws IOException {
		Path mainJava = Path.of("src/main/java");
		if (!Files.isDirectory(mainJava)) {
			mainJava = Path.of("backend/src/main/java");
		}
		List<String> hits = new ArrayList<>();
		try (Stream<Path> stream = Files.walk(mainJava)) {
			stream.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
				try {
					List<String> lines = Files.readAllLines(path);
					for (int i = 0; i < lines.size(); i++) {
						if (lines.get(i).contains("@SaCheckPermission") || lines.get(i).contains("cn.dev33.satoken")) {
							hits.add(path + ":" + (i + 1));
						}
					}
				}
				catch (IOException ex) {
					throw new RuntimeException(ex);
				}
			});
		}
		assertTrue(hits.isEmpty(), () -> "残留鉴权注解/import:\n" + String.join("\n", hits));
	}

}
