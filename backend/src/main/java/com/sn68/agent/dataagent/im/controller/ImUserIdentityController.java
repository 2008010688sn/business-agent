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
package com.sn68.agent.dataagent.im.controller;

import com.sn68.agent.dataagent.controller.DataAgentController;
import com.sn68.agent.dataagent.im.dto.ImUserBindSessionCreateRequest;
import com.sn68.agent.dataagent.im.dto.ImUserBindSessionDTO;
import com.sn68.agent.dataagent.im.dto.ImUserIdentityDTO;
import com.sn68.agent.dataagent.im.service.ImUserBindSessionService;
import com.sn68.agent.dataagent.im.service.ImUserIdentityService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM 用户绑定管理接口。
 */
@RestController
@RequestMapping("/im-user-identities")
@RequiredArgsConstructor
@Tag(name = "IM 用户身份", description = "维护 IM 用户身份映射")
public class ImUserIdentityController {

	private final ImUserIdentityService identityService;

	private final ImUserBindSessionService bindSessionService;

	@Operation(summary = "查询IM 用户身份清单", description = "查询IM 用户身份清单，用于IM 用户身份相关管理和运行场景。")
	@GetMapping
	public List<ImUserIdentityDTO> list() {
		return identityService.list();
	}

	@Operation(summary = "创建IM 用户身份", description = "创建IM 用户身份，用于IM 用户身份相关管理和运行场景。")
	@AccessLog(module = "IM 用户身份", description = "创建IM 用户身份")
	@PostMapping("/create")
	public ImUserIdentityDTO create(@RequestBody ImUserIdentityDTO request) {
		return identityService.create(request);
	}

	@Operation(summary = "修改IM 用户身份", description = "修改IM 用户身份，用于IM 用户身份相关管理和运行场景。")
	@AccessLog(module = "IM 用户身份", description = "修改IM 用户身份")
	@PutMapping("/{id}/modify")
	public ImUserIdentityDTO update(@PathVariable Long id, @RequestBody ImUserIdentityDTO request) {
		return identityService.update(id, request);
	}

	@Operation(summary = "删除IM 用户身份", description = "删除IM 用户身份，用于IM 用户身份相关管理和运行场景。")
	@AccessLog(module = "IM 用户身份", description = "删除IM 用户身份")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		identityService.delete(id);
	}

	@Operation(summary = "创建IM 用户扫码绑定会话", description = "为当前登录用户生成一次性绑定短码，用于在钉钉机器人会话中完成身份绑定。")
	@AccessLog(module = "IM 用户身份", description = "创建扫码绑定会话")
	@PostMapping("/bind-sessions/create")
	public ImUserBindSessionDTO createBindSession(@RequestBody(required = false) ImUserBindSessionCreateRequest request) {
		return bindSessionService.create(request);
	}

	@Operation(summary = "查询IM 用户扫码绑定会话状态", description = "仅创建者可查询，用于管理端轮询绑定结果。")
	@GetMapping("/bind-sessions/{id}/status")
	public ImUserBindSessionDTO bindSessionStatus(@PathVariable Long id) {
		return bindSessionService.status(id);
	}

}
