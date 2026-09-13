package com.changlu.agentforge.llm.chat.request;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class DefaultChatRequestParametersTest {

    @Test
    public void shouldMergeOverridesOverDefaults() {
        DefaultChatRequestParameters defaults = DefaultChatRequestParameters.builder()
                .modelName("default-model")
                .temperature(0.2)
                .maxTokens(100)
                .topP(0.9)
                .stopSequences(Arrays.asList("END"))
                .customParameter("defaultOnly", "yes")
                .customParameter("shared", "default")
                .build();

        DefaultChatRequestParameters overrides = DefaultChatRequestParameters.builder()
                .modelName("request-model")
                .temperature(0.8)
                .customParameter("requestOnly", "yes")
                .customParameter("shared", "request")
                .build();

        DefaultChatRequestParameters merged = DefaultChatRequestParameters.merge(defaults, overrides);

        assertEquals("request-model", merged.modelName());
        assertEquals(Double.valueOf(0.8), merged.temperature());
        assertEquals(Integer.valueOf(100), merged.maxTokens());
        assertEquals(Double.valueOf(0.9), merged.topP());
        assertEquals(Arrays.asList("END"), merged.stopSequences());
        assertEquals("yes", merged.customParameters().get("defaultOnly"));
        assertEquals("yes", merged.customParameters().get("requestOnly"));
        assertEquals("request", merged.customParameters().get("shared"));
    }

    @Test
    public void shouldDefensivelyCopyCollections() {
        java.util.List<String> stops = new java.util.ArrayList<String>();
        stops.add("STOP");
        Map<String, Object> custom = new LinkedHashMap<String, Object>();
        custom.put("seed", 7);

        DefaultChatRequestParameters parameters = DefaultChatRequestParameters.builder()
                .stopSequences(stops)
                .customParameters(custom)
                .build();

        stops.add("LATER");
        custom.put("seed", 8);

        assertEquals(Arrays.asList("STOP"), parameters.stopSequences());
        assertEquals(7, parameters.customParameters().get("seed"));
        assertThrows(UnsupportedOperationException.class,
                () -> parameters.stopSequences().add("NO"));
        assertThrows(UnsupportedOperationException.class,
                () -> parameters.customParameters().put("x", "y"));
    }
}
