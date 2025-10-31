/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import com.google.gson.stream.JsonWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes JSONL snapshot exports for {@code /holarki backup now}. */
public final class BackupExporter {
  private static final Logger LOG = LoggerFactory.getLogger("holarki");
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
      LOG.info("(holarki) export skipped: backup job disabled in config");
      return null;
    }

    Path outDir = overrideDir != null ? overrideDir : Path.of(backupCfg.outDir());
    Files.createDirectories(outDir);
    String stamp = TS.format(Instant.now());
    boolean gzip = gzipOverride != null ? gzipOverride.booleanValue() : backupCfg.gzip();
    Path outFile;
    String baseName;

    try (Connection c = services.database().borrowConnection()) {
      c.setAutoCommit(false);
      c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

      while (true) {
        String uniqueSuffix = UUID.randomUUID().toString().replace("-", "");
        baseName = "holarki-" + stamp + "-" + uniqueSuffix + ".jsonl" + (gzip ? ".gz" : "");
        outFile = outDir.resolve(baseName);
        MessageDigest sha = sha256();
        long players = 0L;
        long attrs = 0L;
        long seq = 0L;
        long ledger = 0L;
        try (DigestOutputStream digestStream =
                new DigestOutputStream(
                    Files.newOutputStream(outFile, StandardOpenOption.CREATE_NEW), sha);
            OutputStream dataOut = gzip ? new GZIPOutputStream(digestStream) : digestStream;
            BufferedWriter writer =
                new BufferedWriter(new OutputStreamWriter(dataOut, StandardCharsets.UTF_8))) {

          writeHeader(writer, cfg);
          players = dumpPlayers(writer, c);
          attrs = dumpAttributes(writer, c);
          seq = dumpEventSequences(writer, c);
          ledger = dumpLedger(writer, c);
          writer.flush();

          String checksumHex = HexFormat.of().formatHex(sha.digest());
          Files.writeString(outDir.resolve(baseName + ".sha256"), checksumHex);
          prune(outDir, backupCfg.prune(), outFile);
          c.rollback();
          return new Result(outFile, players, attrs, seq, ledger);
        } catch (FileAlreadyExistsException exists) {
          // Retry with a new suffix if a file collides between existence check and creation.
          continue;
        }
      }
    }
  }

  private static void writeHeader(BufferedWriter writer, Config cfg) throws IOException {
    writeJsonLine(
        writer,
        json -> {
          json.name("version").value("jsonl/v1");
          json.name("generatedAt").value(Instant.now().toString());
          json.name("defaultZone").value(cfg.time().display().defaultZone().getId());
          json.name("schemaVersion").value(Migrations.currentVersion());
        });
  }

  private static long dumpPlayers(BufferedWriter writer, Connection c)
      throws SQLException, IOException {
    String sql =
        "SELECT HEX(uuid) AS uuid, name, balance_units, created_at_s, updated_at_s, seen_at_s FROM players";
    try (PreparedStatement ps = c.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      long count = 0;
      while (rs.next()) {
        String uuid = formatUuid(rs.getString("uuid"));
        String name = rs.getString("name");
        long balance = rs.getLong("balance_units");
        long createdAt = rs.getLong("created_at_s");
        long updatedAt = rs.getLong("updated_at_s");
        long seenRaw = rs.getLong("seen_at_s");
        boolean seenNull = rs.wasNull();

        writeJsonLine(
            writer,
            json -> {
              json.name("table").value("players");
              json.name("uuid").value(uuid);
              json.name("name").value(name);
              json.name("balance").value(balance);
              json.name("createdAt").value(createdAt);
              json.name("updatedAt").value(updatedAt);
              json.name("seenAt");
              if (seenNull) {
                json.nullValue();
              } else {
                json.value(seenRaw);
              }
            });
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
        String owner = formatUuid(rs.getString("owner_uuid"));
        String key = rs.getString("attr_key");
        String rawJson = rs.getString("value_json");
        long createdAt = rs.getLong("created_at_s");
        long updatedAt = rs.getLong("updated_at_s");
        writeJsonLine(
            writer,
            json -> {
              json.name("table").value("player_attributes");
              json.name("owner").value(owner);
              json.name("key").value(key);
              json.name("value");
              writeJsonValue(json, rawJson);
              json.name("createdAt").value(createdAt);
              json.name("updatedAt").value(updatedAt);
            });
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
        String uuid = formatUuid(rs.getString("uuid"));
        long seqValue = rs.getLong("seq");
        writeJsonLine(
            writer,
            json -> {
              json.name("table").value("player_event_seq");
              json.name("uuid").value(uuid);
              json.name("seq").value(seqValue);
            });
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
        long ts = rs.getLong("ts_s");
        String module = rs.getString("module_id");
        String op = rs.getString("op");
        String from = formatUuid(rs.getString("from_uuid"));
        String to = formatUuid(rs.getString("to_uuid"));
        long amount = rs.getLong("amount");
        String reason = rs.getString("reason");
        boolean ok = rs.getBoolean("ok");
        String code = rs.getString("code");
        long seqValue = rs.getLong("seq");
        String idemScope = rs.getString("idem_scope");
        String idemKey = rs.getString("idem_key_hash");
        Object oldUnitsObj = rs.getObject("old_units");
        Object newUnitsObj = rs.getObject("new_units");
        Long oldUnits = oldUnitsObj == null ? null : ((Number) oldUnitsObj).longValue();
        Long newUnits = newUnitsObj == null ? null : ((Number) newUnitsObj).longValue();
        String serverNode = rs.getString("server_node");
        String extra = rs.getString("extra_json");
        writeJsonLine(
            writer,
            json -> {
              json.name("table").value("core_ledger");
              json.name("ts").value(ts);
              json.name("module").value(module);
              json.name("op").value(op);
              json.name("from").value(from);
              json.name("to").value(to);
              json.name("amount").value(amount);
              json.name("reason").value(reason);
              json.name("ok").value(ok);

              json.name("code");
              writeOptionalString(json, code);

              json.name("seq").value(seqValue);

              json.name("idemScope");
              writeOptionalString(json, idemScope);

              json.name("idemKey");
              writeOptionalString(json, idemKey);

              json.name("oldUnits");
              writeOptionalLong(json, oldUnits);

              json.name("newUnits");
              writeOptionalLong(json, newUnits);

              json.name("serverNode");
              writeOptionalString(json, serverNode);

              json.name("extra");
              writeJsonValue(json, extra);
            });
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

  private static void writeJsonLine(BufferedWriter writer, RowWriter rowWriter) throws IOException {
    StringWriter buffer = new StringWriter();
    try (JsonWriter jsonWriter = new JsonWriter(buffer)) {
      jsonWriter.setSerializeNulls(true);
      jsonWriter.beginObject();
      rowWriter.write(jsonWriter);
      jsonWriter.endObject();
    }
    writer.write(buffer.toString());
    writer.write('\n');
  }

  private static void writeJsonValue(JsonWriter writer, String rawJson) throws IOException {
    if (rawJson == null) {
      writer.nullValue();
    } else {
      writer.jsonValue(rawJson);
    }
  }

  private static void writeOptionalString(JsonWriter writer, String value) throws IOException {
    if (value == null) {
      writer.nullValue();
    } else {
      writer.value(value);
    }
  }

  private static void writeOptionalLong(JsonWriter writer, Long value) throws IOException {
    if (value == null) {
      writer.nullValue();
    } else {
      writer.value(value.longValue());
    }
  }

  @FunctionalInterface
  private interface RowWriter {
    void write(JsonWriter writer) throws IOException;
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
