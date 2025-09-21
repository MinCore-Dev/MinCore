/* MinCore © 2025 — MIT */
package dev.mincore.core;

import dev.mincore.api.ErrorCode;
import dev.mincore.api.Wallets.OperationResult;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple metrics registry that exposes core counters via JMX.
 *
 * <p>The implementation deliberately keeps the surface small: wallet operation success/failure
 * counts and idempotency outcomes. Additional metrics can be added as the core grows.
 */
public final class Metrics implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");
  private static final String MBEAN_NAME = "dev.mincore:type=MinCoreMetrics";

  private final AtomicLong walletSuccess = new AtomicLong();
  private final AtomicLong walletFailure = new AtomicLong();
  private final AtomicLong depositSuccess = new AtomicLong();
  private final AtomicLong depositFailure = new AtomicLong();
  private final AtomicLong withdrawSuccess = new AtomicLong();
  private final AtomicLong withdrawFailure = new AtomicLong();
  private final AtomicLong transferSuccess = new AtomicLong();
  private final AtomicLong transferFailure = new AtomicLong();
  private final AtomicLong walletReplays = new AtomicLong();
  private final AtomicLong walletMismatches = new AtomicLong();

  private final MBeanServer server;
  private final ObjectName objectName;

  /** Creates and registers the MinCore metrics MBean. */
  public Metrics() {
    this.server = ManagementFactory.getPlatformMBeanServer();
    this.objectName = createObjectName();
    registerMBean();
  }

  /**
   * Records a wallet mutation result for metrics aggregation.
   *
   * @param op logical operation name (deposit, withdraw, transfer)
   * @param result structured wallet outcome to aggregate
   */
  public void recordWalletOperation(String op, OperationResult result) {
    if (result == null) {
      return;
    }
    boolean ok = result.ok();
    switch (op == null ? "" : op.toLowerCase(Locale.ROOT)) {
      case "deposit" -> increment(ok ? depositSuccess : depositFailure);
      case "withdraw" -> increment(ok ? withdrawSuccess : withdrawFailure);
      case "transfer" -> increment(ok ? transferSuccess : transferFailure);
      default -> {
        // ignore unknown operations but still count success/failure totals
      }
    }
    increment(ok ? walletSuccess : walletFailure);
    if (result.code() == ErrorCode.IDEMPOTENCY_REPLAY) {
      walletReplays.incrementAndGet();
    } else if (result.code() == ErrorCode.IDEMPOTENCY_MISMATCH) {
      walletMismatches.incrementAndGet();
    }
  }

  private void increment(AtomicLong counter) {
    counter.incrementAndGet();
  }

  private ObjectName createObjectName() {
    try {
      return new ObjectName(MBEAN_NAME);
    } catch (MalformedObjectNameException e) {
      throw new IllegalStateException("Invalid metrics object name", e);
    }
  }

  private void registerMBean() {
    try {
      if (server.isRegistered(objectName)) {
        server.unregisterMBean(objectName);
      }
      server.registerMBean(new Bean(), objectName);
    } catch (InstanceAlreadyExistsException
        | MBeanRegistrationException
        | NotCompliantMBeanException e) {
      LOG.warn("(mincore) metrics registration failed", e);
    } catch (Exception e) {
      LOG.warn("(mincore) metrics registration unexpected failure", e);
    }
  }

  @Override
  public void close() {
    try {
      if (server.isRegistered(objectName)) {
        server.unregisterMBean(objectName);
      }
    } catch (Exception e) {
      LOG.debug("(mincore) metrics unregister failed", e);
    }
  }

  private final class Bean implements MinCoreMetricsMBean {
    @Override
    public long getWalletSuccess() {
      return walletSuccess.get();
    }

    @Override
    public long getWalletFailure() {
      return walletFailure.get();
    }

    @Override
    public long getWalletDepositSuccess() {
      return depositSuccess.get();
    }

    @Override
    public long getWalletDepositFailure() {
      return depositFailure.get();
    }

    @Override
    public long getWalletWithdrawSuccess() {
      return withdrawSuccess.get();
    }

    @Override
    public long getWalletWithdrawFailure() {
      return withdrawFailure.get();
    }

    @Override
    public long getWalletTransferSuccess() {
      return transferSuccess.get();
    }

    @Override
    public long getWalletTransferFailure() {
      return transferFailure.get();
    }

    @Override
    public long getWalletReplays() {
      return walletReplays.get();
    }

    @Override
    public long getWalletMismatches() {
      return walletMismatches.get();
    }
  }

  /** JMX view of the metrics registry. */
  public interface MinCoreMetricsMBean {
    /**
     * Returns total wallet operations marked as success.
     *
     * @return total wallet operations marked as success
     */
    long getWalletSuccess();

    /**
     * Returns total wallet operations marked as failure.
     *
     * @return total wallet operations marked as failure
     */
    long getWalletFailure();

    /**
     * Returns total deposit operations marked as success.
     *
     * @return total deposit operations marked as success
     */
    long getWalletDepositSuccess();

    /**
     * Returns total deposit operations marked as failure.
     *
     * @return total deposit operations marked as failure
     */
    long getWalletDepositFailure();

    /**
     * Returns total withdraw operations marked as success.
     *
     * @return total withdraw operations marked as success
     */
    long getWalletWithdrawSuccess();

    /**
     * Returns total withdraw operations marked as failure.
     *
     * @return total withdraw operations marked as failure
     */
    long getWalletWithdrawFailure();

    /**
     * Returns total transfer operations marked as success.
     *
     * @return total transfer operations marked as success
     */
    long getWalletTransferSuccess();

    /**
     * Returns total transfer operations marked as failure.
     *
     * @return total transfer operations marked as failure
     */
    long getWalletTransferFailure();

    /**
     * Returns total wallet operations treated as idempotent replays.
     *
     * @return total wallet operations treated as idempotent replays
     */
    long getWalletReplays();

    /**
     * Returns total wallet operations that triggered idempotency mismatches.
     *
     * @return total wallet operations that triggered idempotency mismatches
     */
    long getWalletMismatches();
  }
}
