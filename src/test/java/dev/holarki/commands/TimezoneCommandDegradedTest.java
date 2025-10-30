/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.holarki.api.AttributeWriteException;
import dev.holarki.api.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;
import org.junit.jupiter.api.Test;

final class TimezoneCommandDegradedTest {

  @Test
  void degradedModeSendsDegradedMessage() {
    List<Text> messages = new ArrayList<>();

    TimezoneCommand.sendWriteFailure(
        new AttributeWriteException(ErrorCode.DEGRADED_MODE, "degraded"), messages::add);

    assertEquals(1, messages.size());
    assertTranslatableKey(messages.get(0), "holarki.err.db.degraded");
  }

  @Test
  void otherErrorsMapToUnavailableMessage() {
    List<Text> messages = new ArrayList<>();

    TimezoneCommand.sendWriteFailure(
        new AttributeWriteException(ErrorCode.CONNECTION_LOST, "lost"), messages::add);

    assertEquals(1, messages.size());
    assertTranslatableKey(messages.get(0), "holarki.err.db.unavailable");
  }

  private static void assertTranslatableKey(Text text, String expectedKey) {
    if (!(text instanceof MutableText mutable)) {
      throw new AssertionError("Expected MutableText but found " + text.getClass().getName());
    }
    TextContent content = mutable.getContent();
    if (!(content instanceof TranslatableTextContent translatable)) {
      throw new AssertionError("Expected TranslatableTextContent but found " + content.getClass());
    }
    if (!expectedKey.equals(translatable.getKey())) {
      throw new AssertionError(
          "Expected key " + expectedKey + " but found " + translatable.getKey());
    }
  }
}
