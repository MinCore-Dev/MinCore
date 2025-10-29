/* Holarki © 2025 — MIT */
package dev.holarki.jdbc;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the MariaDB JDBC driver at runtime from disk.
 *
 * <p>Discovery rules (first match wins):
 *
 * <ol>
 *   <li>System property {@code holarki.jdbc.driver} (exact file path).
 *   <li>Search these folders for files named {@code mariadb-java-client-*.jar}: {@code
 *       mods/lib/mariadb}, {@code mods}, server root.
 *   <li>If multiple matches exist, choose the highest semantic version (e.g., 3.5.6 over 3.4.1).
 * </ol>
 *
 * <p><strong>Note:</strong> We do not close the {@link URLClassLoader} on purpose; the driver
 * classes must remain reachable for the lifetime of the process.
 */
public final class DriverLoader {
  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("holarki");
  private static final String PROP_OVERRIDE = "holarki.jdbc.driver";
  private static final List<String> ROOTS = Arrays.asList("mods/lib/mariadb", "mods", ".");

  private DriverLoader() {
    throw new AssertionError("No instances");
  }

  /** Attempts to locate and register a MariaDB JDBC driver (any 3.x) from disk. */
  public static void tryLoadMariaDbDriver() {
    File jar = findDriverJar();
    if (jar == null) {
      LOG.error(
          "(holarki) MariaDB JDBC driver not found. Place mariadb-java-client-<version>.jar in mods/lib/mariadb/ "
              + "or set -D{}=C:\\\\path\\\\mariadb-java-client-<version>.jar",
          PROP_OVERRIDE);
      return;
    }

    try {
      URLClassLoader ucl =
          new URLClassLoader(new URL[] {jar.toURI().toURL()}, DriverLoader.class.getClassLoader());
      Class<?> clazz = Class.forName("org.mariadb.jdbc.Driver", true, ucl);
      Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
      DriverManager.registerDriver(new DriverShim(driver)); // register via shim
      LOG.info("(holarki) JDBC driver loaded from {} (file: {})", jar.getParent(), jar.getName());
    } catch (Throwable t) {
      LOG.error("(holarki) failed to load JDBC driver from {}", jar.getPath(), t);
    }
  }

  /** Try override first, then scan typical folders; choose the highest version found. */
  private static File findDriverJar() {
    // 1) Exact override via system property
    String override = System.getProperty(PROP_OVERRIDE);
    if (override != null && !override.isBlank()) {
      File f = new File(override);
      if (f.isFile()) return f;
      LOG.warn("(holarki) {} set but file not found: {}", PROP_OVERRIDE, override);
    }

    // 2) Scan roots for mariadb-java-client-*.jar
    List<File> candidates = new ArrayList<>();
    for (String root : ROOTS) {
      File dir = new File(root);
      File[] files =
          dir.listFiles(
              f ->
                  f.isFile()
                      && f.getName().startsWith("mariadb-java-client-")
                      && f.getName().endsWith(".jar"));
      if (files != null) candidates.addAll(Arrays.asList(files));
    }
    if (candidates.isEmpty()) return null;

    // 3) Pick the highest version by filename (mariadb-java-client-<semver>.jar)
    candidates.sort(Comparator.comparing(DriverLoader::extractVersionKey).reversed());
    return candidates.get(0);
  }

  /**
   * Extract a numeric sortable key from a jar filename (e.g., "mariadb-java-client-3.5.6.jar").
   * Non-numeric parts after the version are ignored (e.g., "-beta").
   */
  private static String extractVersionKey(File f) {
    String name = f.getName();
    String base = name.substring("mariadb-java-client-".length(), name.length() - ".jar".length());
    int dash = base.indexOf('-'); // strip classifier like -sources if present
    if (dash > 0) base = base.substring(0, dash);
    // normalize to 3-part numeric "major.minor.patch" with left-padding to keep lexical ordering
    String[] parts = base.split("\\.");
    int major = parts.length > 0 ? parseIntSafe(parts[0]) : 0;
    int minor = parts.length > 1 ? parseIntSafe(parts[1]) : 0;
    int patch = parts.length > 2 ? parseIntSafe(parts[2]) : 0;
    return String.format("%03d.%03d.%03d", major, minor, patch);
  }

  private static int parseIntSafe(String s) {
    try {
      return Integer.parseInt(s.replaceAll("[^0-9]", ""));
    } catch (Exception ignored) {
      return 0;
    }
  }

  /** Shim so DriverManager accepts a driver loaded by a child classloader. */
  private static final class DriverShim implements Driver {
    private final Driver d;

    DriverShim(Driver d) {
      this.d = d;
    }

    @Override
    public boolean acceptsURL(String u) throws java.sql.SQLException {
      return d.acceptsURL(u);
    }

    @Override
    public java.sql.Connection connect(String u, Properties p) throws java.sql.SQLException {
      return d.connect(u, p);
    }

    @Override
    public int getMajorVersion() {
      return d.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
      return d.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
      return d.jdbcCompliant();
    }

    @Override
    public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, Properties p)
        throws java.sql.SQLException {
      return d.getPropertyInfo(u, p);
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      return d.getParentLogger();
    }
  }
}
