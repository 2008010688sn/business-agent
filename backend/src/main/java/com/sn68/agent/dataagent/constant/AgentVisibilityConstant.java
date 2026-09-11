/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.constant;

import java.util.List;

/**
 * DataAgent 用户对话页可见性常量。
 */
public final class AgentVisibilityConstant {

	public static final String CONVERSATION_SCOPE_GRANT_ONLY = "GRANT_ONLY";

	public static final String CONVERSATION_SCOPE_TENANT = "TENANT";

	public static final String CONVERSATION_SCOPE_TEAM = "TEAM";

	public static final String CONVERSATION_SCOPE_PERMISSION = "PERMISSION";

	public static final String CATALOG_SCOPE_HIDDEN = "HIDDEN";

	public static final String CATALOG_SCOPE_TENANT = "TENANT";

	public static final String CATALOG_SCOPE_TEAM = "TEAM";

	public static final String CATALOG_SCOPE_PERMISSION = "PERMISSION";

	public static final String APPLY_MODE_DISABLED = "DISABLED";

	public static final String APPLY_MODE_AUTO_APPROVE = "AUTO_APPROVE";

	public static final String APPLY_MODE_APPROVAL_REQUIRED = "APPROVAL_REQUIRED";

	public static final String APPROVAL_MODE_LOCAL = "LOCAL";

	public static final String APPROVAL_MODE_WORKFLOW = "WORKFLOW";

	public static final String DEFAULT_WORKFLOW_FLOW_CODE = "AGENT_VISIBILITY_APPLY";

	public static final String POLICY_STATUS_ENABLED = "ENABLED";

	public static final String POLICY_STATUS_DISABLED = "DISABLED";

	public static final String SUBJECT_TYPE_USER = "USER";

	public static final String SUBJECT_TYPE_TEAM = "TEAM";

	public static final String SUBJECT_TYPE_PERMISSION = "PERMISSION";

	public static final String SUBJECT_TYPE_TENANT = "TENANT";

	public static final String GRANT_SOURCE_MANUAL = "MANUAL";

	public static final String GRANT_SOURCE_APPLICATION = "APPLICATION";

	public static final String GRANT_SOURCE_SYSTEM_INIT = "SYSTEM_INIT";

	public static final String GRANT_STATUS_ACTIVE = "ACTIVE";

	public static final String GRANT_STATUS_REVOKED = "REVOKED";

	public static final String APPLICATION_STATUS_PENDING = "PENDING";

	public static final String APPLICATION_STATUS_APPROVED = "APPROVED";

	public static final String APPLICATION_STATUS_REJECTED = "REJECTED";

	public static final String APPLICATION_STATUS_CANCELED = "CANCELED";

	public static final String CATALOG_STATUS_VISIBLE = "VISIBLE";

	public static final String CATALOG_STATUS_APPLYABLE = "APPLYABLE";

	public static final String CATALOG_STATUS_PENDING = "PENDING";

	public static final String CATALOG_STATUS_NOT_APPLYABLE = "NOT_APPLYABLE";

	public static final String BUSINESS_CODE_PREFIX = "DATA_AGENT_VISIBILITY_APPLY:";

	public static final String PERMISSION_APPLICATION_VIEW = "agent:visibility-application:view";

	public static final String PERMISSION_APPLICATION_AUDIT = "agent:visibility-application:audit";

	public static final List<String> CONVERSATION_SCOPES = List.of(CONVERSATION_SCOPE_GRANT_ONLY,
			CONVERSATION_SCOPE_TENANT, CONVERSATION_SCOPE_TEAM, CONVERSATION_SCOPE_PERMISSION);

	public static final List<String> CATALOG_SCOPES = List.of(CATALOG_SCOPE_HIDDEN, CATALOG_SCOPE_TENANT,
			CATALOG_SCOPE_TEAM, CATALOG_SCOPE_PERMISSION);

	public static final List<String> APPLY_MODES = List.of(APPLY_MODE_DISABLED, APPLY_MODE_AUTO_APPROVE,
			APPLY_MODE_APPROVAL_REQUIRED);

	public static final List<String> APPROVAL_MODES = List.of(APPROVAL_MODE_LOCAL, APPROVAL_MODE_WORKFLOW);

	public static final List<String> SUBJECT_TYPES = List.of(SUBJECT_TYPE_USER, SUBJECT_TYPE_TEAM,
			SUBJECT_TYPE_PERMISSION, SUBJECT_TYPE_TENANT);

	public static final List<String> APPLICATION_TERMINAL_STATUSES = List.of(APPLICATION_STATUS_APPROVED,
			APPLICATION_STATUS_REJECTED, APPLICATION_STATUS_CANCELED);

	private AgentVisibilityConstant() {

	}

}
