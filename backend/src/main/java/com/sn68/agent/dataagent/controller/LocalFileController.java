/*
 * Copyright (c) sn68. All Rights Reserved.
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
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.service.file.FilePreviewResp;
import com.sn68.agent.dataagent.service.file.LocalFileService;
import com.sn68.agent.framework.commons.annotation.IgnoreGlobalResponse;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Local multipart upload and content serving. Paths are absolute http URLs under {publicBase}/files/{id}.
 *
 * @author sn68
 */
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
@Tag(name = "Local Files")
public class LocalFileController {

	private final LocalFileService localFileService;

	@PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "上传本地文件")
	public FilePreviewResp upload(@RequestParam("file") MultipartFile file) {
		return localFileService.upload(file);
	}

	@IgnoreGlobalResponse(description = "file content")
	@GetMapping("/{id}")
	@Operation(summary = "下载/预览本地文件")
	public ResponseEntity<Resource> get(@PathVariable Long id) {
		FilePreviewResp preview = localFileService.findById(id);
		if (preview == null) {
			throw CheckedException.notFound("文件不存在");
		}
		byte[] bytes = localFileService.readBytes(id);
		String filename = StringUtils.hasText(preview.getOriginalName()) ? preview.getOriginalName()
				: String.valueOf(id);
		MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
		try {
			if (StringUtils.hasText(preview.getOriginalName())) {
				String name = preview.getOriginalName().toLowerCase();
				if (name.endsWith(".png")) {
					mediaType = MediaType.IMAGE_PNG;
				}
				else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
					mediaType = MediaType.IMAGE_JPEG;
				}
				else if (name.endsWith(".pdf")) {
					mediaType = MediaType.APPLICATION_PDF;
				}
				else if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".log")) {
					mediaType = MediaType.TEXT_PLAIN;
				}
			}
		}
		catch (Exception ignored) {
			mediaType = MediaType.APPLICATION_OCTET_STREAM;
		}
		return ResponseEntity.ok()
			.contentType(mediaType)
			.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename.replace("\"", "") + "\"")
			.body(new ByteArrayResource(bytes));
	}

}
