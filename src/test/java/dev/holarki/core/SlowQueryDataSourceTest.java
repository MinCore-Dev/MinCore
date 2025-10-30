/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.Connection;
import java.sql.PreparedStatement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlowQueryDataSourceTest {
  private LoggerContext context;
  private Logger holarkiLogger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setup() {
    context = new LoggerContext();
    context.start();
    holarkiLogger = context.getLogger("holarki");
    appender = new ListAppender<>();
    appender.start();
    holarkiLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    if (holarkiLogger != null && appender != null) {
      holarkiLogger.detachAppender(appender);
    }
    if (appender != null) {
      appender.stop();
    }
    if (context != null) {
      context.stop();
    }
  }

  @Test
  void warnsWhenQueryExceedsThreshold() throws Exception {
    DataSource delegate = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement prepared = mock(PreparedStatement.class);

    when(delegate.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(prepared);
    when(prepared.execute())
        .thenAnswer(
            invocation -> {
              Thread.sleep(15L);
              return Boolean.TRUE;
            });

    DataSource wrapped = SlowQueryDataSource.wrap(delegate, 5L, holarkiLogger);

    try (Connection c = wrapped.getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT 1")) {
      ps.execute();
    }

    assertTrue(
        appender.list.stream()
            .anyMatch(event -> event.getFormattedMessage().contains("DB_SLOW_QUERY")),
        "expected slow query warning to be emitted");
  }
}
