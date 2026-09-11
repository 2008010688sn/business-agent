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
package com.sn68.agent.dataagent.service.tts.impl;

import lombok.extern.slf4j.Slf4j;
import com.sn68.agent.dataagent.dto.tts.TtsVoiceSampleResp;
import com.sn68.agent.dataagent.entity.TtsVoiceSample;
import com.sn68.agent.dataagent.repository.TtsVoiceSampleMapper;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.dataagent.service.tts.VoiceSampleService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 音色试听样本管理实现：维护音色样本的生成、存储与查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceSampleServiceImpl implements VoiceSampleService {

	private static final long MAX_SAMPLE_SIZE = 20L * 1024 * 1024;

	private static final Path SAMPLE_DIR = Path.of("uploads", "data-agent", "voice-samples");

	private static final Set<String> SUPPORTED_TYPES = Set.of("audio/wav", "audio/x-wav", "audio/mpeg",
			"audio/mp3", "audio/mp4", "audio/m4a", "audio/webm", "video/webm");

	private final TtsVoiceSampleMapper sampleMapper;

	private final TtsDtoConverter converter;

	private final PlatformScopePermissionService platformScopePermissionService;

	@Override
	public TtsVoiceSampleResp upload(MultipartFile file, String transcript, Boolean consentConfirmed) {
		validate(file, consentConfirmed);
		String fileId = UUID.randomUUID().toString();
		String extension = resolveExtension(file);
		Path target = SAMPLE_DIR.resolve(fileId + extension).normalize();
		try {
			Files.createDirectories(SAMPLE_DIR);
			file.transferTo(target);
		}
		catch (IOException ex) {
			throw CheckedException.fail("保存音色样本失败：" + ex.getMessage());
		}
		Instant now = Instant.now();
		TtsVoiceSample sample = new TtsVoiceSample();
		sample.setFileId(fileId);
		sample.setFileName(file.getOriginalFilename());
		sample.setFilePath(target.toString().replace('\\', '/'));
		sample.setContentType(normalizeContentType(file.getContentType()));
		sample.setTranscript(trimToNull(transcript));
		sample.setTenantId(requireCurrentTenantId());
		sample.setConsentConfirmed(true);
		sample.setStatus("READY");
		sample.setCreateTime(now);
		sample.setLastModifyTime(now);
		sample.setDeleted(false);
		sampleMapper.insert(sample);
		return converter.toDTO(sample);
	}

	@Override
	public List<TtsVoiceSampleResp> list() {
		return sampleMapper.findAll(requireCurrentTenantId()).stream().map(converter::toDTO).toList();
	}

	@Override
	public void delete(Long id) {
		TtsVoiceSample sample = sampleMapper.findById(id);
		if (sample == null || !requireCurrentTenantId().equals(sample.getTenantId())) {
			return;
		}
		sample.setDeleted(true);
		sample.setLastModifyTime(Instant.now());
		sampleMapper.updateById(sample);
		if (StringUtils.hasText(sample.getFilePath())) {
			try {
				Files.deleteIfExists(Path.of(sample.getFilePath()));
			}
			catch (IOException ex) {
				// 元数据删除优先，文件清理失败不阻断接口，但残留文件必须可追查。
				log.warn("音色样本文件清理失败, 已残留在磁盘上, sampleId={}", id, ex);
			}
		}
	}

	private void validate(MultipartFile file, Boolean consentConfirmed) {
		if (file == null || file.isEmpty()) {
			throw CheckedException.badRequest("音色样本不能为空。");
		}
		if (!Boolean.TRUE.equals(consentConfirmed)) {
			throw CheckedException.badRequest("上传本地参考音色前必须确认拥有录音和音色授权。");
		}
		if (file.getSize() > MAX_SAMPLE_SIZE) {
			throw CheckedException.badRequest("音色样本不能超过 20MB。");
		}
		String contentType = normalizeContentType(file.getContentType());
		if (!SUPPORTED_TYPES.contains(contentType)) {
			throw CheckedException.badRequest("仅支持 wav、mp3、m4a、webm 音频样本。");
		}
	}

	private String resolveExtension(MultipartFile file) {
		String name = file.getOriginalFilename();
		if (StringUtils.hasText(name) && name.contains(".")) {
			String ext = name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
			if (ext.matches("\\.(wav|mp3|m4a|mp4|webm)")) {
				return ext;
			}
		}
		return switch (normalizeContentType(file.getContentType())) {
			case "audio/wav", "audio/x-wav" -> ".wav";
			case "audio/mp4", "audio/m4a" -> ".m4a";
			case "audio/webm", "video/webm" -> ".webm";
			default -> ".mp3";
		};
	}

	private String normalizeContentType(String contentType) {
		if (!StringUtils.hasText(contentType)) {
			return "";
		}
		return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
	}

	private String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private String requireCurrentTenantId() {
		return platformScopePermissionService.requireCurrentTenantId("音色样本");
	}

}
