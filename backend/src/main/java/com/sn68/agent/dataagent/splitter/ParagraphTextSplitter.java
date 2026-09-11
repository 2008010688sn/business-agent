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
package com.sn68.agent.dataagent.splitter;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生产级段落文本分块器 特性：递归降级策略（段落->句子->字符）、智能Overlap、防文本丢失
 *
 * @author zihenzzz
 * @since 2025-01-03
 */
@Slf4j
@Builder
public class ParagraphTextSplitter extends TextSplitter {

	private final int chunkSize;

	/**
	 * 段落重叠字符数
	 */
	private final int paragraphOverlapChars;

	/**
	 * 段落分隔符：至少两个连续的换行符
	 */
	private static final Pattern PARAGRAPH_PATTERN = Pattern.compile("\\n\\s*\\n+");

	private static final Pattern sentencePattern = Pattern.compile("[^。！？.!?\\n]+[。！？.!?\\n]*");

	@Override
	public List<String> splitText(String text) {
		if (text == null || text.trim().isEmpty()) {
			return List.of();
		}

		// 1. 按段落粗切
		String[] paragraphs = PARAGRAPH_PATTERN.split(text);
		log.debug("Split text into {} paragraphs", paragraphs.length);

		List<String> chunks = new ArrayList<>();
		StringBuilder currentChunk = new StringBuilder();

		for (String paragraph : paragraphs) {
			String trimmedParagraph = paragraph.trim();
			if (trimmedParagraph.isEmpty()) {
				continue;
			}

			// --- 情况 A: 遇到超大段落 (递归处理) ---
			if (trimmedParagraph.length() > chunkSize) {
				currentChunk = consumeLargeParagraph(trimmedParagraph, chunks, currentChunk);
				continue;
			}

			// --- 情况 B: 普通段落 (积累处理) ---

			// 计算添加这个段落后的总长度
			int separatorLength = currentChunk.length() > 0 ? 2 : 0;
			int potentialLength = currentChunk.length() + separatorLength + trimmedParagraph.length();

			// 如果加上当前段落会超过 chunkSize，先保存当前块
			if (potentialLength > chunkSize && currentChunk.length() > 0) {
				chunks.add(currentChunk.toString().trim());
				// 提取 overlap 内容作为新块的开始
				currentChunk = extractOverlap(currentChunk.toString());
			}

			// 添加当前段落
			if (currentChunk.length() > 0) {
				currentChunk.append("\n\n");
			}
			currentChunk.append(trimmedParagraph);
		}

		// 处理最后的尾巴
		if (currentChunk.length() > 0) {
			chunks.add(currentChunk.toString().trim());
		}

		log.info("Created {} paragraph chunks", chunks.size());
		return chunks;
	}

	/**
	 * 处理超过 chunkSize 的段落：先结算缓存区，再把大段落递归切成子块并逐个结算。
	 * @param chunks 已完成块的收集器，子块会直接写入
	 * @param currentChunk 进入大段落前的缓存区
	 * @return 处理完毕后供后续段落续写的缓存区（含最后一个子块的 overlap）
	 */
	private StringBuilder consumeLargeParagraph(String trimmedParagraph, List<String> chunks,
			StringBuilder currentChunk) {
		log.debug("Processing large paragraph length: {}", trimmedParagraph.length());

		// 1. 先结算当前缓存区 (Buffer)，确保之前的上下文不丢失
		if (currentChunk.length() > 0) {
			chunks.add(currentChunk.toString().trim());
			// 提取 Overlap 留给大段落的开头使用
			currentChunk = extractOverlap(currentChunk.toString());
		}

		// 2. 切分大段落
		// 注意：这里切分出来的 subChunks 每一个都已经接近 chunkSize 了
		List<String> subChunks = splitLargeParagraph(trimmedParagraph);

		// 3. 逐个处理子块
		for (String subChunk : subChunks) {
			// 检查：Overlap + 当前子块 是否会超限？
			int potentialLen = currentChunk.length() + (currentChunk.length() > 0 ? 2 : 0) + subChunk.length();

			if (potentialLen > chunkSize) {
				// 拼接 Overlap 会溢出时放弃 Overlap，让当前 subChunk 独立成块，防止超限
				if (currentChunk.length() > 0) {
					currentChunk = new StringBuilder();
				}
			}

			if (currentChunk.length() > 0) {
				currentChunk.append("\n\n");
			}
			currentChunk.append(subChunk);

			// 当前子块处理完，立即结算，为下一个子块准备 overlap
			chunks.add(currentChunk.toString().trim());
			currentChunk = extractOverlap(currentChunk.toString());
		}
		return currentChunk;
	}

