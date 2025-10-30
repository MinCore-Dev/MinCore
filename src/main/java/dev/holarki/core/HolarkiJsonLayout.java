/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.LayoutBase;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Minimal JSON layout for console logging. */
final class HolarkiJsonLayout extends LayoutBase<ILoggingEvent> {
  private static final DateTimeFormatter ISO_INSTANT =
      DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

  @Override
  public String doLayout(ILoggingEvent event) {
    StringBuilder json = new StringBuilder(256);
    json.append('{');
    appendField(json, "ts", ISO_INSTANT.format(Instant.ofEpochMilli(event.getTimeStamp())));
    json.append(',');
    appendField(json, "level", event.getLevel().toString());
    json.append(',');
    appendField(json, "logger", event.getLoggerName());
    json.append(',');
    appendField(json, "thread", event.getThreadName());
    json.append(',');
    appendField(json, "message", event.getFormattedMessage());

    Map<String, String> mdc = event.getMDCPropertyMap();
    if (mdc != null && !mdc.isEmpty()) {
      json.append(',');
      json.append("\"mdc\":{");
      boolean first = true;
      for (Map.Entry<String, String> entry : mdc.entrySet()) {
        if (!first) {
          json.append(',');
        }
        appendField(json, entry.getKey(), entry.getValue());
        first = false;
      }
      json.append('}');
    }

    IThrowableProxy throwable = event.getThrowableProxy();
    if (throwable != null) {
      json.append(',');
      appendField(json, "stack", ThrowableProxyUtil.asString(throwable));
    }
    json.append('}');
    json.append(System.lineSeparator());
    return json.toString();
  }

  private static void appendField(StringBuilder json, String key, String value) {
    json.append('"').append(escape(key)).append('"').append(':');
    if (value == null) {
      json.append("null");
    } else {
      json.append('"').append(escape(value)).append('"');
    }
  }

  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder escaped = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      switch (ch) {
        case '\\':
          escaped.append("\\\\");
          break;
        case '"':
          escaped.append("\\\"");
          break;
        case '\n':
          escaped.append("\\n");
          break;
        case '\r':
          escaped.append("\\r");
          break;
        case '\t':
          escaped.append("\\t");
          break;
        default:
          if (ch < 0x20) {
            escaped.append(String.format("\\u%04x", (int) ch));
          } else {
            escaped.append(ch);
          }
          break;
      }
    }
    return escaped.toString();
  }
}
