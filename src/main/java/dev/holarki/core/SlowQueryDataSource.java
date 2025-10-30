/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a {@link DataSource} to detect slow SQL executions and log structured warnings when a query
 * exceeds the configured latency budget.
 */
final class SlowQueryDataSource implements DataSource {
  private static final String CODE = "DB_SLOW_QUERY";

  private final DataSource delegate;
  private final long thresholdMs;

  private final Logger logger;

  private SlowQueryDataSource(DataSource delegate, long thresholdMs, Logger logger) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.thresholdMs = thresholdMs;
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  static DataSource wrap(DataSource delegate, long thresholdMs) {
    return wrap(delegate, thresholdMs, LoggerFactory.getLogger("holarki"));
  }

  static DataSource wrap(DataSource delegate, long thresholdMs, Logger logger) {
    if (delegate == null) {
      return null;
    }
    if (thresholdMs <= 0) {
      return delegate;
    }
    if (delegate instanceof SlowQueryDataSource) {
      return delegate;
    }
    return new SlowQueryDataSource(delegate, thresholdMs, logger);
  }

  @Override
  public Connection getConnection() throws SQLException {
    Connection connection = delegate.getConnection();
    return wrapConnection(connection);
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    Connection connection = delegate.getConnection(username, password);
    return wrapConnection(connection);
  }

  private Connection wrapConnection(Connection connection) {
    if (connection == null) {
      return null;
    }
    ConnectionInvocationHandler handler = new ConnectionInvocationHandler(connection);
    Connection proxy =
        (Connection)
            Proxy.newProxyInstance(
                connection.getClass().getClassLoader(), new Class[] {Connection.class}, handler);
    handler.setProxy(proxy);
    return proxy;
  }

  private Statement wrapStatement(Statement statement, Connection connectionProxy, String sql) {
    if (statement == null) {
      return null;
    }
    Class<?>[] interfaces;
    if (statement instanceof CallableStatement) {
      interfaces = new Class[] {CallableStatement.class};
    } else if (statement instanceof PreparedStatement) {
      interfaces = new Class[] {PreparedStatement.class};
    } else {
      interfaces = new Class[] {Statement.class};
    }
    StatementInvocationHandler handler =
        new StatementInvocationHandler(statement, connectionProxy, sql, thresholdMs, logger);
    return (Statement)
        Proxy.newProxyInstance(statement.getClass().getClassLoader(), interfaces, handler);
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    return delegate.unwrap(iface);
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isInstance(this) || delegate.isWrapperFor(iface);
  }

  @Override
  public java.io.PrintWriter getLogWriter() throws SQLException {
    return delegate.getLogWriter();
  }

  @Override
  public void setLogWriter(java.io.PrintWriter out) throws SQLException {
    delegate.setLogWriter(out);
  }

  @Override
  public void setLoginTimeout(int seconds) throws SQLException {
    delegate.setLoginTimeout(seconds);
  }

  @Override
  public int getLoginTimeout() throws SQLException {
    return delegate.getLoginTimeout();
  }

  @Override
  public java.util.logging.Logger getParentLogger() {
    try {
      Method method = delegate.getClass().getMethod("getParentLogger");
      Object result = method.invoke(delegate);
      if (result instanceof java.util.logging.Logger logger) {
        return logger;
      }
    } catch (ReflectiveOperationException ignored) {
      // Fall through to default when the delegate does not expose a parent logger.
    }
    return java.util.logging.Logger.getGlobal();
  }

  private final class ConnectionInvocationHandler implements InvocationHandler {
    private final Connection delegateConnection;
    private Connection proxy;

    private ConnectionInvocationHandler(Connection delegateConnection) {
      this.delegateConnection = delegateConnection;
    }

    private void setProxy(Connection proxy) {
      this.proxy = proxy;
    }

    @Override
    public Object invoke(Object proxyInstance, Method method, Object[] args) throws Throwable {
      String name = method.getName();
      if ("equals".equals(name)) {
        return args != null && args.length > 0 && proxy == args[0];
      }
      if ("hashCode".equals(name)) {
        return System.identityHashCode(proxy);
      }
      try {
        Object result = method.invoke(delegateConnection, args);
        if (result instanceof Statement statement) {
          String sql = extractSql(args);
          return wrapStatement(statement, proxy, sql);
        }
        return result;
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }

    private String extractSql(Object[] args) {
      if (args == null || args.length == 0) {
        return null;
      }
      Object first = args[0];
      return first instanceof String ? (String) first : null;
    }
  }

  private static final class StatementInvocationHandler implements InvocationHandler {
    private final Statement delegateStatement;
    private final Connection connectionProxy;
    private final String preparedSql;
    private final long thresholdMs;
    private final Logger logger;

    private StatementInvocationHandler(
        Statement delegateStatement,
        Connection connectionProxy,
        String preparedSql,
        long thresholdMs,
        Logger logger) {
      this.delegateStatement = delegateStatement;
      this.connectionProxy = connectionProxy;
      this.preparedSql = preparedSql;
      this.thresholdMs = thresholdMs;
      this.logger = logger;
    }

    @Override
    public Object invoke(Object proxyInstance, Method method, Object[] args) throws Throwable {
      String name = method.getName();
      if ("equals".equals(name)) {
        return args != null && args.length > 0 && proxyInstance == args[0];
      }
      if ("hashCode".equals(name)) {
        return System.identityHashCode(proxyInstance);
      }
      if ("toString".equals(name)) {
        return delegateStatement.toString();
      }
      if ("getConnection".equals(name)) {
        return connectionProxy;
      }
      boolean timed = isExecuteMethod(name);
      long startNs = 0L;
      if (timed) {
        startNs = System.nanoTime();
      }
      try {
        Object result = method.invoke(delegateStatement, args);
        return result;
      } catch (InvocationTargetException e) {
        throw e.getCause();
      } finally {
        if (timed) {
          long elapsedNs = System.nanoTime() - startNs;
          long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNs);
          if (elapsedMs >= thresholdMs) {
            logSlowQuery(name, args, elapsedMs);
          }
        }
      }
    }

    private boolean isExecuteMethod(String name) {
      return name.startsWith("execute");
    }

    private void logSlowQuery(String method, Object[] args, long elapsedMs) {
      String sql = resolveSql(args);
      logger.warn(
          "(holarki) code={} op={} elapsedMs={} thresholdMs={} sql={}",
          CODE,
          method,
          elapsedMs,
          thresholdMs,
          sql);
    }

    private String resolveSql(Object[] args) {
      if (args != null && args.length > 0 && args[0] instanceof String) {
        return abbreviate((String) args[0]);
      }
      if (preparedSql != null) {
        return abbreviate(preparedSql);
      }
      return "<unknown>";
    }

    private String abbreviate(String sql) {
      if (sql == null) {
        return "<null>";
      }
      String normalized = sql.replaceAll("\s+", " ").trim();
      if (normalized.length() <= 160) {
        return normalized;
      }
      return normalized.substring(0, 157) + "...";
    }
  }
}
