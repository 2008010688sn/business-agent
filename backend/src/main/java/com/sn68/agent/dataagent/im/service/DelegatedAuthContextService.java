package com.sn68.agent.dataagent.im.service;

import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.context.DataAgentOutboundContext;
import com.sn68.agent.dataagent.iam.dto.DelegatedAuthContextReq;
import com.sn68.agent.dataagent.iam.dto.DelegatedAuthContextResp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * IM 委托授权上下文服务。standalone 无 IAM，签发返回本地失败提示。
 */
@Slf4j
@Service
public class DelegatedAuthContextService {

	private static final String TOKEN_TYPE_BEARER = "Bearer";

	private final DataAgentAsyncContextBridge asyncContextBridge;

	public DelegatedAuthContextService(DataAgentAsyncContextBridge asyncContextBridge) {
		this.asyncContextBridge = asyncContextBridge;
	}

	/**
	 * standalone 不签发 IAM 委托上下文。
	 */
	public IssueResult issue(DelegatedAuthContextReq req) {
		if (req == null) {
			return IssueResult.failed("request-empty");
		}
		log.warn(
				"Standalone mode cannot issue IAM delegated auth. provider={}, connectorCode={}, runtimeRequestId={}",
				req.getProvider(), req.getConnectorCode(), req.getRuntimeRequestId());
		return IssueResult.failed("standalone-no-iam");
	}

	public <T> T executeWith(DelegatedAuthContextResp context, Supplier<T> supplier) {
		DataAgentOutboundContext.Snapshot previous = DataAgentOutboundContext.get();
		DataAgentOutboundContext.Snapshot delegatedHeaders = mergeDelegatedHeaders(previous, context);
		DataAgentAsyncContextBridge.Snapshot delegated = asyncContextBridge.snapshotForDelegatedToken(
				context == null ? null : context.getAccessToken(), delegatedHeaders);
		return asyncContextBridge.supplyWith(delegated, supplier);
	}

	private DataAgentOutboundContext.Snapshot mergeDelegatedHeaders(DataAgentOutboundContext.Snapshot previous,
			DelegatedAuthContextResp context) {
		Map<String, String> headers = new LinkedHashMap<>(previous == null ? Map.of() : previous.headers());
		String token = bearerToken(context);
		if (StringUtils.hasText(token)) {
			headers.entrySet()
				.removeIf(entry -> "V4-Authorization".equalsIgnoreCase(entry.getKey())
						|| "Authorization".equalsIgnoreCase(entry.getKey()));
			headers.put("V4-Authorization", token);
		}
		return new DataAgentOutboundContext.Snapshot(headers, true);
	}

	private String bearerToken(DelegatedAuthContextResp context) {
		if (context == null || !StringUtils.hasText(context.getAccessToken())) {
			return null;
		}
		String tokenType = StringUtils.hasText(context.getTokenType()) ? context.getTokenType() : TOKEN_TYPE_BEARER;
		return tokenType + " " + context.getAccessToken();
	}

	public record IssueResult(Status status, DelegatedAuthContextResp context, String reason) {

		public enum Status {
			OK, TIMEOUT, FAILED, INCOMPLETE
		}

		public static IssueResult ok(DelegatedAuthContextResp context) {
			return new IssueResult(Status.OK, context, null);
		}

		public static IssueResult timeout() {
			return new IssueResult(Status.TIMEOUT, null, "timeout");
		}

		public static IssueResult failed(String reason) {
			return new IssueResult(Status.FAILED, null, reason);
		}

		public static IssueResult incomplete() {
			return new IssueResult(Status.INCOMPLETE, null, "incomplete-snapshot");
		}

		public boolean succeeded() {
			return status == Status.OK && context != null;
		}
	}

}
