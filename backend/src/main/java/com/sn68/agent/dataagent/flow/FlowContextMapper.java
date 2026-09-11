/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * JSON Pointer reader/writer for mutable FLOW context maps.
 */
@Component
public class FlowContextMapper {

	public Object get(Map<String, Object> context, String pointer) {
		if (context == null || !StringUtils.hasText(pointer) || "/".equals(pointer)) {
			return context;
		}
		Object current = context;
		for (String segment : segments(pointer)) {
			if (current instanceof Map<?, ?> map) {
				current = map.get(segment);
			}
			else if (current instanceof List<?> list) {
				int index = index(segment, list.size());
				current = index < 0 ? null : list.get(index);
			}
			else {
				return null;
			}
		}
		return current;
	}

	public void set(Map<String, Object> context, String pointer, Object value) {
		if (context == null || !StringUtils.hasText(pointer) || !pointer.startsWith("/")) {
			throw new IllegalArgumentException("FLOW context paths must use JSON Pointer");
		}
		List<String> segments = segments(pointer);
		if (segments.isEmpty()) {
			throw new IllegalArgumentException("Cannot replace the FLOW context root");
		}
		setValue(context, segments, 0, value);
	}

	public void mergeMissing(Map<String, Object> target, String targetPointer, Object source) {
		Object current = get(target, targetPointer);
		if (current instanceof Map<?, ?> targetMap && source instanceof Map<?, ?> sourceMap) {
			mergeMaps(castMap(targetMap), castMap(sourceMap));
			return;
		}
		if (isEmpty(current)) {
			set(target, targetPointer, source);
		}
	}

	public void mergePresent(Map<String, Object> target, String targetPointer, Object source) {
		Object current = get(target, targetPointer);
		if (current instanceof Map<?, ?> targetMap && source instanceof Map<?, ?> sourceMap) {
			mergeMapsPresent(castMap(targetMap), castMap(sourceMap));
			return;
		}
		if (source != null) {
			set(target, targetPointer, source);
		}
	}

	public boolean isEmpty(Object value) {
		return value == null || (value instanceof String text && !StringUtils.hasText(text))
				|| (value instanceof Map<?, ?> map && map.isEmpty())
				|| (value instanceof Iterable<?> iterable && !iterable.iterator().hasNext());
	}

	private void mergeMaps(Map<String, Object> target, Map<String, Object> source) {
		for (Map.Entry<String, Object> entry : source.entrySet()) {
			Object current = target.get(entry.getKey());
			if (current instanceof Map<?, ?> currentMap && entry.getValue() instanceof Map<?, ?> sourceMap) {
				mergeMaps(castMap(currentMap), castMap(sourceMap));
			}
			else if (isEmpty(current)) {
				target.put(entry.getKey(), entry.getValue());
			}
		}
	}

	private void mergeMapsPresent(Map<String, Object> target, Map<String, Object> source) {
		for (Map.Entry<String, Object> entry : source.entrySet()) {
			if (entry.getValue() == null) {
				continue;
			}
			Object current = target.get(entry.getKey());
			if (current instanceof Map<?, ?> currentMap && entry.getValue() instanceof Map<?, ?> sourceMap) {
				mergeMapsPresent(castMap(currentMap), castMap(sourceMap));
			}
			else {
				target.put(entry.getKey(), entry.getValue());
			}
		}
	}

	private List<String> segments(String pointer) {
		if (!pointer.startsWith("/")) {
			throw new IllegalArgumentException("FLOW context paths must use JSON Pointer: " + pointer);
		}
		List<String> result = new ArrayList<>();
		for (String segment : pointer.substring(1).split("/", -1)) {
			result.add(segment.replace("~1", "/").replace("~0", "~"));
		}
		return result;
	}

	private int index(String value, int size) {
		try {
			int index = Integer.parseInt(value);
			return index >= 0 && index < size ? index : -1;
		}
		catch (NumberFormatException ex) {
			return -1;
		}
	}

	private void setValue(Object container, List<String> path, int offset, Object value) {
		String segment = path.get(offset);
		boolean last = offset == path.size() - 1;
		if (container instanceof Map<?, ?> map) {
			Map<String, Object> current = castMap(map);
			if (last) {
				current.put(segment, value);
				return;
			}
			Object child = current.get(segment);
			if (!isContainer(child)) {
				child = containerFor(path.get(offset + 1));
				current.put(segment, child);
			}
			setValue(child, path, offset + 1, value);
			return;
		}
		if (container instanceof List<?> list) {
			List<Object> current = castList(list);
			int position = requiredIndex(segment);
			while (current.size() <= position) {
				current.add(null);
			}
			if (last) {
				current.set(position, value);
				return;
			}
			Object child = current.get(position);
			if (!isContainer(child)) {
				child = containerFor(path.get(offset + 1));
				current.set(position, child);
			}
			setValue(child, path, offset + 1, value);
			return;
		}
		throw new IllegalArgumentException("FLOW context path traverses a scalar value");
	}

	private Object containerFor(String nextSegment) {
		return isIndex(nextSegment) ? new ArrayList<>() : new LinkedHashMap<String, Object>();
	}

	private boolean isContainer(Object value) {
		return value instanceof Map<?, ?> || value instanceof List<?>;
	}

	private boolean isIndex(String value) {
		try {
			return Integer.parseInt(value) >= 0;
		}
		catch (NumberFormatException ex) {
			return false;
		}
	}

	private int requiredIndex(String value) {
		if (!isIndex(value)) {
			throw new IllegalArgumentException("FLOW context list path must use a non-negative index: " + value);
		}
		return Integer.parseInt(value);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> castMap(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private List<Object> castList(Object value) {
		return (List<Object>) value;
	}

}
