/* MinCore © 2025 — MIT */
package dev.mincore.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/** Writes JSONL snapshot exports for {@code /mincore backup now}. */
public final class BackupExporter {
  private static final DateTimeFormatter TS =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private BackupExporter() {}

  /**
   * Runs a JSONL export covering players, player_attributes, and core_ledger.
   *
   * @param services live services
   * @param cfg runtime configuration
   * @return metadata about the export
   * @throws IOException if writing to disk fails
   * @throws SQLException if database access fails
   */
  public static Result exportAll(Services services, Config cfg) throws IOException, SQLException {
    Config.Backup backupCfg = cfg.jobs().backup();
    if (!backupCfg.enabled()) {
      throw new IllegalStateException("backup disabled in config");
    }

    Path outDir = Path.of(backupCfg.outDir());
    Files.createDirectories(outDir);
    String stamp = TS.format(Instant.now());
    String baseName = "mincore-" + stamp + ".jsonl" + (backupCfg.gzip() ? ".gz" : "");
    Path outFile = outDir.resolve(baseName);

    MessageDigest sha = sha256();

    try (Connection c = services.database().borrowConnection()) {
      c.setAutoCommit(false);
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

      try (OutputStream fos = Files.newOutputStream(outFile);
          OutputStream wrapped = backupCfg.gzip() ? new GZIPOutputStream(fos) : fos;
          DigestOutput digestOut = new DigestOutput(wrapped, sha);
          BufferedWriter writer =
              new BufferedWriter(new OutputStreamWriter(digestOut, StandardCharsets.UTF_8))) {

        writeHeader(writer, cfg);
        long players = dumpPlayers(writer, c);
        long attrs = dumpAttributes(writer, c);
        long ledger = dumpLedger(writer, c);
        writer.flush();

        Files.writeString(
            outDir.resolve(baseName + ".sha256"), HexFormat.of().formatHex(sha.digest()));
        prune(outDir, backupCfg.prune(), outFile);
        c.rollback();
        return new Result(outFile, players, attrs, ledger);
      }
    }
  }

  private static void writeHeader(BufferedWriter writer, Config cfg) throws IOException {
    String json =
        "{"
            + quote("version")
            + ":"
            + quote("jsonl/v1")
            + ','
            + quote("generatedAt")
            + ":"
            + quote(Instant.now().toString())
            + ','
            + quote("defaultZone")
            + ":"
            + quote(cfg.time().display().defaultZone().getId())
            + "}\n";
    writer.write(json);
  }

  private static long dumpPlayers(BufferedWriter writer, Connection c)
      throws SQLException, IOException {
    String sql =
        "SELECT HEX(uuid) AS uuid, name, balance_units, created_at_s, updated_at_s, seen_at_s FROM players";
    try (PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      long count = 0;
      while (rs.next()) {
        String line =
            "{"
                + quote("table")
                + ":"
                + quote("players")
                + ','
                + quote("uuid")
                + ":"
                + quote(formatUuid(rs.getString("uuid")))
                + ','
                + quote("name")
                + ":"
                + quote(rs.getString("name"))
                + ','
                + quote("balance")
                + ":"
                + rs.getLong("balance_units")
                + ','
                + quote("createdAt")
                + ":"
                + rs.getLong("created_at_s")
                + ','
                + quote("updatedAt")
                + ":"
                + rs.getLong("updated_at_s")
                + ','
                + quote("seenAt")
                + ":"
                + rs.getLong("seen_at_s")
                + "}\n";
        writer.write(line);
        count++;
      }
      return count;
    }
  }

  private static long dumpAttributes(BufferedWriter writer, Connection c)
      throws SQLException, IOException {
    String sql =
        "SELECT HEX(owner_uuid) AS owner_uuid, attr_key, value_json, created_at_s, updated_at_s FROM player_attributes";
    try (PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      long count = 0;
      while (rs.next()) {
        String line =
            "{"
                + quote("table")
                + ":"
                + quote("player_attributes")
                + ','
                + quote("owner")
                + ":"
                + quote(formatUuid(rs.getString("owner_uuid")))
                + ','
                + quote("key")
                + ":"
                + quote(rs.getString("attr_key"))
                + ','
                + quote("value")
                + ":"
                + rs.getString("value_json")
                + ','
                + quote("createdAt")
                + ":"
                + rs.getLong("created_at_s")
                + ','
                + quote("updatedAt")
                + ":"
                + rs.getLong("updated_at_s")
                + "}\n";
        writer.write(line);
        count++;
      }
      return count;
    }
  }