	/**
	 * 从已完成的块中提取 overlap 内容 策略：尝试智能贴合段落边界，如果找不到段落边界，则硬截取
	 */
	private StringBuilder extractOverlap(String chunk) {
		if (paragraphOverlapChars <= 0 || chunk == null || chunk.isEmpty()) {
			return new StringBuilder();
		}

		int len = chunk.length();
		// 如果块本身就很小，小于 overlap 要求，那就全拿
		if (len <= paragraphOverlapChars) {
			return new StringBuilder(chunk);
		}

		// 1. 初步截取
		int overlapStart = len - paragraphOverlapChars;
		String rawOverlap = chunk.substring(overlapStart);

		// 2. 尝试寻找最近的段落边界 "\n\n"，让 Overlap 从完整的段落开始
		// 这里的逻辑是：不要从段落中间截断，尽量从段落头开始
		int firstParagraphBreak = rawOverlap.indexOf("\n\n");

		if (firstParagraphBreak != -1 && firstParagraphBreak + 2 < rawOverlap.length()) {
			// 找到了边界，且边界后还有内容。丢弃边界前的半截段落，保留后面的完整段落
			return new StringBuilder(rawOverlap.substring(firstParagraphBreak + 2));
		}

		// 3. 如果找不到段落边界（说明最后一段很长），那就只能硬截取了，但最好避开句子中间
		// 可以在这里加一个寻找句号的逻辑，但为了性能和通用性，直接返回 rawOverlap 也是可接受的
		return new StringBuilder(rawOverlap.trim());
	}

	/**
	 * 切分过大的段落 (逻辑保持不变，你的实现已经很好了)
	 */
	private List<String> splitLargeParagraph(String paragraph) {
		List<String> subChunks = new ArrayList<>();

		// 1. 尝试按句子切分
		Matcher matcher = sentencePattern.matcher(paragraph);

		StringBuilder currentChunk = new StringBuilder();
		int lastMatchEnd = 0;

		while (matcher.find()) {
			String sentence = matcher.group();
			lastMatchEnd = matcher.end();

			if (sentence.length() > chunkSize) {
				if (currentChunk.length() > 0) {
					subChunks.add(currentChunk.toString().trim());
					currentChunk = new StringBuilder();
				}
				subChunks.addAll(splitByChars(sentence));
				continue;
			}

			if (currentChunk.length() + sentence.length() > chunkSize && currentChunk.length() > 0) {
				subChunks.add(currentChunk.toString().trim());
				currentChunk = new StringBuilder();
			}
			currentChunk.append(sentence);
		}

		if (lastMatchEnd < paragraph.length()) {
			String remaining = paragraph.substring(lastMatchEnd);
			if (!remaining.trim().isEmpty()) {
				if (remaining.length() > chunkSize) {
					if (currentChunk.length() > 0) {
						subChunks.add(currentChunk.toString().trim());
						currentChunk = new StringBuilder();
					}
					subChunks.addAll(splitByChars(remaining));
				}
				else {
					if (currentChunk.length() + remaining.length() > chunkSize && currentChunk.length() > 0) {
						subChunks.add(currentChunk.toString().trim());
						currentChunk = new StringBuilder();
					}
					currentChunk.append(remaining);
				}
			}
		}

		if (currentChunk.length() > 0) {
			subChunks.add(currentChunk.toString().trim());
		}

		return subChunks;
	}

	private List<String> splitByChars(String text) {
		List<String> chunks = new ArrayList<>();
		int start = 0;
		while (start < text.length()) {
			int end = Math.min(start + chunkSize, text.length());
			chunks.add(text.substring(start, end).trim());
			start = end;
		}
		return chunks;
	}

}
