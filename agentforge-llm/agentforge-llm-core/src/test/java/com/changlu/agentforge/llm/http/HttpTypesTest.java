package com.changlu.agentforge.llm.http;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HttpTypesTest {

    @Test
    public void shouldBuildImmutableHttpRequestWithDefaults() {
        HttpRequest request = HttpRequest.builder()
                .url("https://example.test")
                .header("X-Test", "value")
                .body("{}")
                .build();

        assertEquals("https://example.test", request.url());
        assertEquals("POST", request.method());
        assertEquals("value", request.headers().get("X-Test"));
        assertEquals("{}", request.body());
        assertEquals(10000, request.connectTimeoutMillis());
        assertEquals(60000, request.readTimeoutMillis());
        assertThrows(UnsupportedOperationException.class,
                () -> request.headers().put("X-New", "no"));
    }

    @Test
    public void shouldClassifyHttpResponseStatus() {
        assertTrue(new HttpResponse(200, "ok").isSuccessful());
        assertTrue(new HttpResponse(299, "ok").isSuccessful());
        assertFalse(new HttpResponse(300, "redirect").isSuccessful());
        assertFalse(new HttpResponse(500, "error").isSuccessful());
        assertEquals("", new HttpResponse(204, null).body());
    }
}
