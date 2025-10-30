/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DbBootstrapTest {
  @Test
  void retainsTlsQueryParametersInBootstrapUrl() {
    String jdbcUrl =
        "jdbc:mariadb://example.com:3306/holarki?useSsl=true&sslMode=VERIFY_IDENTITY";

    String bootstrapUrl = DbBootstrap.buildBootstrapUrl(jdbcUrl);

    assertEquals(
        "jdbc:mariadb://example.com:3306/?useSsl=true&sslMode=VERIFY_IDENTITY", bootstrapUrl);
  }
}
