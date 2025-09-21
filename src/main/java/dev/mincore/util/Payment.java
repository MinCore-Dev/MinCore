/* MinCore © 2025 — MIT */
package dev.mincore.util;

import dev.mincore.api.Wallets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/**
 * Utility functions for payment flows: reason normalization, idempotency keys, and a simple
 * one-shot transfer helper.
 *
 * <p>These helpers are intentionally lightweight and avoid any direct database access; they operate
 * in terms of the public {@link Wallets} API. Use them inside add-ons to reduce boilerplate around
 * consistent reason strings and idempotent operations.
 */
public final class Payment {
  private Payment() {}

  /**
   * Normalizes a human-provided reason to an ASCII-like, lowercase string with length {@literal <=}
   * 64 characters.
   *
   * <p>Rules:
   *
   * <ul>
   *   <li>Trims leading/trailing whitespace.
   *   <li>Lowercases using {@link Locale#ROOT}.
   *   <li>Replaces any character outside {@code [a-z0-9:_\\-. ]} with {@code '?'}.
   *   <li>Truncates to 64 code units if longer.
   * </ul>
   *
   * @param reason free-form text supplied by the caller (may be {@code null})
   * @return a safe, normalized reason string; never {@code null}
   */
  public static String normalizeReason(String reason) {
    if (reason == null) return "";
    String r = reason.trim().toLowerCase(Locale.ROOT);
    if (r.length() > 64) r = r.substring(0, 64);
    return r.replaceAll("[^a-z0-9:_\\-\\. ]", "?");
  }

  /**
   * Builds a deterministic idempotency key for a money movement within an add-on scope.
   *
   * <p>This returns a stable, human-readable composite string in the form:
   *
   * <pre>{@code
   * <scope>|<from-uuid>|<to-uuid>|<amount>|<normalized-reason>
   * }</pre>
   *
   * <p>If you need a fixed-length token for storage or privacy, hash the result with {@link
   * #sha256(String)} and store the digest instead.
   *
   * @param scope add-on scope namespace (for example, {@code "shop:buy"})
   * @param from payer UUID (nullable; replaced by the zero-UUID when null)
   * @param to payee UUID (nullable; replaced by the zero-UUID when null)
   * @param amount amount in smallest currency units
   * @param reasonNorm normalized reason (see {@link #normalizeReason(String)})
   * @return a stable composite idempotency key string
   */
  public static String idemKey(String scope, UUID from, UUID to, long amount, String reasonNorm) {
    String f = from == null ? "00000000-0000-0000-0000-000000000000" : from.toString();
    String t = to == null ? "00000000-0000-0000-0000-000000000000" : to.toString();
    return scope + "|" + f + "|" + t + "|" + amount + "|" + reasonNorm;
  }

  /**
   * Computes the SHA-256 digest of a string using UTF-8 encoding.
   *
   * @param s input text
   * @return a 32-byte SHA-256 digest; returns an empty array if the algorithm is unavailable
   */
  public static byte[] sha256(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      return new byte[0];
    }
  }

  /**
   * Performs a one-shot, idempotent transfer: withdraws from the payer and deposits to the payee
   * using {@link Wallets#transfer(UUID, UUID, long, String, String)}.
   *
   * <p>The idempotency key is derived from {@code addonScope}, the participants, the amount, and
   * the normalized reason. Repeating the same call with the same arguments will be treated as the
   * same logical transfer by the wallet layer.
   *
   * @param w wallet service
   * @param from payer UUID (must not be {@code null})
   * @param to payee UUID (must not be {@code null})
   * @param amount smallest currency units; must be {@code > 0}
   * @param reason short, human-friendly reason; normalized internally via {@link
   *     #normalizeReason(String)}
   * @param addonScope namespace for idempotency keys (for example, {@code "shop:buy"})
   * @return {@code true} if the transfer completed successfully or was already applied; {@code
   *     false} otherwise
   */
  public static boolean safeTransfer(
      Wallets w, UUID from, UUID to, long amount, String reason, String addonScope) {
    String rn = normalizeReason(reason);
    String idem = idemKey(addonScope + ":transfer", from, to, amount, rn);
    return w.transfer(from, to, amount, rn, idem);
  }
}
