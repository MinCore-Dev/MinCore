/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.holarki.api.AttributeWriteException;
import dev.holarki.api.Attributes;
import dev.holarki.api.Attributes.WriteResult;
import dev.holarki.api.ErrorCode;
import dev.holarki.core.Services;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TimezonesWriteTest {

  @Test
  void setThrowsWhenWriteRejected() {
    Services services = new AttributesOnlyServices(new RejectingAttributes());

    AttributeWriteException ex =
        assertThrows(
            AttributeWriteException.class,
            () -> Timezones.set(UUID.randomUUID(), ZoneId.of("UTC"), services));

    assertEquals(ErrorCode.DEGRADED_MODE, ex.errorCode());
  }

  private static final class AttributesOnlyServices implements Services {
    private final Attributes attributes;

    private AttributesOnlyServices(Attributes attributes) {
      this.attributes = attributes;
    }

    @Override
    public dev.holarki.api.Players players() {
      throw new UnsupportedOperationException();
    }

    @Override
    public dev.holarki.api.Wallets wallets() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Attributes attributes() {
      return attributes;
    }

    @Override
    public dev.holarki.api.events.CoreEvents events() {
      throw new UnsupportedOperationException();
    }

    @Override
    public dev.holarki.api.storage.ModuleDatabase database() {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.concurrent.ScheduledExecutorService scheduler() {
      throw new UnsupportedOperationException();
    }

    @Override
    public dev.holarki.core.Metrics metrics() {
      return null;
    }

    @Override
    public dev.holarki.api.Playtime playtime() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {}
  }

  private static final class RejectingAttributes implements Attributes {
    @Override
    public Optional<String> get(UUID owner, String key) {
      return Optional.empty();
    }

    @Override
    public WriteResult put(UUID owner, String key, String jsonValue, long nowS) {
      return WriteResult.failure(ErrorCode.DEGRADED_MODE);
    }

    @Override
    public WriteResult remove(UUID owner, String key) {
      return WriteResult.success();
    }
  }
}