  private static long dumpLedger(BufferedWriter writer, Connection c)
      throws SQLException, IOException {
    String sql =
        "SELECT ts_s, addon_id, op, HEX(from_uuid) AS from_uuid, HEX(to_uuid) AS to_uuid, amount, reason, ok, code, seq, "
            + "idem_scope, HEX(idem_key_hash) AS idem_key_hash, old_units, new_units, server_node, extra_json "
            + "FROM core_ledger ORDER BY id";
    try (PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      long count = 0;
      while (rs.next()) {
        StringBuilder line = new StringBuilder();
        line.append('{')
            .append(quote("table"))
            .append(':')
            .append(quote("core_ledger"))
            .append(',')
            .append(quote("ts"))
            .append(':')
            .append(rs.getLong("ts_s"))
            .append(',')
            .append(quote("addon"))
            .append(':')
            .append(quote(rs.getString("addon_id")))
            .append(',')
            .append(quote("op"))
            .append(':')
            .append(quote(rs.getString("op")))
            .append(',')
            .append(quote("from"))
            .append(':')
            .append(quote(formatUuid(rs.getString("from_uuid"))))
            .append(',')
            .append(quote("to"))
            .append(':')
            .append(quote(formatUuid(rs.getString("to_uuid"))))
            .append(',')
            .append(quote("amount"))
            .append(':')
            .append(rs.getLong("amount"))
            .append(',')
            .append(quote("reason"))
            .append(':')
            .append(quote(rs.getString("reason")))
            .append(',')
            .append(quote("ok"))
            .append(':')
            .append(rs.getBoolean("ok"))
            .append(',')
            .append(quote("code"))
            .append(':')
            .append(quote(rs.getString("code")))
            .append(',')
            .append(quote("seq"))
            .append(':')
            .append(rs.getLong("seq"))
            .append(',')
            .append(quote("idemScope"))
            .append(':')
            .append(quote(rs.getString("idem_scope")))
            .append(',')
            .append(quote("idemKey"))
            .append(':')
            .append(quote(rs.getString("idem_key_hash")))
            .append(',')
            .append(quote("oldUnits"))
            .append(':')
            .append(rs.getLong("old_units"))
            .append(',')
            .append(quote("newUnits"))
            .append(':')
            .append(rs.getLong("new_units"))
            .append(',')
            .append(quote("serverNode"))
            .append(':')
            .append(quote(rs.getString("server_node")))
            .append(',')
            .append(quote("extra"))
            .append(':')
            .append(rs.getString("extra_json") == null ? "null" : rs.getString("extra_json"))
            .append("}\n");
        writer.write(line.toString());
        count++;
      }
      return count;
    }
  }

  private static void prune(Path dir, Config.Prune prune, Path newest) throws IOException {
    if (prune == null) {
      return;
    }
    try (var stream = Files.list(dir)) {
      var files =
          stream
              .filter(p -> !Files.isDirectory(p))
              .filter(
                  p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".jsonl") || name.endsWith(".jsonl.gz");
                  })
              .sorted()
              .toList();
      int keepMax = Math.max(0, prune.keepMax());
      int toRemove = Math.max(0, files.size() - keepMax);
      for (Path p : files) {
        if (p.equals(newest)) {
          continue;
        }
        if (toRemove > 0) {
          Files.deleteIfExists(p);
          deleteChecksum(dir, p);
          toRemove--;
          continue;
        }
        long keepDays = Math.max(0, prune.keepDays());
        if (keepDays == 0) {
          continue;
        }
        long ageDays =
            (Instant.now().toEpochMilli() - Files.getLastModifiedTime(p).toMillis()) / 86_400_000L;
        if (ageDays > keepDays) {
          Files.deleteIfExists(p);
          deleteChecksum(dir, p);
        }
      }
    }
  }

  private static void deleteChecksum(Path dir, Path file) throws IOException {
    Files.deleteIfExists(dir.resolve(file.getFileName().toString() + ".sha256"));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 missing", e);
    }
  }

  private static String formatUuid(String hex) {
    if (hex == null || hex.length() != 32) {
      return "";
    }
    long msb = new BigInteger(hex.substring(0, 16), 16).longValue();
    long lsb = new BigInteger(hex.substring(16), 16).longValue();
    return new UUID(msb, lsb).toString();
  }

  private static String quote(String in) {
    if (in == null) {
      return "\"\"";
    }
    return "\"" + in.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /** Result metadata for a backup run. */
  /**
   * Summary of an export run.
   *
   * @param file final archive path that was written
   * @param players number of player records exported
   * @param attributes number of attribute rows exported
   * @param ledger number of ledger entries exported
   */
  public record Result(Path file, long players, long attributes, long ledger) {}

  /** Simple OutputStream wrapper that updates a MessageDigest. */
  private static final class DigestOutput extends OutputStream {
    private final OutputStream delegate;
    private final MessageDigest digest;

    DigestOutput(OutputStream delegate, MessageDigest digest) {
      this.delegate = delegate;
      this.digest = digest;
    }

    @Override
    public void write(int b) throws IOException {
      delegate.write(b);
      digest.update((byte) b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      delegate.write(b, off, len);
      digest.update(b, off, len);
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
