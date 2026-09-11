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
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

class FlowExtractionExceptionTest {

	@Test
	void classifiesRecoverableProviderFailuresWithoutReadingResponseBodies() {
		assertEquals(FlowExtractionException.TIMEOUT,
				FlowExtractionException.modelCall(new SocketTimeoutException("read timed out")).errorCode());
		assertEquals(FlowExtractionException.INPUT_TOO_LARGE,
				FlowExtractionException.modelCall(new HttpClientErrorException(HttpStatus.PAYLOAD_TOO_LARGE)).errorCode());
		assertEquals(FlowExtractionException.AUTHENTICATION_ERROR,
				FlowExtractionException.modelCall(new HttpClientErrorException(HttpStatus.UNAUTHORIZED)).errorCode());
		assertEquals(FlowExtractionException.RATE_LIMITED,
				FlowExtractionException.modelCall(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS)).errorCode());
		assertEquals(FlowExtractionException.UPSTREAM_ERROR,
				FlowExtractionException.modelCall(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)).errorCode());
	}

}
