/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository.typehandler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

/**
 * PostgreSQL JSONB 与 Map 的最小类型映射。
 */
public class JsonbMapTypeHandler extends BaseTypeHandler<Map<String, Object>> {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	@Override
	public void setNonNullParameter(PreparedStatement ps, int index, Map<String, Object> parameter, JdbcType jdbcType)
			throws SQLException {
		PGobject object = new PGobject();
		object.setType("jsonb");
		try {
			object.setValue(OBJECT_MAPPER.writeValueAsString(parameter));
		}
		catch (Exception ex) {
			throw new SQLException("temporal_policy JSON serialization failed", ex);
		}
		ps.setObject(index, object);
	}

	@Override
	public Map<String, Object> getNullableResult(ResultSet rs, String columnName) throws SQLException {
		return parse(rs.getObject(columnName));
	}

	@Override
	public Map<String, Object> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
		return parse(rs.getObject(columnIndex));
	}

	@Override
	public Map<String, Object> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
		return parse(cs.getObject(columnIndex));
	}

	private Map<String, Object> parse(Object value) throws SQLException {
		if (value == null) {
			return null;
		}
		try {
			return OBJECT_MAPPER.readValue(value.toString(), MAP_TYPE);
		}
		catch (Exception ex) {
			throw new SQLException("temporal_policy JSON deserialization failed", ex);
		}
	}

}
