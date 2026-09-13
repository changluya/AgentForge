package com.changlu.agentforge.llm.internal.json;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JsonTest {

    @Test
    public void shouldRoundTripObjectArrayAndEscapedText() {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("text", "hello\n\"AgentForge\"");
        source.put("enabled", true);
        source.put("count", 3);
        source.put("items", Arrays.asList("a", "b"));
        source.put("nothing", null);

        String json = Json.stringify(source);
        Map<String, Object> parsed = Json.parseObject(json);

        assertEquals("hello\n\"AgentForge\"", parsed.get("text"));
        assertEquals(Boolean.TRUE, parsed.get("enabled"));
        assertEquals(3L, ((Number) parsed.get("count")).longValue());
        assertEquals(Arrays.asList("a", "b"), parsed.get("items"));
        assertTrue(parsed.containsKey("nothing"));
        assertNull(parsed.get("nothing"));
    }

    @Test
    public void shouldParseNumbersAndLiterals() {
        List<Object> values = Json.array(Json.parse("[-1,1.25,true,false,null,1e3]"));

        assertEquals(-1L, ((Number) values.get(0)).longValue());
        assertEquals(1.25d, ((Number) values.get(1)).doubleValue(), 0.00001d);
        assertEquals(Boolean.TRUE, values.get(2));
        assertEquals(Boolean.FALSE, values.get(3));
        assertNull(values.get(4));
        assertEquals(1000d, ((Number) values.get(5)).doubleValue(), 0.00001d);
    }

    @Test
    public void shouldRejectMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("true trailing"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[]"));
    }
}
