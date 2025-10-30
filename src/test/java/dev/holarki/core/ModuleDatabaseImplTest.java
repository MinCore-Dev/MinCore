/* Holarki © 2025 — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.holarki.api.ErrorCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ModuleDatabaseImplTest {

  @Mock private DataSource dataSource;
  @Mock private DbHealth dbHealth;
  @Mock private Metrics metrics;
  @Mock private Connection connection;
  @Mock private PreparedStatement statement;
  @Mock private PreparedStatement releaseStatement;
  @Mock private ResultSet resultSet;

  @InjectMocks private ModuleDatabaseImpl moduleDatabase;

  @Test
  void tryAdvisoryLockTreatsNullResultAsFailure() throws Exception {
    when(dbHealth.allowWrite(anyString())).thenReturn(true);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("SELECT GET_LOCK(?, 0)")).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true);
    when(resultSet.getInt(1)).thenReturn(0);
    when(resultSet.wasNull()).thenReturn(true);

    boolean acquired = moduleDatabase.tryAdvisoryLock("test_lock");

    assertFalse(acquired);
    verify(resultSet).wasNull();
    verify(dbHealth).markFailure(any(SQLException.class));
    verify(metrics).recordModuleOperation(false, ErrorCode.CONNECTION_LOST);
    verify(dbHealth, never()).markSuccess();
    verify(connection).close();
  }

  @Test
  void tryAdvisoryLockDuplicateAcquireCountsAsSuccess() throws Exception {
    when(dbHealth.allowWrite(anyString())).thenReturn(true);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("SELECT GET_LOCK(?, 0)")).thenReturn(statement);
    when(connection.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(releaseStatement);
    when(statement.executeQuery()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true);
    when(resultSet.getInt(1)).thenReturn(1);
    when(resultSet.wasNull()).thenReturn(false);
    when(releaseStatement.executeQuery()).thenReturn(resultSet);

    var field = ModuleDatabaseImpl.class.getDeclaredField("lockConnections");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    var lockConnections = (java.util.Map<String, Connection>) field.get(moduleDatabase);
    lockConnections.put("test_lock", connection);

    boolean acquired = moduleDatabase.tryAdvisoryLock("test_lock");

    assertFalse(acquired);
    verify(dbHealth).markSuccess();
    verify(metrics).recordModuleOperation(true, null);
    verify(metrics, never()).recordModuleOperation(false, null);
    verify(dbHealth, never()).markFailure(any(SQLException.class));
    verify(connection).close();
  }

  @Test
  void tryAdvisoryLockUnexpectedResultFlagsFailure() throws Exception {
    when(dbHealth.allowWrite(anyString())).thenReturn(true);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("SELECT GET_LOCK(?, 0)")).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true);
    when(resultSet.getInt(1)).thenReturn(2);
    when(resultSet.wasNull()).thenReturn(false);

    boolean acquired = moduleDatabase.tryAdvisoryLock("test_lock");

    assertFalse(acquired);
    verify(dbHealth).markFailure(any(SQLException.class));
    verify(metrics).recordModuleOperation(false, ErrorCode.CONNECTION_LOST);
    verify(metrics, never()).recordModuleOperation(true, null);
    verify(dbHealth, never()).markSuccess();
    verify(connection).close();
  }
}
