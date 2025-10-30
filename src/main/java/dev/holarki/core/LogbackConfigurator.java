/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/** Configures Holarki logging for Logback backends. */
final class LogbackConfigurator {
  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("holarki");
  private static final String HOLARKI_CONSOLE_APPENDER = "holarki-console";

  private LogbackConfigurator() {}

  static void configure(Config.Log logCfg) {
    if (logCfg == null) {
      return;
    }
    try {
      ILoggerFactory factory = LoggerFactory.getILoggerFactory();
      if (factory instanceof LoggerContext context) {
        configure(context, logCfg);
      } else {
        LOG.debug(
            "(holarki) skipping logback configuration; factory is {}",
            factory.getClass().getName());
      }
    } catch (NoClassDefFoundError e) {
      LOG.debug("(holarki) logback not available; leaving logging as-is");
    } catch (Throwable t) {
      LOG.warn("(holarki) failed to configure logging: {}", t.getMessage(), t);
    }
  }

  static void configure(LoggerContext context, Config.Log logCfg) {
    if (context == null || logCfg == null) {
      return;
    }
    applyLogbackConfiguration(context, logCfg);
  }

  private static void applyLogbackConfiguration(LoggerContext context, Config.Log logCfg) {
    Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    Level level = levelFrom(logCfg.level());
    if (level == null) {
      level = Level.INFO;
      LOG.warn("(holarki) invalid core.log.level {}; defaulting to INFO", logCfg.level());
    }
    root.setLevel(level);

    detachHolarkiAppender(root);
    if (logCfg.json()) {
      if (!attachJsonAppender(context, root)) {
        LOG.warn(
            "(holarki) structured logging requested but JSON encoder unavailable; using plain output");
        attachPatternAppender(context, root);
      }
    } else {
      attachPatternAppender(context, root);
    }
  }

  private static void detachHolarkiAppender(Logger root) {
    if (root.getAppender(HOLARKI_CONSOLE_APPENDER) != null) {
      root.detachAppender(HOLARKI_CONSOLE_APPENDER);
    }
  }

  private static void attachPatternAppender(LoggerContext context, Logger root) {
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(context);
    encoder.setPattern("%d{ISO8601} %-5level [%thread] %logger{36} - %msg%n");
    encoder.start();

    ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<>();
    console.setName(HOLARKI_CONSOLE_APPENDER);
    console.setContext(context);
    console.setEncoder(encoder);
    console.start();

    root.addAppender(console);
  }

  private static boolean attachJsonAppender(LoggerContext context, Logger root) {
    try {
      HolarkiJsonLayout layout = new HolarkiJsonLayout();
      layout.setContext(context);
      layout.start();

      LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
      encoder.setContext(context);
      encoder.setLayout(layout);
      encoder.start();

      ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<>();
      console.setName(HOLARKI_CONSOLE_APPENDER);
      console.setContext(context);
      console.setEncoder(encoder);
      console.start();

      root.addAppender(console);
      return true;
    } catch (NoClassDefFoundError e) {
      return false;
    }
  }

  private static Level levelFrom(String level) {
    if (level == null) {
      return null;
    }
    try {
      return Level.valueOf(level.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
