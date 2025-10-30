/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dispatches logging configuration to the active backend when available. */
final class LoggingConfigurator {
  private static final Logger LOG = LoggerFactory.getLogger("holarki");
  private static final String LOGBACK_CLASS = "dev.holarki.core.LogbackConfigurator";

  private LoggingConfigurator() {}

  static void configure(Config.Log logCfg) {
    if (logCfg == null) {
      return;
    }
    try {
      Class<?> configurator = Class.forName(LOGBACK_CLASS);
      Method configure = configurator.getDeclaredMethod("configure", Config.Log.class);
      configure.setAccessible(true);
      configure.invoke(null, logCfg);
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      LOG.debug("(holarki) logback backend not detected; leaving logging at defaults");
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      LOG.warn("(holarki) failed to configure logging: {}", cause.getMessage(), cause);
    } catch (ReflectiveOperationException e) {
      LOG.warn("(holarki) failed to configure logging: {}", e.getMessage(), e);
    }
  }
}
