/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import java.text.Normalizer;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class RouteTextNormalizer {

	public String normalize(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
		StringBuilder result = new StringBuilder(normalized.length());
		for (int offset = 0; offset < normalized.length();) {
			int codePoint = normalized.codePointAt(offset);
			int charCount = Character.charCount(codePoint);
			if (Character.isLetterOrDigit(codePoint) || codePoint == '-' || codePoint == '_'
					|| isSemanticSeparator(normalized, offset, codePoint, charCount)) {
				result.appendCodePoint(codePoint);
			}
			else {
				result.append(' ');
			}
			offset += charCount;
		}
		return result.toString().replaceAll("\\s+", " ").trim();
	}

	private boolean isSemanticSeparator(String value, int offset, int codePoint, int charCount) {
		if (codePoint != '.' && codePoint != ':' && codePoint != '/') {
			return false;
		}
		int previous = offset == 0 ? -1 : value.codePointBefore(offset);
		int nextOffset = offset + charCount;
		int next = nextOffset >= value.length() ? -1 : value.codePointAt(nextOffset);
		return previous >= 0 && next >= 0 && Character.isDigit(previous) && Character.isDigit(next);
	}

}
