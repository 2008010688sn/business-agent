package com.sn68.agent.dataagent.dto.schema;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 批量启停语义模型的请求。
 */
@Schema(description = "语义模型状态修改请求")
public record SemanticModelStatusModifyReq(
		@Schema(description = "语义模型ID列表") List<Long> ids,
		@Schema(description = "是否启用") Boolean enabled
) {
}
