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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.cloud.ai.transformer.splitter.RecursiveCharacterTextSplitter;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecursiveOverlapTextSplitterTest {

	private static final String[] SEPARATORS = { "\n\n" };

	private static final String TEXT = "第一段讲的是保温箱的适用温区。\n\n第二段讲的是保温时长与蓄冷剂用量。\n\n第三段讲的是回收流程。";

	@Test
	void eachChunkCarriesTheTailOfThePreviousOne() {
		List<String> chunks = new RecursiveOverlapTextSplitter(20, 6, SEPARATORS).splitText(TEXT);
		List<String> plain = new RecursiveCharacterTextSplitter(20, SEPARATORS).splitText(TEXT);

		assertEquals(plain.size(), chunks.size());
		for (int index = 1; index < chunks.size(); index++) {
			String previous = plain.get(index - 1);
			assertTrue(chunks.get(index).startsWith(previous.substring(previous.length() - 6)),
					"分块 " + index + " 应带上一块的尾部：" + chunks.get(index));
			assertTrue(chunks.get(index).endsWith(plain.get(index)), "重叠只加在开头，正文不能改：" + chunks.get(index));
		}
	}

	/** 重叠是新增行为，配 0 必须能原样退回上游切法。 */
	@Test
	void zeroOverlapKeepsTheUpstreamResultUnchanged() {
		assertEquals(new RecursiveCharacterTextSplitter(20, SEPARATORS).splitText(TEXT),
				new RecursiveOverlapTextSplitter(20, 0, SEPARATORS).splitText(TEXT));
	}

	/**
	 * 上游按分隔符硬切且不合并小块，逐行文本会切出大量碎块。重叠不能超过被补的块自身长度，否则重叠内容
	 * 反客为主，向量库里挤满近似重复的向量。
	 */
	@Test
	void overlapNeverOutgrowsTheChunkItIsAttachedTo() {
		String text = "这是一段足够长的正文用来当上一块。\n\n短句。\n\n又一段足够长的正文用来收尾。";

		List<String> chunks = new RecursiveOverlapTextSplitter(20, 200, SEPARATORS).splitText(text);
		List<String> plain = new RecursiveCharacterTextSplitter(20, SEPARATORS).splitText(text);

		for (int index = 1; index < chunks.size(); index++) {
			assertTrue(chunks.get(index).length() <= plain.get(index).length() * 2,
					"分块 " + index + " 的重叠超过了正文本身：" + chunks.get(index));
		}
	}

}
