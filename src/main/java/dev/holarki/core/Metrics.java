/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import dev.holarki.api.ErrorCode;
import dev.holarki.api.Wallets.OperationResult;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>The implementation exposes wallet, player, attribute, ledger, and module-database counters
 * via JMX. Metrics focus on success/failure counts and capture the last observed error codes for
 * quick diagnostics.
 */
public final class Metrics implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger("holarki");
  private static final String MBEAN_NAME = "dev.holarki:type=HolarkiMetrics";

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
  private final AtomicLong playerLookupSuccess = new AtomicLong();
  private final AtomicLong playerLookupFailure = new AtomicLong();
  private final AtomicLong playerMutationSuccess = new AtomicLong();
  private final AtomicLong playerMutationFailure = new AtomicLong();
  private final AtomicLong attributeReadSuccess = new AtomicLong();
  private final AtomicLong attributeReadFailure = new AtomicLong();
  private final AtomicLong attributeWriteSuccess = new AtomicLong();
  private final AtomicLong attributeWriteFailure = new AtomicLong();
  private final AtomicLong ledgerWriteSuccess = new AtomicLong();
  private final AtomicLong ledgerWriteFailure = new AtomicLong();
  private final AtomicLong moduleOpSuccess = new AtomicLong();
  private final AtomicLong moduleOpFailure = new AtomicLong();

  private final AtomicReference<String> lastPlayerErrorCode = new AtomicReference<>("NONE");
  private final AtomicReference<String> lastAttributeErrorCode = new AtomicReference<>("NONE");
  private final AtomicReference<String> lastLedgerErrorCode = new AtomicReference<>("NONE");
  private final AtomicReference<String> lastModuleErrorCode = new AtomicReference<>("NONE");

  private final MBeanServer server;
  private final ObjectName objectName;

  /** Creates and registers the Holarki metrics MBean. */
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

  /** Records a player lookup (read-only) outcome. */
  public void recordPlayerLookup(boolean ok, ErrorCode code) {
    increment(ok ? playerLookupSuccess : playerLookupFailure);
    if (!ok && code != null) {
      lastPlayerErrorCode.set(code.name());
    }
  }

  /** Records a player mutation (insert/update/delete) outcome. */
  public void recordPlayerMutation(boolean ok, ErrorCode code) {
    increment(ok ? playerMutationSuccess : playerMutationFailure);
    if (!ok && code != null) {
      lastPlayerErrorCode.set(code.name());
    }
  }

  /** Records an attribute read operation. */
  public void recordAttributeRead(boolean ok, ErrorCode code) {
    increment(ok ? attributeReadSuccess : attributeReadFailure);
    if (!ok && code != null) {
      lastAttributeErrorCode.set(code.name());
    }
  }

  /** Records an attribute write (put/remove) operation. */
  public void recordAttributeWrite(boolean ok, ErrorCode code) {
    increment(ok ? attributeWriteSuccess : attributeWriteFailure);
    if (!ok && code != null) {
      lastAttributeErrorCode.set(code.name());
    }
  }

  /** Records a ledger database write. */
  public void recordLedgerWrite(boolean ok, ErrorCode code) {
    increment(ok ? ledgerWriteSuccess : ledgerWriteFailure);
    if (!ok && code != null) {
      lastLedgerErrorCode.set(code.name());
    }
  }

  /** Records a ModuleDatabase helper operation outcome. */
  public void recordModuleOperation(boolean ok, ErrorCode code) {
    increment(ok ? moduleOpSuccess : moduleOpFailure);
    if (!ok && code != null) {
      lastModuleErrorCode.set(code.name());
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
      LOG.warn("(holarki) metrics registration failed", e);
    } catch (Exception e) {
      LOG.warn("(holarki) metrics registration unexpected failure", e);
    }
  }

  @Override
  public void close() {
    try {
      if (server.isRegistered(objectName)) {
        server.unregisterMBean(objectName);
      }
    } catch (Exception e) {
      LOG.debug("(holarki) metrics unregister failed", e);
    }
  }

  private final class Bean implements HolarkiMetricsMBean {
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

    @Override
    public long getPlayerLookupSuccess() {
      return playerLookupSuccess.get();
    }

    @Override
    public long getPlayerLookupFailure() {
      return playerLookupFailure.get();
    }

    @Override
    public long getPlayerMutationSuccess() {
      return playerMutationSuccess.get();
    }

    @Override
    public long getPlayerMutationFailure() {
      return playerMutationFailure.get();
    }

    @Override
    public long getAttributeReadSuccess() {
      return attributeReadSuccess.get();
    }

    @Override
    public long getAttributeReadFailure() {
      return attributeReadFailure.get();
    }

    @Override
    public long getAttributeWriteSuccess() {
      return attributeWriteSuccess.get();
    }

    @Override
    public long getAttributeWriteFailure() {
      return attributeWriteFailure.get();
    }

    @Override
    public long getLedgerWriteSuccess() {
      return ledgerWriteSuccess.get();
    }

    @Override
    public long getLedgerWriteFailure() {
      return ledgerWriteFailure.get();
    }

    @Override
    public long getModuleOperationSuccess() {
      return moduleOpSuccess.get();
    }

    @Override
    public long getModuleOperationFailure() {
      return moduleOpFailure.get();
    }

    @Override
    public String getLastPlayerErrorCode() {
      return lastPlayerErrorCode.get();
    }

    @Override
    public String getLastAttributeErrorCode() {
      return lastAttributeErrorCode.get();
    }

    @Override
    public String getLastLedgerErrorCode() {
      return lastLedgerErrorCode.get();
    }

    @Override
    public String getLastModuleErrorCode() {
      return lastModuleErrorCode.get();
    }
  }

  /** JMX view of the metrics registry. */
  public interface HolarkiMetricsMBean {
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

    /** Player lookup successes. */
    long getPlayerLookupSuccess();

    /** Player lookup failures. */
    long getPlayerLookupFailure();

    /** Player mutation successes. */
    long getPlayerMutationSuccess();

    /** Player mutation failures. */
    long getPlayerMutationFailure();

    /** Attribute read successes. */
    long getAttributeReadSuccess();

    /** Attribute read failures. */
    long getAttributeReadFailure();

    /** Attribute write successes. */
    long getAttributeWriteSuccess();

    /** Attribute write failures. */
    long getAttributeWriteFailure();

    /** Ledger write successes. */
    long getLedgerWriteSuccess();

    /** Ledger write failures. */
    long getLedgerWriteFailure();

    /** Module database helper successes. */
    long getModuleOperationSuccess();

    /** Module database helper failures. */
    long getModuleOperationFailure();

    /** Last observed player error code. */
    String getLastPlayerErrorCode();

    /** Last observed attribute error code. */
    String getLastAttributeErrorCode();

    /** Last observed ledger error code. */
    String getLastLedgerErrorCode();

    /** Last observed module database error code. */
    String getLastModuleErrorCode();
  }
}
