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

import com.alibaba.cloud.ai.transformer.splitter.RecursiveCharacterTextSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 给递归字符分块器补上重叠（overlap）。
 *
 * <p>上游 {@link RecursiveCharacterTextSplitter} 只有 chunkSize 和 separators，构造器 Javadoc 里写的
 * overlap 参数并不存在，所以相邻块之间零重叠；而它又是本模块知识库导入的默认分块器。零重叠的后果是
 * 答案跨越切块边界时两个块都不含完整语义单元，两边都召不回来。上游类不在本仓库，只能在子类里补。
 *
 * <p>这里只把上一块的尾部拼到下一块开头，不改上游的切分算法。重叠长度额外受「不超过被补的块自身长度」
 * 约束：上游按分隔符硬切且不合并小块，逐行文本会切出大量碎块，不设这条约束时重叠内容会反客为主，
 * 向量库里挤满近似重复的向量。有了这条约束，文本总量最多翻倍。
 *
 * <p>把 chunkOverlap 配成 0 即可退回上游原始行为。
 */
public class RecursiveOverlapTextSplitter extends RecursiveCharacterTextSplitter {

	private final int chunkOverlap;

	public RecursiveOverlapTextSplitter(int chunkSize, int chunkOverlap, String[] separators) {
		super(chunkSize, separators);
		this.chunkOverlap = Math.max(0, chunkOverlap);
	}

	@Override
	public List<String> splitText(String text) {
		List<String> chunks = super.splitText(text);
		if (chunkOverlap == 0 || chunks.size() <= 1) {
			return chunks;
		}
		List<String> overlapped = new ArrayList<>(chunks.size());
		overlapped.add(chunks.get(0));
		for (int index = 1; index < chunks.size(); index++) {
			String chunk = chunks.get(index);
			// 重叠取自原始上一块而不是已经加过重叠的那一块，否则重叠会逐块累积
			overlapped.add(tailOf(chunks.get(index - 1), chunk.length()) + chunk);
		}
		return overlapped;
	}

	private String tailOf(String previous, int currentLength) {
		int size = Math.min(Math.min(chunkOverlap, currentLength), previous.length());
		return size <= 0 ? "" : previous.substring(previous.length() - size);
	}

}
