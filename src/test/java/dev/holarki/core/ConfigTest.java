/* Holarki © 2025 — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class ConfigTest {

  @Test
  void stripJson5_preservesQuotedCommasWhileRemovingTrailing() throws Exception {
    String raw =
        "{\n"
            + "  \"array\": [\n"
            + "    \",]\"\n"
            + "  ,\n"
            + "  ],\n"
            + "  \"object\": {\n"
            + "    \"value\": \",}\"\n"
            + "  },\n"
            + "}\n";

    String cleaned = invokeStripJson5(raw);

    JsonObject parsed = JsonParser.parseString(cleaned).getAsJsonObject();
    JsonArray array = parsed.getAsJsonArray("array");
    assertEquals(",]", array.get(0).getAsString(), "quoted comma should remain untouched");
    JsonObject inner = parsed.getAsJsonObject("object");
    assertEquals(",}", inner.get("value").getAsString(), "quoted comma should remain untouched");
  }

  private static String invokeStripJson5(String raw)
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    Method method = Config.class.getDeclaredMethod("stripJson5", String.class);
    method.setAccessible(true);
    return (String) method.invoke(null, raw);
  }
}
