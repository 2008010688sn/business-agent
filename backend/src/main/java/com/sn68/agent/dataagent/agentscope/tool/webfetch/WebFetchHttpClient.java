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
package com.sn68.agent.dataagent.agentscope.tool.webfetch;

import com.sn68.agent.dataagent.linking.AppOriginMatcher;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.netty.channel.ChannelOption;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.netty.http.client.HttpClient;

/**
 * 关闭自动跟随的逐跳 GET。TCP 连到已校验 IP，Host/SNI 仍用原 host。
 */
@Slf4j
@Component
public class WebFetchHttpClient {

	static final String REASON_TIMEOUT = "TIMEOUT";

	static final String REASON_TOO_LARGE = "TOO_LARGE";

	static final String REASON_TOCTOU = "TOCTOU";

	private final WebClient.Builder webClientBuilder;

	private final ExchangeFunction exchangeFunction;

	@Autowired
	public WebFetchHttpClient(WebClient.Builder webClientBuilder) {
		this(webClientBuilder, null);
	}

	WebFetchHttpClient(WebClient.Builder webClientBuilder, ExchangeFunction exchangeFunction) {
		this.webClientBuilder = webClientBuilder;
		this.exchangeFunction = exchangeFunction;
	}

	public Hop get(URI uri, InetAddress connectIp, Duration timeout, long maxBytes) {
		if (connectIp == null) {
			log.warn("web_fetch residual TOCTOU, refuse connect without pinned IP. reasonCode={}, url={}",
					REASON_TOCTOU, AppOriginMatcher.truncateForLog(uri == null ? null : uri.toString()));
			throw CheckedException.forbidden("拒绝抓取：无法钉住已校验 IP");
		}
		int bufferLimit = bufferLimit(maxBytes);
		WebClient client = buildClient(uri, connectIp, timeout, bufferLimit);
		try {
			return client.get()
				.uri(uri)
				.header(HttpHeaders.ACCEPT, "text/html, text/plain, application/json, application/xml, text/xml")
				.exchangeToMono(response -> readHop(response, maxBytes))
				.block(timeout);
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (WebClientRequestException ex) {
			throw timeoutOrFail(ex, uri);
		}
		catch (RuntimeException ex) {
			if (isTimeout(ex)) {
				throw timeoutOrFail(ex, uri);
			}
			if (isTooLarge(ex)) {
				log.warn("web_fetch rejected. reasonCode={}, url={}", REASON_TOO_LARGE,
						AppOriginMatcher.truncateForLog(uri.toString()));
				throw CheckedException.forbidden("拒绝抓取：响应超过体积上限");
			}
			throw CheckedException.fail("网页读取失败：" + ex.getMessage());
		}
	}

	private WebClient buildClient(URI uri, InetAddress connectIp, Duration timeout, int bufferLimit) {
		WebClient.Builder builder = webClientBuilder.clone()
			.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(bufferLimit));
		if (exchangeFunction != null) {
			return builder.exchangeFunction(exchangeFunction).build();
		}
		int connectMs = (int) Math.min(Math.max(timeout.toMillis(), 1L), Integer.MAX_VALUE);
		HttpClient httpClient = HttpClient.create()
			.followRedirect(false)
			.responseTimeout(timeout)
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectMs)
			.resolver(new PinnedAddressResolverGroup(connectIp));
		log.info("web_fetch connecting with IP pin. host={}, pin={}", uri.getHost(), connectIp.getHostAddress());
		return builder.clientConnector(new ReactorClientHttpConnector(httpClient)).build();
	}

	/**
	 * 把 hostname 解析钉死到已校验 IP，Host/SNI/证书校验仍用原 host，避免 remoteAddress(IP) 破坏 SNI。
	 */
	private static final class PinnedAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

		private final InetAddress pinned;

		private PinnedAddressResolverGroup(InetAddress pinned) {
			this.pinned = pinned;
		}

		@Override
		protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
			return new AbstractAddressResolver<>(executor) {
				@Override
				protected boolean doIsResolved(InetSocketAddress address) {
					return !address.isUnresolved();
				}

				@Override
				protected void doResolve(InetSocketAddress unresolvedAddress, Promise<InetSocketAddress> promise) {
					promise.setSuccess(new InetSocketAddress(pinned, unresolvedAddress.getPort()));
				}

				@Override
				protected void doResolveAll(InetSocketAddress unresolvedAddress,
						Promise<List<InetSocketAddress>> promise) {
					promise.setSuccess(List.of(new InetSocketAddress(pinned, unresolvedAddress.getPort())));
				}
			};
		}

	}

	private reactor.core.publisher.Mono<Hop> readHop(ClientResponse response, long maxBytes) {
		int status = response.statusCode().value();
		HttpHeaders headers = response.headers().asHttpHeaders();
		String location = headers.getFirst(HttpHeaders.LOCATION);
		String contentType = headers.getFirst(HttpHeaders.CONTENT_TYPE);
		if (status >= 300 && status < 400) {
			return response.releaseBody().thenReturn(new Hop(status, location, contentType, new byte[0], false));
		}
		return response.bodyToMono(byte[].class)
			.defaultIfEmpty(new byte[0])
			.map(body -> limitBody(body, maxBytes, status, location, contentType));
	}

	private Hop limitBody(byte[] body, long maxBytes, int status, String location, String contentType) {
		byte[] bytes = body == null ? new byte[0] : body;
		if (maxBytes > 0 && bytes.length > maxBytes) {
			return new Hop(status, location, contentType, Arrays.copyOf(bytes, (int) maxBytes), true);
		}
		return new Hop(status, location, contentType, bytes, false);
	}

	private CheckedException timeoutOrFail(RuntimeException ex, URI uri) {
		log.warn("web_fetch rejected. reasonCode={}, url={}", REASON_TIMEOUT,
				AppOriginMatcher.truncateForLog(uri.toString()));
		return CheckedException.fail("网页读取超时");
	}

	private static boolean isTimeout(Throwable ex) {
		Throwable current = ex;
		while (current != null) {
			String name = current.getClass().getSimpleName();
			if (name.contains("Timeout") || name.contains("TimeoutException")) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static boolean isTooLarge(Throwable ex) {
		Throwable current = ex;
		while (current != null) {
			if (current.getClass().getSimpleName().contains("DataBufferLimit")) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static int bufferLimit(long maxBytes) {
		long limit = maxBytes <= 0 ? 1024L : maxBytes + 1L;
		return (int) Math.min(limit, Integer.MAX_VALUE);
	}

	record Hop(int status, String location, String contentType, byte[] body, boolean truncated) {

		boolean redirect() {
			return status >= 300 && status < 400;
		}

	}

}
