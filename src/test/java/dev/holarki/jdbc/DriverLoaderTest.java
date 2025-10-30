/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.slf4j.Logger;

class DriverLoaderTest {
  private Path modsDir;
  private Path libsDir;
  private Path libsParentDir;
  private Logger testLogger;
  private final List<Path> createdFiles = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    Path projectRoot = Path.of("").toAbsolutePath().normalize();
    modsDir = projectRoot.resolve("mods");
    libsDir = modsDir.resolve("lib/mariadb");
    libsParentDir = modsDir.resolve("lib");
    Files.createDirectories(libsDir);
    System.clearProperty("holarki.jdbc.driver");
    testLogger = Mockito.mock(Logger.class);
    DriverLoader.setLoggerForTesting(testLogger);
  }

  @AfterEach
  void tearDown() throws Exception {
    DriverLoader.clearLoggerOverride();
    for (Path path : createdFiles) {
      Files.deleteIfExists(path);
    }
    createdFiles.clear();
    deleteDirectoryIfEmpty(libsDir);
    deleteDirectoryIfEmpty(libsParentDir);
    deleteDirectoryIfEmpty(modsDir);
    testLogger = null;
  }

  @Test
  void skipsLegacyMajorVersionsAndPicksNewestSupported() throws Exception {
    createJar(libsDir, "mariadb-java-client-2.7.6.jar");
    createJar(libsDir, "mariadb-java-client-3.3.2.jar");
    createJar(libsDir, "mariadb-java-client-3.4.1.jar");

    File jar = invokeFindDriverJar();

    assertNotNull(jar);
    assertEquals("mariadb-java-client-3.4.1.jar", jar.getName());
    assertWarningCaptured("mariadb-java-client-2.7.6.jar", 2);
  }

  @Test
  void returnsNullWhenOnlyLegacyDriversPresent() throws Exception {
    createJar(modsDir, "mariadb-java-client-2.9.0.jar");

    File jar = invokeFindDriverJar();

    assertNull(jar);
    assertWarningCaptured("mariadb-java-client-2.9.0.jar", 2);
  }

  private void createJar(Path directory, String name) throws Exception {
    Path file = directory.resolve(name);
    Files.deleteIfExists(file);
    Files.createDirectories(file.getParent());
    Files.createFile(file);
    createdFiles.add(file);
  }

  private void deleteDirectoryIfEmpty(Path directory) throws Exception {
    if (!Files.exists(directory) || !Files.isDirectory(directory)) {
      return;
    }
    try (var entries = Files.list(directory)) {
      if (entries.findAny().isEmpty()) {
        Files.delete(directory);
      }
    }
  }

  private File invokeFindDriverJar() throws Exception {
    Method method = DriverLoader.class.getDeclaredMethod("findDriverJar");
    method.setAccessible(true);
    return (File) method.invoke(null);
  }

  private void assertWarningCaptured(String jarName, int major) {
    boolean found =
        Mockito.mockingDetails(testLogger).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("warn"))
            .anyMatch(invocation -> matches(invocation, jarName, major));
    if (!found) {
      throw new AssertionError("Expected warning for " + jarName);
    }
  }

  private boolean matches(Invocation invocation, String jarName, int major) {
    Object[] args = invocation.getArguments();
    if (args.length < 3) {
      return false;
    }
    if (!(args[0] instanceof String message)
        || !message.startsWith("(holarki) ignoring MariaDB driver")) {
      return false;
    }
    return jarName.equals(args[1]) && Integer.valueOf(major).equals(args[2]);
  }
}
