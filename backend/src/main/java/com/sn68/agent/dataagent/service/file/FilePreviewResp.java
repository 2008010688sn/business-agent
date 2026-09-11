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
package com.sn68.agent.dataagent.service.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Local file preview payload (replaces Suite FilePreviewResp).
 *
 * @author sn68
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilePreviewResp {

	@Schema(description = "文件路径（绝对 http URL）")
	private String path;

	@Schema(description = "原始文件名称")
	private String originalName;

	@Schema(description = "预览地址")
	private String previewUrl;

}
