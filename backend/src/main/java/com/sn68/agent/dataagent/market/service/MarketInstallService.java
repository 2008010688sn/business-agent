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
package com.sn68.agent.dataagent.market.service;

import com.sn68.agent.dataagent.market.dto.MarketInstallPrepareResp;

/**
 * 能力市场安装服务（PR-5 起安装目标为数字员工 Capability 绑定）。
 *
 * <p>PR-1 过渡期安装入口失败关闭；PR-5 重建链路：校验条目审核通过（APPROVED）、
 * 未撤销且当前版本可用后，幂等写入 digital_employee_capability（草稿态，
 * Seal 时冻结进员工 Release 快照）。</p>
 */
public interface MarketInstallService {

	/**
	 * 将市场条目当前通过版本安装为指定数字员工的能力绑定（幂等：重复安装恢复启用既有绑定）。
	 * @param listingId 市场条目ID
	 * @param expectedVersionNo 调用方选择的市场条目版本号，必须与当前通过版本一致
	 * @param digitalEmployeeId 数字员工ID（须属于当前租户且未封存）
	 * @return 安装结果（含绑定记录ID与来源版本追溯信息）
	 */
	MarketInstallPrepareResp install(Long listingId, Integer expectedVersionNo, Long digitalEmployeeId);

	/**
	 * 兼容旧的内部调用：沿用市场条目的当前版本号后委托到带版本校验的安装入口。
	 * 新调用方应始终传入 expectedVersionNo，避免客户端使用过期版本。
	 */
	MarketInstallPrepareResp install(Long listingId, Long digitalEmployeeId);

}
