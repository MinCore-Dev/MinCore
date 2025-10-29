/* Holarki © 2025 — MIT */
package dev.holarki.perms;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.holarki.perms.Perms.PlayerAccess;
import dev.holarki.perms.Perms.ServerAccess;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Perms}. */
final class PermsTest {
  @Test
  void classLoads() {
    assertDoesNotThrow(() -> Class.forName("dev.holarki.perms.Perms"));
  }

  @Test
  void fallsBackToOpLevelWhenNoIntegrationsPresent() {
    UUID uuid = UUID.randomUUID();
    PlayerAccess stub =
        new PlayerAccess() {
          @Override
          public UUID uuid() {
            return uuid;
          }

          @Override
          public net.minecraft.server.network.ServerPlayerEntity asEntity() {
            return null;
          }

          @Override
          public boolean hasPermissionLevel(int opLevel) {
            return opLevel <= 4;
          }
        };

    assertTrue(Perms.check(stub, "holarki.test", 4));
  }

  @Test
  void uuidCheckUsesOpLevelFallbackWhenOffline() {
    UUID uuid = UUID.randomUUID();
    ServerAccess server =
        new ServerAccess() {
          @Override
          public net.minecraft.server.network.ServerPlayerEntity onlinePlayer(UUID ignored) {
            return null;
          }

          @Override
          public boolean hasPermissionLevel(UUID ignored, int opLevel) {
            return opLevel <= 4;
          }
        };

    assertTrue(Perms.check(server, uuid, "holarki.test", 4));
  }
}
