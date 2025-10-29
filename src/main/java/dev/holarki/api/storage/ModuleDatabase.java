/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.api.storage;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Shared database helpers available to bundled modules and operator automation maintained by server
 * staff.
 *
 * <p>Lets Holarki modules and automation borrow connections, use advisory locks, or run actions with
 * retry without shipping their own pool management.
 */
public interface ModuleDatabase {
  /**
   * Borrows a JDBC {@link Connection} from the pool.
   *
   * @return connection (caller must {@link Connection#close() close} it)
   * @throws SQLException on pool/database error
   */
  Connection borrowConnection() throws SQLException;

  /**
   * Attempts to acquire a named advisory lock immediately.
   *
   * @param name lock name (quoted and escaped)
   * @return {@code true} if lock acquired
   */
  boolean tryAdvisoryLock(String name);

  /**
   * Releases a previously acquired advisory lock.
   *
   * @param name lock name
   */
  void releaseAdvisoryLock(String name);

  /**
   * Runs {@code action} with simple retries; throws the last {@link SQLException} if all attempts
   * fail.
   *
   * @param <T> result type
   * @param action work to perform
   * @return result
   * @throws SQLException last error if all retries fail
   */
  <T> T withRetry(SQLSupplier<T> action) throws SQLException;

  /**
   * Gets a helper that provides idempotent schema evolution primitives.
   *
   * <p>The returned helper is safe to cache and reuse. It exposes convenience methods such as
   * {@link SchemaHelper#ensureTable(String)}, {@link SchemaHelper#addColumnIfMissing(String,
   * String, String)}, and {@link SchemaHelper#ensureIndex(String, String, String)}.
   *
   * @return schema helper singleton
   */
  SchemaHelper schema();

  /**
   * Functional supplier that can throw {@link SQLException}.
   *
   * @param <T> result type
   */
  @FunctionalInterface
  interface SQLSupplier<T> {
    /**
     * Supplies a value, possibly performing JDBC work.
     *
     * @return supplied value
     * @throws java.sql.SQLException if the supplier fails
     */
    T get() throws java.sql.SQLException;
  }
}
