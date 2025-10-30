/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;

class LoggingConfiguratorTest {
  @Test
  void appliesConfiguredLogLevel() {
    LoggerContext context = new LoggerContext();
    context.start();
    Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
    root.setLevel(Level.INFO);

    LogbackConfigurator.configure(context, new Config.Log(false, 250L, "DEBUG"));
    assertEquals(Level.DEBUG, root.getLevel());

    LogbackConfigurator.configure(context, new Config.Log(false, 250L, "ERROR"));
    assertEquals(Level.ERROR, root.getLevel());

    context.stop();
  }
}
