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
package com.sn68.agent.dataagent.service.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.entity.AgentFile;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.AgentFileMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void requirePreview_rejectsDataAndFileSchemes() {
		LocalFileService service = newService();

		CheckedException data = assertThrows(CheckedException.class, () -> service.requirePreview("data:text/plain,hi"));
		assertEquals("Only suite file path is supported", data.getMessage());

		CheckedException file = assertThrows(CheckedException.class, () -> service.requirePreview("file:///tmp/a.png"));
		assertEquals("Only suite file path is supported", file.getMessage());
	}

	@Test
	void findByPaths_parsesIdFromAbsoluteUrl() throws Exception {
		AgentFileMapper mapper = mock(AgentFileMapper.class);
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		when(authenticationContext.tenantId()).thenReturn("default");
		DataAgentProperties properties = new DataAgentProperties();
		properties.getStorage().setLocalDir(tempDir.toString());
		properties.getStorage().setPublicBaseUrl("http://localhost:10108/ai");
		LocalFileService service = new LocalFileService(mapper, properties, authenticationContext);
		Path stored = tempDir.resolve("a.png");
		Files.write(stored, new byte[] { 1, 2, 3 });
		when(mapper.findById(12L)).thenReturn(AgentFile.builder()
			.id(12L)
			.originalName("a.png")
			.contentType("image/png")
			.size(3L)
			.storagePath(stored.toString())
			.deleted(false)
			.build());

		String path = "http://localhost:10108/ai/files/12";
		Map<String, FilePreviewResp> result = service.findByPaths(java.util.List.of(path));

		assertEquals(path, result.get(path).getPreviewUrl());
		assertEquals("a.png", result.get(path).getOriginalName());
	}

	@Test
	void download_readsLocalDisk() throws Exception {
		AgentFileMapper mapper = mock(AgentFileMapper.class);
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		DataAgentProperties properties = new DataAgentProperties();
		properties.getStorage().setLocalDir(tempDir.toString());
		properties.getStorage().setPublicBaseUrl("http://localhost:10108/ai");
		LocalFileService service = new LocalFileService(mapper, properties, authenticationContext);
		Path stored = tempDir.resolve("note.txt");
		Files.writeString(stored, "hello");
		when(mapper.findById(9L)).thenReturn(AgentFile.builder()
			.id(9L)
			.originalName("note.txt")
			.contentType("text/plain")
			.size(5L)
			.storagePath(stored.toString())
			.deleted(false)
			.build());

		LocalFileService.DownloadedFile file = service.download("http://localhost:10108/ai/files/9", 1024);

		assertEquals("hello", new String(file.bytes()));
	}

	private LocalFileService newService() {
		return new LocalFileService(mock(AgentFileMapper.class), new DataAgentProperties(),
				mock(AuthenticationContext.class));
	}

}
