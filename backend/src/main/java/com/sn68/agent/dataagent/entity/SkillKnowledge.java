/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.dataagent.enums.EmbeddingStatus;
import com.sn68.agent.dataagent.enums.KnowledgeType;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** Knowledge-base resource owned by a QA Skill. */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("skill_knowledge")
@Schema(description = "Skill 知识库资源")
public class SkillKnowledge extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "所属Skill ID")
	private Long skillId;

	@Schema(description = "被哪条新版本取代（空表示当前有效）")
	private Long supersededById;

	@Schema(description = "标题")
	private String title;

	@Schema(description = "知识类型（DOCUMENT/QA/FAQ）")
	private KnowledgeType type;

	@Schema(description = "问题（QA/FAQ类型）")
	private String question;

	@Schema(description = "知识内容")
	private String content;

	@Schema(description = "是否参与召回")
	private Boolean isRecall;

	@Schema(description = "向量化状态")
	private EmbeddingStatus embeddingStatus;

	@Schema(description = "向量化失败原因")
	private String errorMsg;

	@Schema(description = "源文件名")
	private String sourceFilename;

	@Schema(description = "文件存储路径")
	private String filePath;

	@Schema(description = "文件大小（字节）")
	private Long fileSize;

	@Schema(description = "文件类型")
	private String fileType;

	@Schema(description = "切分器类型")
	private String splitterType;

	@Schema(description = "向量/文件资源是否已清理")
	private Boolean isResourceCleaned;

}
