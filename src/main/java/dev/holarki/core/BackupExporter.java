/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import com.google.gson.JsonPrimitive;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
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

/** Writes JSONL snapshot exports for {@code /holarki backup now}. */
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
    return exportAll(services, cfg, null, null);
  }

  /**
   * Runs a JSONL export with optional overrides for the destination directory or gzip toggle.
   *
   * @param services live services
   * @param cfg runtime configuration
   * @param overrideDir optional directory override; {@code null} to use config value
   * @param gzipOverride optional gzip flag override; {@code null} to use config value
   * @return metadata about the export
   * @throws IOException if writing to disk fails
   * @throws SQLException if database access fails
   */
  public static Result exportAll(
      Services services, Config cfg, Path overrideDir, Boolean gzipOverride)
      throws IOException, SQLException {
    Config.Backup backupCfg = cfg.jobs().backup();
    if (!backupCfg.enabled()) {
      throw new IllegalStateException("backup disabled in config");
    }

    Path outDir = overrideDir != null ? overrideDir : Path.of(backupCfg.outDir());
    Files.createDirectories(outDir);
    String stamp = TS.format(Instant.now());
    boolean gzip = gzipOverride != null ? gzipOverride.booleanValue() : backupCfg.gzip();
    String baseName = "holarki-" + stamp + ".jsonl" + (gzip ? ".gz" : "");
    Path outFile = outDir.resolve(baseName);

    MessageDigest sha = sha256();

    try (Connection c = services.database().borrowConnection()) {
      c.setAutoCommit(false);
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

      long players;
      long attrs;
      long seq;
      long ledger;
      try (DigestOutputStream digestStream =
              new DigestOutputStream(Files.newOutputStream(outFile), sha);
          OutputStream dataOut = gzip ? new GZIPOutputStream(digestStream) : digestStream;
          BufferedWriter writer =
              new BufferedWriter(new OutputStreamWriter(dataOut, StandardCharsets.UTF_8))) {

        writeHeader(writer, cfg);
        players = dumpPlayers(writer, c);
        attrs = dumpAttributes(writer, c);
        seq = dumpEventSequences(writer, c);
        ledger = dumpLedger(writer, c);
        writer.flush();
      }

      String checksumHex = HexFormat.of().formatHex(sha.digest());
      Files.writeString(outDir.resolve(baseName + ".sha256"), checksumHex);
      prune(outDir, backupCfg.prune(), outFile);
      c.rollback();
      return new Result(outFile, players, attrs, seq, ledger);
    }
  }

  private static void writeHeader(BufferedWriter writer, Config cfg) throws IOException {
    String json =
        "{"
            + requiredString("version")
            + ":"
            + requiredString("jsonl/v1")
            + ','
            + requiredString("generatedAt")
            + ":"
            + requiredString(Instant.now().toString())
            + ','
            + requiredString("defaultZone")
            + ":"
            + requiredString(cfg.time().display().defaultZone().getId())
            + ','
            + requiredString("schemaVersion")
            + ":"
            + Migrations.currentVersion()
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
        long balance = rs.getLong("balance_units");
        long createdAt = rs.getLong("created_at_s");
        long updatedAt = rs.getLong("updated_at_s");
        long seenRaw = rs.getLong("seen_at_s");
        boolean seenNull = rs.wasNull();
        String seenValue = seenNull ? "null" : Long.toString(seenRaw);

        String line =
            "{"
                + requiredString("table")
                + ":"
                + requiredString("players")
                + ','
                + requiredString("uuid")
                + ":"
                + requiredString(formatUuid(rs.getString("uuid")))
                + ','
                + requiredString("name")
                + ":"
                + requiredString(rs.getString("name"))
                + ','
                + requiredString("balance")
                + ":"
                + balance
                + ','
                + requiredString("createdAt")
                + ":"
                + createdAt
                + ','
                + requiredString("updatedAt")
                + ":"
                + updatedAt
                + ','
                + requiredString("seenAt")
                + ":"
                + seenValue
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
                + requiredString("table")
                + ":"
                + requiredString("player_attributes")
                + ','
                + requiredString("owner")
                + ":"
                + requiredString(formatUuid(rs.getString("owner_uuid")))
                + ','
                + requiredString("key")
                + ":"
                + requiredString(rs.getString("attr_key"))
                + ','
                + requiredString("value")
                + ":"
                + rs.getString("value_json")
                + ','
                + requiredString("createdAt")
                + ":"
                + rs.getLong("created_at_s")
                + ','
                + requiredString("updatedAt")
                + ":"
                + rs.getLong("updated_at_s")
                + "}\n";
        writer.write(line);
        count++;
      }
      return count;
    }
  }

  private static long dumpEventSequences(BufferedWriter writer, Connection c)
      throws SQLException, IOException {
    String sql = "SELECT HEX(uuid) AS uuid, seq FROM player_event_seq";
    try (PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      long count = 0;
      while (rs.next()) {
        StringBuilder line = new StringBuilder();
        line.append('{')
            .append(requiredString("table"))
            .append(':')
            .append(requiredString("player_event_seq"))
            .append(',')
            .append(requiredString("uuid"))
            .append(':')
            .append(requiredString(formatUuid(rs.getString("uuid"))))
            .append(',')
            .append(requiredString("seq"))
            .append(':')
            .append(rs.getLong("seq"))
            .append("}\n");
        writer.write(line.toString());
        count++;
      }
      return count;
    }
  }

  private static long dumpLedger(BufferedWriter writer, Connection c)
      throws SQLException, IOException {
    String sql =
        "SELECT ts_s, module_id, op, HEX(from_uuid) AS from_uuid, HEX(to_uuid) AS to_uuid, amount, reason, ok, code, seq, "
            + "idem_scope, HEX(idem_key_hash) AS idem_key_hash, old_units, new_units, server_node, extra_json "
            + "FROM core_ledger ORDER BY id";
    try (PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      long count = 0;
      while (rs.next()) {
        Object oldUnitsObj = rs.getObject("old_units");
        Object newUnitsObj = rs.getObject("new_units");
        Long oldUnits = oldUnitsObj == null ? null : ((Number) oldUnitsObj).longValue();
        Long newUnits = newUnitsObj == null ? null : ((Number) newUnitsObj).longValue();
        StringBuilder line = new StringBuilder();
        line.append('{')
            .append(requiredString("table"))
            .append(':')
            .append(requiredString("core_ledger"))
            .append(',')
            .append(requiredString("ts"))
            .append(':')
            .append(rs.getLong("ts_s"))
            .append(',')
            .append(requiredString("module"))
            .append(':')
            .append(requiredString(rs.getString("module_id")))
            .append(',')
            .append(requiredString("op"))
            .append(':')
            .append(requiredString(rs.getString("op")))
            .append(',')
            .append(requiredString("from"))
            .append(':')
            .append(requiredString(formatUuid(rs.getString("from_uuid"))))
            .append(',')
            .append(requiredString("to"))
            .append(':')
            .append(requiredString(formatUuid(rs.getString("to_uuid"))))
            .append(',')
            .append(requiredString("amount"))
            .append(':')
            .append(rs.getLong("amount"))
            .append(',')
            .append(requiredString("reason"))
            .append(':')
            .append(requiredString(rs.getString("reason")))
            .append(',')
            .append(requiredString("ok"))
            .append(':')
            .append(rs.getBoolean("ok"))
            .append(',')
            .append(requiredString("code"))
            .append(':')
            .append(optionalString(rs.getString("code")))
            .append(',')
            .append(requiredString("seq"))
            .append(':')
            .append(rs.getLong("seq"))
            .append(',')
            .append(requiredString("idemScope"))
            .append(':')
            .append(optionalString(rs.getString("idem_scope")))
            .append(',')
            .append(requiredString("idemKey"))
            .append(':')
            .append(optionalString(rs.getString("idem_key_hash")))
            .append(',')
            .append(requiredString("oldUnits"))
            .append(':')
            .append(oldUnits == null ? "null" : oldUnits.toString())
            .append(',')
            .append(requiredString("newUnits"))
            .append(':')
            .append(newUnits == null ? "null" : newUnits.toString())
            .append(',')
            .append(requiredString("serverNode"))
            .append(':')
            .append(optionalString(rs.getString("server_node")))
            .append(',')
            .append(requiredString("extra"))
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

  private static String requiredString(String in) {
    return new JsonPrimitive(in == null ? "" : in).toString();
  }

  private static String optionalString(String in) {
    return in == null ? "null" : requiredString(in);
  }

  /**
   * Summary of an export run.
   *
   * @param file final archive path that was written
   * @param players number of player records exported
   * @param attributes number of attribute rows exported
   * @param eventSeq number of event sequence rows exported
   * @param ledger number of ledger entries exported
   */
  public record Result(Path file, long players, long attributes, long eventSeq, long ledger) {}

}
