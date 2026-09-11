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

import com.sn68.agent.dataagent.entity.AgentFile;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.AgentFileMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Local-disk file service replacing Suite FileFeign. Public methods match the former SuiteFileService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalFileService {

	private static final int BUFFER_SIZE = 8192;

	private static final String DEFAULT_PUBLIC_BASE = "http://localhost:10108/ai";

	private static final Pattern FILE_ID_PATTERN = Pattern.compile("/files/(\\d+)(?:/)?$");

	private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("yyyyMM");

	private final AgentFileMapper agentFileMapper;

	private final DataAgentProperties dataAgentProperties;

	private final AuthenticationContext authenticationContext;

	public FilePreviewResp upload(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw CheckedException.badRequest("上传文件不能为空");
		}
		String originalName = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename().trim()
				: "unnamed";
		String contentType = StringUtils.hasText(file.getContentType()) ? file.getContentType()
				: "application/octet-stream";
		Path storagePath = resolveStoragePath(originalName);
		try {
			Files.createDirectories(storagePath.getParent());
			file.transferTo(storagePath);
		}
		catch (IOException ex) {
			log.warn("Failed to store uploaded file. name={}", originalName, ex);
			throw CheckedException.fail("文件保存失败");
		}
		AgentFile entity = AgentFile.builder()
			.tenantId(currentTenantId())
			.originalName(originalName)
			.contentType(contentType)
			.size(file.getSize())
			.storagePath(storagePath.toAbsolutePath().toString())
			.deleted(false)
			.build();
		agentFileMapper.insert(entity);
		return toPreview(entity);
	}

	public FilePreviewResp findById(Long id) {
		AgentFile entity = agentFileMapper.findById(id);
		if (entity == null || Boolean.TRUE.equals(entity.getDeleted())) {
			return null;
		}
		return toPreview(entity);
	}

	public byte[] readBytes(Long id) {
		AgentFile entity = requireEntity(id);
		try {
			return Files.readAllBytes(Path.of(entity.getStoragePath()));
		}
		catch (IOException ex) {
			log.warn("Failed to read local file. id={}, path={}", id, entity.getStoragePath(), ex);
			throw CheckedException.fail("文件读取失败");
		}
	}

	public Map<String, FilePreviewResp> findByPaths(Collection<String> paths) {
		if (CollectionUtils.isEmpty(paths)) {
			return Map.of();
		}
		Map<String, FilePreviewResp> result = new LinkedHashMap<>();
		for (String path : paths) {
			if (!StringUtils.hasText(path)) {
				continue;
			}
			try {
				String normalized = normalizeSuiteReference(path);
				Long id = parseFileId(normalized);
				if (id == null) {
					log.warn("Skip file path without id. path={}", abbreviateForLog(path));
					continue;
				}
				FilePreviewResp preview = findById(id);
				if (preview == null) {
					continue;
				}
				result.put(normalized, preview);
				result.put(String.valueOf(id), preview);
				if (StringUtils.hasText(preview.getPath())) {
					result.put(preview.getPath(), preview);
				}
			}
			catch (RuntimeException ex) {
				log.warn("Skip invalid local file path for preview. path={}, reason={}",
						abbreviateForLog(path), ex.getMessage());
			}
		}
		return result;
	}

	public Map<String, FilePreviewResp> findDisplayPreviews(Collection<String> paths) {
		if (CollectionUtils.isEmpty(paths)) {
			return Map.of();
		}
		Set<String> validPaths = new LinkedHashSet<>();
		for (String path : paths) {
			if (!StringUtils.hasText(path)) {
				continue;
			}
			try {
				validPaths.add(normalizeSuiteReference(path));
			}
			catch (RuntimeException ex) {
				log.warn("Skip invalid local file path for display preview. path={}, reason={}",
						abbreviateForLog(path), ex.getMessage());
			}
		}
		if (validPaths.isEmpty()) {
			return Map.of();
		}
		try {
			return findByPaths(validPaths);
		}
		catch (RuntimeException ex) {
			log.warn("Failed to resolve local file display previews.", ex);
			return Map.of();
		}
	}

	public FilePreviewResp requirePreview(String path) {
		String normalized = normalizeSuiteReference(path);
		FilePreviewResp resp = findByPaths(Set.of(normalized)).get(normalized);
		if (resp == null || !StringUtils.hasText(resp.getPreviewUrl())) {
			throw CheckedException.badRequest("Suite file preview is unavailable");
		}
		return resp;
	}

	public String resolvePreviewUrl(String path) {
		return requirePreview(path).getPreviewUrl();
	}

	public DownloadedFile download(String path, long maxBytes) {
		return download(path, maxBytes, resolveDownloadTimeoutMs());
	}

	public DownloadedFile download(String path, long maxBytes, int timeoutMs) {
		FilePreviewResp preview = requirePreview(path);
		Long id = parseFileId(preview.getPath());
		if (id == null) {
			id = parseFileId(path);
		}
		AgentFile entity = requireEntity(id);
		long effectiveMaxBytes = maxBytes > 0 ? maxBytes : suiteFileProperties().getMaxDownloadSize();
		try {
			byte[] bytes = readLimited(Files.newInputStream(Path.of(entity.getStoragePath())), effectiveMaxBytes);
			return new DownloadedFile(path.trim(), preview, bytes, normalizeContentType(entity.getContentType()));
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("Failed to download local file. path={}", path, ex);
			throw CheckedException.fail("Suite file download failed");
		}
	}

	public DownloadedFile downloadImage(String path, long maxBytes, Collection<String> allowedContentTypes) {
		return downloadImage(path, maxBytes, allowedContentTypes, resolveDownloadTimeoutMs());
	}

	public DownloadedFile downloadImage(String path, long maxBytes, Collection<String> allowedContentTypes,
			int timeoutMs) {
		DownloadedFile file = download(path, maxBytes, timeoutMs);
		String detectedContentType = detectImageContentType(file.bytes());
		if (!StringUtils.hasText(detectedContentType)) {
			throw CheckedException.badRequest("Only png, jpg, jpeg and webp images are supported");
		}
		Set<String> allowed = normalizeContentTypes(allowedContentTypes);
		if (!allowed.isEmpty() && !allowed.contains(detectedContentType)) {
			throw CheckedException.badRequest("Unsupported image content type");
		}
		return new DownloadedFile(file.path(), file.preview(), file.bytes(), detectedContentType);
	}

	public DownloadedFile downloadDocument(String path, long maxBytes, Collection<String> allowedContentTypes,
			int timeoutMs) {
		DownloadedFile file = download(path, maxBytes, timeoutMs);
		String detected = detectDocumentContentType(file.bytes(), file.contentType(),
				file.preview() == null ? null : file.preview().getOriginalName());
		if (!StringUtils.hasText(detected)) {
			throw CheckedException.badRequest("不支持的文档类型");
		}
		Set<String> allowed = normalizeContentTypes(allowedContentTypes);
		if (!allowed.isEmpty() && !allowed.contains(detected)) {
			throw CheckedException.badRequest("不支持的文档类型");
		}
		return new DownloadedFile(file.path(), file.preview(), file.bytes(), detected);
	}

	public Resource getFileResource(String path) {
		DownloadedFile file = download(path, suiteFileProperties().getMaxDownloadSize());
		return new ByteArrayResource(file.bytes()) {
			@Override
			public String getFilename() {
				String originalName = file.preview().getOriginalName();
				return StringUtils.hasText(originalName) ? originalName : file.path();
			}
		};
	}

	public void validateSuitePath(String path) {
		normalizeSuiteReference(path);
	}

	public void validateSuiteReference(String path) {
		normalizeSuiteReference(path);
	}

	public String publicFileUrl(Long id) {
		return publicBase().replaceAll("/+$", "") + "/files/" + id;
	}

	private AgentFile requireEntity(Long id) {
		if (id == null) {
			throw CheckedException.badRequest("Suite file preview is unavailable");
		}
		AgentFile entity = agentFileMapper.findById(id);
		if (entity == null || Boolean.TRUE.equals(entity.getDeleted()) || !StringUtils.hasText(entity.getStoragePath())) {
			throw CheckedException.badRequest("Suite file preview is unavailable");
		}
		return entity;
	}

	private FilePreviewResp toPreview(AgentFile entity) {
		String url = publicFileUrl(entity.getId());
		return FilePreviewResp.builder()
			.path(url)
			.originalName(entity.getOriginalName())
			.previewUrl(url)
			.build();
	}

	private String normalizeSuiteReference(String path) {
		if (!StringUtils.hasText(path)) {
			throw CheckedException.badRequest("Suite file path cannot be empty");
		}
		String normalized = path.trim();
		rejectNonSuitePath(normalized);
		if (normalized.startsWith("/")) {
			return normalized;
		}
		URI uri = parseHttpUri(normalized, "path");
		if (StringUtils.hasText(uri.getQuery())) {
			throw CheckedException.badRequest("Suite file path cannot contain temporary query parameters");
		}
		return normalized;
	}

	private URI parseHttpUri(String value, String fieldName) {
		try {
			URI uri = URI.create(value.trim());
			String scheme = uri.getScheme();
			String host = uri.getHost();
			if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || !StringUtils.hasText(host)) {
				throw CheckedException.badRequest("Invalid suite file " + fieldName);
			}
			return uri;
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("Invalid suite file " + fieldName);
		}
	}

	private void rejectNonSuitePath(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		if (lower.startsWith("data:") || lower.startsWith("file:")) {
			throw CheckedException.badRequest("Only suite file path is supported");
		}
	}

	Long parseFileId(String path) {
		if (!StringUtils.hasText(path)) {
			return null;
		}
		String trimmed = path.trim();
		Matcher matcher = FILE_ID_PATTERN.matcher(trimmed);
		if (matcher.find()) {
			return Long.valueOf(matcher.group(1));
		}
		try {
			URI uri = URI.create(trimmed);
			String uriPath = uri.getPath();
			if (StringUtils.hasText(uriPath)) {
				matcher = FILE_ID_PATTERN.matcher(uriPath);
				if (matcher.find()) {
					return Long.valueOf(matcher.group(1));
				}
			}
		}
		catch (Exception ignored) {
			// fall through
		}
		int slash = trimmed.lastIndexOf('/');
		String last = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
		if (last.matches("\\d+")) {
			return Long.valueOf(last);
		}
		return null;
	}

	private byte[] readLimited(InputStream inputStream, long maxBytes) throws IOException {
		try (InputStream input = inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[BUFFER_SIZE];
			long total = 0;
			int read;
			while ((read = input.read(buffer)) != -1) {
				total += read;
				if (total > maxBytes) {
					throw CheckedException.badRequest("Suite file size exceeds the limit");
				}
				output.write(buffer, 0, read);
			}
			return output.toByteArray();
		}
	}

	private int resolveDownloadTimeoutMs() {
		int configured = suiteFileProperties().getDownloadTimeoutMs();
		return configured > 0 ? configured : 5000;
	}

	private DataAgentProperties.SuiteFile suiteFileProperties() {
		return dataAgentProperties.getSuiteFile() == null ? new DataAgentProperties.SuiteFile()
				: dataAgentProperties.getSuiteFile();
	}

	private String publicBase() {
		DataAgentProperties.Storage storage = dataAgentProperties.getStorage();
		if (storage != null && StringUtils.hasText(storage.getPublicBaseUrl())) {
			return storage.getPublicBaseUrl().trim();
		}
		return DEFAULT_PUBLIC_BASE;
	}

	private Path resolveStoragePath(String originalName) {
		DataAgentProperties.Storage storage = dataAgentProperties.getStorage();
		String root = storage != null && StringUtils.hasText(storage.getLocalDir()) ? storage.getLocalDir().trim()
				: "./data/files";
		String ext = "";
		int dot = originalName.lastIndexOf('.');
		if (dot >= 0 && dot < originalName.length() - 1) {
			ext = originalName.substring(dot);
		}
		return Path.of(root, LocalDate.now().format(MONTH_DIR), UUID.randomUUID() + ext).toAbsolutePath().normalize();
	}

	private String currentTenantId() {
		try {
			String tenantId = authenticationContext.tenantId();
			return StringUtils.hasText(tenantId) ? tenantId.trim() : "default";
		}
		catch (Exception ex) {
			return "default";
		}
	}

	private Set<String> normalizeContentTypes(Collection<String> contentTypes) {
		if (CollectionUtils.isEmpty(contentTypes)) {
			return Set.of();
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (String contentType : contentTypes) {
			String value = normalizeContentType(contentType);
			if (StringUtils.hasText(value)) {
				normalized.add(value);
			}
		}
		return normalized;
	}

	private String normalizeContentType(String contentType) {
		if (!StringUtils.hasText(contentType)) {
			return null;
		}
		return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
	}

	private String abbreviateForLog(String value) {
		if (!StringUtils.hasText(value) || value.length() <= 128) {
			return value;
		}
		return value.substring(0, 128) + "...";
	}

	private String detectDocumentContentType(byte[] bytes, String responseContentType, String originalName) {
		if (bytes != null && bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F'
				&& bytes[4] == '-') {
			return "application/pdf";
		}
		String name = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
		if (name.endsWith(".pdf")) {
			return "application/pdf";
		}
		if (name.endsWith(".docx")) {
			return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
		}
		if (name.endsWith(".xlsx")) {
			return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
		}
		if (name.endsWith(".xls")) {
			return "application/vnd.ms-excel";
		}
		if (name.endsWith(".csv")) {
			return "text/csv";
		}
		if (name.endsWith(".md")) {
			return "text/markdown";
		}
		if (name.endsWith(".txt") || name.endsWith(".log")) {
			return "text/plain";
		}
		if (name.endsWith(".wav")) {
			return "audio/wav";
		}
		if (name.endsWith(".mp3")) {
			return "audio/mpeg";
		}
		if (name.endsWith(".m4a")) {
			return "audio/mp4";
		}
		if (name.endsWith(".ogg")) {
			return "audio/ogg";
		}
		if (name.endsWith(".webm")) {
			return "audio/webm";
		}
		return normalizeContentType(responseContentType);
	}

	private String detectImageContentType(byte[] bytes) {
		if (bytes == null || bytes.length < 4) {
			return null;
		}
		if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
			return "image/png";
		}
		if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
			return "image/jpeg";
		}
		if (bytes.length >= 12 && bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46
				&& bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50) {
			return "image/webp";
		}
		return null;
	}

	public record DownloadedFile(String path, FilePreviewResp preview, byte[] bytes, String contentType) {
	}

}
