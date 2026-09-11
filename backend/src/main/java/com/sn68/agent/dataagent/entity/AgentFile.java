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
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Local disk file metadata.
 *
 * @author sn68
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_file")
@Schema(description = "本地文件")
public class AgentFile extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "原始文件名")
	private String originalName;

	@Schema(description = "内容类型")
	private String contentType;

	@Schema(description = "字节大小")
	private Long size;

	@Schema(description = "本地存储路径")
	private String storagePath;

}
