/* MinCore © 2025 — MIT */
package dev.mincore.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.mincore.api.ErrorCode;
import dev.mincore.util.Uuids;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Imports JSONL backups created by {@link BackupExporter}. */
public final class BackupImporter {
  private static final Logger LOG = LoggerFactory.getLogger("mincore");

  private BackupImporter() {}

  /** Restore mode for the importer. */
  public enum Mode {
    /** Replace existing data. */
    FRESH,
    /** Merge with existing data. */
    MERGE
  }

  /** Strategy for {@link Mode#FRESH} restores. */
  public enum FreshStrategy {
    /** Run inside a transaction; roll back on failure. */
    ATOMIC,
    /** Use staging tables to load data before swapping into place. */
    STAGING
  }

  /**
   * Summary of an import run.
   *
   * @param source snapshot file processed
   * @param players number of player rows imported
   * @param attributes number of attribute rows imported
   * @param eventSeq number of event sequence rows imported
   * @param ledger number of ledger rows imported
   */
  public record Result(Path source, long players, long attributes, long eventSeq, long ledger) {}

  private record Header(String version, int schemaVersion, String defaultZone) {}

  /**
   * Restores a snapshot created by {@link BackupExporter}.
   *
   * @param services live services
   * @param source directory or file containing the export
   * @param mode restore mode
   * @param strategy strategy for {@link Mode#FRESH}
   * @param overwrite if {@code true}, merge mode replaces conflicting ledger rows
   * @param skipForeignKeys if {@code true}, temporarily disable foreign key checks during import
   * @return summary of imported rows
   * @throws IOException if reading the snapshot fails
   * @throws SQLException if database access fails
   */
  public static Result restore(
      Services services,
      Path source,
      Mode mode,
      FreshStrategy strategy,
      boolean overwrite,
      boolean skipForeignKeys)
      throws IOException, SQLException {
    Path file = resolveInput(source);
    Header header = peekHeader(file);
    return switch (Objects.requireNonNull(mode, "mode")) {
      case FRESH -> restoreFresh(services, file, strategy, skipForeignKeys, header);
      case MERGE -> restoreMerge(services, file, overwrite, skipForeignKeys, header);
    };
  }

  private static Result restoreFresh(
      Services services, Path file, FreshStrategy strategy, boolean skipForeignKeys, Header header)
      throws IOException, SQLException {
    FreshStrategy effective = strategy != null ? strategy : FreshStrategy.ATOMIC;
    if (effective == FreshStrategy.STAGING) {
      LOG.info("(mincore) restore fresh --staging requested; using staging tables");
    }

    try (Connection c = services.database().borrowConnection()) {
      boolean originalAutoCommit = c.getAutoCommit();
      c.setAutoCommit(false);
      boolean schemaRecorded = false;
      try {
        schemaRecorded = ensureCompatibleSchema(c, header);
        if (skipForeignKeys) {
          LOG.warn(
              "(mincore) restore running with FOREIGN_KEY_CHECKS=0; data integrity checks are skipped");
          setForeignKeyChecks(c, false);
        }
        Counters counters;
        if (effective == FreshStrategy.STAGING) {
          LOG.info("(mincore) restore fresh using staging tables");
          StagingTables staging = StagingTables.create(c);
          try {
            counters = importSnapshot(file, new StagingHandler(c, staging));
            staging.swapIntoPrimary(c);
          } finally {
            staging.cleanupQuietly(c);
          }
        } else {
          clearTables(c);
          counters = importSnapshot(file, new FreshHandler(c));
        }
        c.commit();
        if (schemaRecorded) {
          LOG.info("(mincore) recorded schema version {} during restore", header.schemaVersion());
        }
        return new Result(
            file, counters.players, counters.attributes, counters.eventSeq, counters.ledger);
      } catch (Exception e) {
        try {
          c.rollback();
        } catch (SQLException rollback) {
          logSqlWarning("restore.rollback", rollback);
        }
        if (e instanceof IOException io) {
          throw io;
        }
        if (e instanceof SQLException sql) {
          throw sql;
        }
        throw new IOException("restore failed", e);
      } finally {
        if (skipForeignKeys) {
          try {
            setForeignKeyChecks(c, true);
          } catch (SQLException enable) {
            logSqlWarning("restore.fk.enable", enable);
          }
        }
        c.setAutoCommit(originalAutoCommit);
      }
    }
  }

  private static Result restoreMerge(
      Services services, Path file, boolean overwrite, boolean skipForeignKeys, Header header)
      throws IOException, SQLException {
    try (Connection c = services.database().borrowConnection()) {
      boolean originalAutoCommit = c.getAutoCommit();
      c.setAutoCommit(false);
      MergeHandler handler = new MergeHandler(c, overwrite);
      boolean schemaRecorded = false;
      try {
        schemaRecorded = ensureCompatibleSchema(c, header);
        if (skipForeignKeys) {
          LOG.warn(
              "(mincore) restore running with FOREIGN_KEY_CHECKS=0; data integrity checks are skipped");
          setForeignKeyChecks(c, false);
        }
        Counters counters = importSnapshot(file, handler);
        c.commit();
        if (schemaRecorded) {
          LOG.info("(mincore) recorded schema version {} during restore", header.schemaVersion());
        }
        return new Result(
            file, counters.players, counters.attributes, counters.eventSeq, counters.ledger);
      } catch (Exception e) {
        try {
          c.rollback();
        } catch (SQLException rollback) {
          logSqlWarning("restore.merge.rollback", rollback);
        }
        handler.closeQuietly();
        if (e instanceof IOException io) {
          throw io;
        }
        if (e instanceof SQLException sql) {
          throw sql;
        }
        throw new IOException("restore failed", e);
      } finally {
        handler.closeQuietly();
        if (skipForeignKeys) {
          try {
            setForeignKeyChecks(c, true);
          } catch (SQLException enable) {
            logSqlWarning("restore.merge.fk.enable", enable);
          }
        }
        c.setAutoCommit(originalAutoCommit);
      }
    }
  }

  private static void clearTables(Connection c) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.executeUpdate("DELETE FROM core_ledger");
      st.executeUpdate("DELETE FROM player_attributes");
      st.executeUpdate("DELETE FROM player_event_seq");
      st.executeUpdate("DELETE FROM players");
    }
  }

  private static Counters importSnapshot(Path file, SnapshotHandler handler)
      throws IOException, SQLException {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(open(file), StandardCharsets.UTF_8))) {
      String line;
      boolean headerSeen = false;
      Counters counters = new Counters();
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        JsonObject obj = parse(line);
        if (!headerSeen && !obj.has("table")) {
          validateHeader(obj);
          headerSeen = true;
          continue;
        }
        if (!obj.has("table")) {
          continue;
        }
        String table = obj.get("table").getAsString();
        switch (table) {
          case "players" -> {
            handler.handlePlayer(obj);
            counters.players++;
          }
          case "player_attributes" -> {
            handler.handleAttribute(obj);
            counters.attributes++;
          }
          case "player_event_seq" -> {
            handler.handleEventSeq(obj);
            counters.eventSeq++;
          }
          case "core_ledger" -> {
            handler.handleLedger(obj);
            counters.ledger++;
          }
          default -> LOG.debug("(mincore) ignoring snapshot line for table {}", table);
        }
      }
      return counters;
    } finally {
      handler.closeQuietly();
    }
  }

  private static InputStream open(Path file) throws IOException {
    InputStream in = Files.newInputStream(file);
    if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")) {
      return new GZIPInputStream(in);
    }
    return in;
  }

  private static Path resolveInput(Path source) throws IOException {
    if (Files.isDirectory(source)) {
      List<Path> candidates = new ArrayList<>();
      try (var stream = Files.list(source)) {
        stream
            .filter(p -> !Files.isDirectory(p))
            .filter(
                p -> {
                  String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                  return name.endsWith(".jsonl") || name.endsWith(".jsonl.gz");
                })
            .forEach(candidates::add);
      }
      if (candidates.isEmpty()) {
        throw new IOException("no snapshot found in " + source);
      }
      candidates.sort(Comparator.comparingLong(BackupImporter::lastModified).reversed());
      return candidates.get(0);
    }
    if (!Files.exists(source)) {
      throw new IOException("snapshot not found: " + source);
    }
    return source;
  }

  private static Header peekHeader(Path file) throws IOException {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(open(file), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        JsonObject obj = parse(line);
        if (!obj.has("table")) {
          return validateHeader(obj);
        }
        break;
      }
    }
    throw new IOException("snapshot missing header");
  }

  private static boolean ensureCompatibleSchema(Connection c, Header header)
      throws SQLException, IOException {
    int runtimeVersion = Migrations.currentVersion();
    if (header.schemaVersion() != runtimeVersion) {
      throw new IOException(
          "snapshot schema version "
              + header.schemaVersion()
              + " incompatible with runtime version "
              + runtimeVersion);
    }
    boolean recorded = ensureSchemaVersionRecorded(c, runtimeVersion);
    if (recorded) {
      LOG.debug("(mincore) core_schema_version table was empty; recorded runtime version");
    }
    return recorded;
  }

  private static boolean ensureSchemaVersionRecorded(Connection c, int expected)
      throws SQLException, IOException {
    int current = readSchemaVersion(c);
    if (current == 0) {
      writeSchemaVersion(c, expected);
      return true;
    }
    if (current != expected) {
      throw new IOException(
          "database schema version " + current + " incompatible with runtime version " + expected);
    }
    return false;
  }

  private static int readSchemaVersion(Connection c) throws SQLException {
    final String sql = "SELECT MAX(version) FROM core_schema_version";
    try (Statement st = c.createStatement()) {
      try (ResultSet rs = st.executeQuery(sql)) {
        if (rs.next()) {
          return rs.getInt(1);
        }
      }
    } catch (SQLException e) {
      String state = e.getSQLState();
      if (state != null && state.startsWith("42")) {
        return 0;
      }
      throw e;
    }
    return 0;
  }

  private static void writeSchemaVersion(Connection c, int version) throws SQLException {
    final String sql =
        "INSERT INTO core_schema_version(version, applied_at_s) VALUES(?, ?) "
            + "ON DUPLICATE KEY UPDATE applied_at_s=VALUES(applied_at_s)";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setInt(1, version);
      ps.setLong(2, Instant.now().getEpochSecond());
      ps.executeUpdate();
    }
  }

  private static void setForeignKeyChecks(Connection c, boolean enabled) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute("SET FOREIGN_KEY_CHECKS=" + (enabled ? "1" : "0"));
    }
  }

  private static long lastModified(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException e) {
      return 0L;
    }
  }

  private static JsonObject parse(String line) throws IOException {
    try {
      JsonElement element = JsonParser.parseString(line);
      if (!element.isJsonObject()) {
        throw new IOException("malformed snapshot row");
      }
      return element.getAsJsonObject();
    } catch (JsonParseException e) {
      throw new IOException("invalid snapshot json", e);
    }
  }

  private static Header validateHeader(JsonObject obj) throws IOException {
    String version = stringOrNull(obj, "version");
    if (!"jsonl/v1".equals(version)) {
      throw new IOException("unsupported snapshot version: " + version);
    }
    if (!obj.has("schemaVersion") || obj.get("schemaVersion").isJsonNull()) {
      throw new IOException("snapshot missing schemaVersion");
    }
    int schemaVersion;
    try {
      schemaVersion = obj.get("schemaVersion").getAsInt();
    } catch (RuntimeException e) {
      throw new IOException("invalid schemaVersion", e);
    }
    String defaultZone = stringOrNull(obj, "defaultZone");
    return new Header(version, schemaVersion, defaultZone);
  }

  private static byte[] uuidToBytes(String value) {
    return Uuids.fromString(value);
  }

  private static byte[] hexToBytes(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return HexFormat.of().parseHex(value.replace("-", ""));
  }

  private static String stringOrNull(JsonObject obj, String key) {
    if (!obj.has(key)) {
      return null;
    }
    JsonElement element = obj.get(key);
    if (element == null || element.isJsonNull()) {
      return null;
    }
    String value = element.getAsString();
    return value.isEmpty() ? null : value;
  }

  private static String moduleField(JsonObject obj) throws SQLException {
    String module = stringOrNull(obj, "module");
    if (module == null) {
      throw new SQLException("snapshot missing ledger module identifier");
    }
    return module;
  }

  private static long longOrZero(JsonObject obj, String key) {
    if (!obj.has(key) || obj.get(key).isJsonNull()) {
      return 0L;
    }
    return obj.get(key).getAsLong();
  }

  private static Boolean boolOrNull(JsonObject obj, String key) {
    if (!obj.has(key) || obj.get(key).isJsonNull()) {
      return null;
    }
    return obj.get(key).getAsBoolean();
  }

  private static String ident(String name) {
    return "`" + name + "`";
  }

  private static void copy(Connection c, String source, String target, String columns)
      throws SQLException {
    String sql =
        "INSERT INTO "
            + ident(target)
            + "("
            + columns
            + ") SELECT "
            + columns
            + " FROM "
            + ident(source);
    try (Statement st = c.createStatement()) {
      st.executeUpdate(sql);
    }
  }

  private interface SnapshotHandler {
    void handlePlayer(JsonObject obj) throws SQLException;

    void handleAttribute(JsonObject obj) throws SQLException;

    void handleLedger(JsonObject obj) throws SQLException;

    default void handleEventSeq(JsonObject obj) throws SQLException {}

    default void closeQuietly() {}
  }

  private static final class Counters {
    long players;
    long attributes;
    long eventSeq;
    long ledger;
  }

  private static final class FreshHandler implements SnapshotHandler {
    private final PreparedStatement insertPlayer;
    private final PreparedStatement insertAttr;
    private final PreparedStatement insertLedger;
    private final PreparedStatement insertSeq;

    FreshHandler(Connection c) throws SQLException {
      this.insertPlayer =
          c.prepareStatement(
              "INSERT INTO players(uuid,name,balance_units,created_at_s,updated_at_s,seen_at_s) "
                  + "VALUES(?,?,?,?,?,?)");
      this.insertAttr =
          c.prepareStatement(
              "INSERT INTO player_attributes("
                  + "owner_uuid,attr_key,value_json,created_at_s,updated_at_s) VALUES(?,?,?,?,?)");
      this.insertLedger =
          c.prepareStatement(
              "INSERT INTO core_ledger("
                  + "ts_s,module_id,op,from_uuid,to_uuid,amount,reason,ok,code,seq,"
                  + "idem_scope,idem_key_hash,old_units,new_units,server_node,extra_json) "
                  + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
      this.insertSeq = c.prepareStatement("INSERT INTO player_event_seq(uuid,seq) VALUES(?,?)");
    }

    @Override
    public void handlePlayer(JsonObject obj) throws SQLException {
      byte[] uuid = uuidToBytes(stringOrNull(obj, "uuid"));
      if (uuid == null) {
        throw new SQLException("missing player uuid in snapshot");
      }
      insertPlayer.setBytes(1, uuid);
      insertPlayer.setString(2, Objects.requireNonNullElse(stringOrNull(obj, "name"), ""));
      insertPlayer.setLong(3, longOrZero(obj, "balance"));
      insertPlayer.setLong(4, longOrZero(obj, "createdAt"));
      insertPlayer.setLong(5, longOrZero(obj, "updatedAt"));
      long seen = longOrZero(obj, "seenAt");
      if (seen <= 0) {
        insertPlayer.setNull(6, Types.BIGINT);
      } else {
        insertPlayer.setLong(6, seen);
      }
      insertPlayer.executeUpdate();
    }

    @Override
    public void handleAttribute(JsonObject obj) throws SQLException {
      byte[] owner = uuidToBytes(stringOrNull(obj, "owner"));
      if (owner == null) {
        throw new SQLException("missing attribute owner uuid in snapshot");
      }
      insertAttr.setBytes(1, owner);
      insertAttr.setString(2, Objects.requireNonNullElse(stringOrNull(obj, "key"), ""));
      String value = obj.get("value").toString();
      insertAttr.setString(3, value);
      insertAttr.setLong(4, longOrZero(obj, "createdAt"));
      insertAttr.setLong(5, longOrZero(obj, "updatedAt"));
      insertAttr.executeUpdate();
    }

    @Override
    public void handleLedger(JsonObject obj) throws SQLException {
      insertLedger.setLong(1, longOrZero(obj, "ts"));
      String module = moduleField(obj);
      insertLedger.setString(2, module);
      insertLedger.setString(3, Objects.requireNonNullElse(stringOrNull(obj, "op"), ""));
      byte[] from = uuidToBytes(stringOrNull(obj, "from"));
      if (from == null) {
        insertLedger.setNull(4, Types.BINARY);
      } else {
        insertLedger.setBytes(4, from);
      }
      byte[] to = uuidToBytes(stringOrNull(obj, "to"));
      if (to == null) {
        insertLedger.setNull(5, Types.BINARY);
      } else {
        insertLedger.setBytes(5, to);
      }
      insertLedger.setLong(6, longOrZero(obj, "amount"));
      insertLedger.setString(7, Objects.requireNonNullElse(stringOrNull(obj, "reason"), ""));
      Boolean ok = boolOrNull(obj, "ok");
      insertLedger.setBoolean(8, ok != null ? ok : false);
      String code = stringOrNull(obj, "code");
      if (code == null) {
        insertLedger.setNull(9, Types.VARCHAR);
      } else {
        insertLedger.setString(9, code);
      }
      insertLedger.setLong(10, longOrZero(obj, "seq"));
      String scope = stringOrNull(obj, "idemScope");
      if (scope == null) {
        insertLedger.setNull(11, Types.VARCHAR);
      } else {
        insertLedger.setString(11, scope);
      }
      byte[] key = hexToBytes(stringOrNull(obj, "idemKey"));
      if (key == null) {
        insertLedger.setNull(12, Types.BINARY);
      } else {
        insertLedger.setBytes(12, key);
      }
      long oldUnits = longOrZero(obj, "oldUnits");
      if (obj.has("oldUnits") && !obj.get("oldUnits").isJsonNull()) {
        insertLedger.setLong(13, oldUnits);
      } else {
        insertLedger.setNull(13, Types.BIGINT);
      }
      long newUnits = longOrZero(obj, "newUnits");
      if (obj.has("newUnits") && !obj.get("newUnits").isJsonNull()) {
        insertLedger.setLong(14, newUnits);
      } else {
        insertLedger.setNull(14, Types.BIGINT);
      }
      String node = stringOrNull(obj, "serverNode");
      if (node == null) {
        insertLedger.setNull(15, Types.VARCHAR);
      } else {
        insertLedger.setString(15, node);
      }
      JsonElement extra = obj.get("extra");
      if (extra == null || extra.isJsonNull()) {
        insertLedger.setNull(16, Types.LONGVARCHAR);
      } else {
        insertLedger.setString(16, extra.toString());
      }
      insertLedger.executeUpdate();
    }

    @Override
    public void handleEventSeq(JsonObject obj) throws SQLException {
      byte[] uuid = uuidToBytes(stringOrNull(obj, "uuid"));
      if (uuid == null) {
        throw new SQLException("missing player_event_seq uuid in snapshot");
      }
      insertSeq.setBytes(1, uuid);
      insertSeq.setLong(2, longOrZero(obj, "seq"));
      insertSeq.executeUpdate();
    }

    @Override
    public void closeQuietly() {
      close(insertLedger);
      close(insertAttr);
      close(insertPlayer);
      close(insertSeq);
    }
  }

  private static final class StagingTables {
    private final String players;
    private final String attributes;
    private final String eventSeq;
    private final String ledger;

    private StagingTables(String players, String attributes, String eventSeq, String ledger) {
      this.players = players;
      this.attributes = attributes;
      this.eventSeq = eventSeq;
      this.ledger = ledger;
    }

    static StagingTables create(Connection c) throws SQLException {
      String suffix = Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 16);
      if (suffix.isEmpty()) {
        suffix = "0";
      }
      StagingTables tables =
          new StagingTables(
              "players_restore_" + suffix,
              "player_attributes_restore_" + suffix,
              "player_event_seq_restore_" + suffix,
              "core_ledger_restore_" + suffix);
      tables.createAll(c);
      return tables;
    }

    private void createAll(Connection c) throws SQLException {
      createLike(c, players, "players");
      createLike(c, attributes, "player_attributes");
      createLike(c, eventSeq, "player_event_seq");
      createLike(c, ledger, "core_ledger");
    }

    private static void createLike(Connection c, String staging, String base) throws SQLException {
      try (Statement st = c.createStatement()) {
        st.execute("DROP TABLE IF EXISTS " + ident(staging));
        st.execute("CREATE TABLE " + ident(staging) + " LIKE " + ident(base));
      }
    }

    void swapIntoPrimary(Connection c) throws SQLException {
      try (Statement st = c.createStatement()) {
        st.executeUpdate("DELETE FROM core_ledger");
        st.executeUpdate("DELETE FROM player_attributes");
        st.executeUpdate("DELETE FROM player_event_seq");
        st.executeUpdate("DELETE FROM players");
      }
      copy(c, players, "players", "uuid,name,balance_units,created_at_s,updated_at_s,seen_at_s");
      copy(c, eventSeq, "player_event_seq", "uuid,seq");
      copy(
          c,
          attributes,
          "player_attributes",
          "owner_uuid,attr_key,value_json,created_at_s,updated_at_s");
      copy(
          c,
          ledger,
          "core_ledger",
          "ts_s,module_id,op,from_uuid,to_uuid,amount,reason,ok,code,seq,idem_scope,idem_key_hash,old_units,new_units,server_node,extra_json");
    }

    void cleanupQuietly(Connection c) {
      dropQuietly(c, ledger);
      dropQuietly(c, attributes);
      dropQuietly(c, eventSeq);
      dropQuietly(c, players);
    }

    private static void dropQuietly(Connection c, String table) {
      try (Statement st = c.createStatement()) {
        st.execute("DROP TABLE IF EXISTS " + ident(table));
      } catch (SQLException e) {
        logSqlWarning("restore.staging.drop", e);
      }
    }

    String players() {
      return players;
    }

    String attributes() {
      return attributes;
    }

    String eventSeq() {
      return eventSeq;
    }

    String ledger() {
      return ledger;
    }
  }

  private static final class StagingHandler implements SnapshotHandler {
    private final PreparedStatement insertPlayer;
    private final PreparedStatement insertAttr;
    private final PreparedStatement insertLedger;
    private final PreparedStatement insertSeq;

    StagingHandler(Connection c, StagingTables tables) throws SQLException {
      this.insertPlayer =
          c.prepareStatement(
              "INSERT INTO "
                  + ident(tables.players())
                  + "(uuid,name,balance_units,created_at_s,updated_at_s,seen_at_s) VALUES(?,?,?,?,?,?)");
      this.insertAttr =
          c.prepareStatement(
              "INSERT INTO "
                  + ident(tables.attributes())
                  + "(owner_uuid,attr_key,value_json,created_at_s,updated_at_s) VALUES(?,?,?,?,?)");
      this.insertLedger =
          c.prepareStatement(
              "INSERT INTO "
                  + ident(tables.ledger())
                  + "(ts_s,module_id,op,from_uuid,to_uuid,amount,reason,ok,code,seq,"
                  + "idem_scope,idem_key_hash,old_units,new_units,server_node,extra_json) "
                  + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
      this.insertSeq =
          c.prepareStatement("INSERT INTO " + ident(tables.eventSeq()) + "(uuid,seq) VALUES(?,?)");
    }

    @Override
    public void handlePlayer(JsonObject obj) throws SQLException {
      byte[] uuid = uuidToBytes(stringOrNull(obj, "uuid"));
      if (uuid == null) {
        throw new SQLException("missing player uuid in snapshot");
      }
      insertPlayer.setBytes(1, uuid);
      insertPlayer.setString(2, Objects.requireNonNullElse(stringOrNull(obj, "name"), ""));
      insertPlayer.setLong(3, longOrZero(obj, "balance"));
      insertPlayer.setLong(4, longOrZero(obj, "createdAt"));
      insertPlayer.setLong(5, longOrZero(obj, "updatedAt"));
      long seen = longOrZero(obj, "seenAt");
      if (seen <= 0) {
        insertPlayer.setNull(6, Types.BIGINT);
      } else {
        insertPlayer.setLong(6, seen);
      }
      insertPlayer.executeUpdate();
    }

    @Override
    public void handleAttribute(JsonObject obj) throws SQLException {
      byte[] owner = uuidToBytes(stringOrNull(obj, "owner"));
      if (owner == null) {
        throw new SQLException("missing attribute owner uuid in snapshot");
      }
      insertAttr.setBytes(1, owner);
      insertAttr.setString(2, Objects.requireNonNullElse(stringOrNull(obj, "key"), ""));
      String value = obj.get("value").toString();
      insertAttr.setString(3, value);
      insertAttr.setLong(4, longOrZero(obj, "createdAt"));
      insertAttr.setLong(5, longOrZero(obj, "updatedAt"));
      insertAttr.executeUpdate();
    }

    @Override
    public void handleLedger(JsonObject obj) throws SQLException {
      insertLedger.setLong(1, longOrZero(obj, "ts"));
      String module = moduleField(obj);
      insertLedger.setString(2, module);
      insertLedger.setString(3, Objects.requireNonNullElse(stringOrNull(obj, "op"), ""));
      byte[] from = uuidToBytes(stringOrNull(obj, "from"));
      if (from == null) {
        insertLedger.setNull(4, Types.BINARY);
      } else {
        insertLedger.setBytes(4, from);
      }
      byte[] to = uuidToBytes(stringOrNull(obj, "to"));
      if (to == null) {
        insertLedger.setNull(5, Types.BINARY);
      } else {
        insertLedger.setBytes(5, to);
      }
      insertLedger.setLong(6, longOrZero(obj, "amount"));
      insertLedger.setString(7, Objects.requireNonNullElse(stringOrNull(obj, "reason"), ""));
      Boolean ok = boolOrNull(obj, "ok");
      insertLedger.setBoolean(8, ok != null ? ok : false);
      String code = stringOrNull(obj, "code");
      if (code == null) {
        insertLedger.setNull(9, Types.VARCHAR);
      } else {
        insertLedger.setString(9, code);
      }
      insertLedger.setLong(10, longOrZero(obj, "seq"));
      String scope = stringOrNull(obj, "idemScope");
      if (scope == null) {
        insertLedger.setNull(11, Types.VARCHAR);
      } else {
        insertLedger.setString(11, scope);
      }
      byte[] key = hexToBytes(stringOrNull(obj, "idemKey"));
      if (key == null) {
        insertLedger.setNull(12, Types.BINARY);
      } else {
        insertLedger.setBytes(12, key);
      }
      long oldUnits = longOrZero(obj, "oldUnits");
      if (obj.has("oldUnits") && !obj.get("oldUnits").isJsonNull()) {
        insertLedger.setLong(13, oldUnits);
      } else {
        insertLedger.setNull(13, Types.BIGINT);
      }
      long newUnits = longOrZero(obj, "newUnits");
      if (obj.has("newUnits") && !obj.get("newUnits").isJsonNull()) {
        insertLedger.setLong(14, newUnits);
      } else {
        insertLedger.setNull(14, Types.BIGINT);
      }
      String node = stringOrNull(obj, "serverNode");
      if (node == null) {
        insertLedger.setNull(15, Types.VARCHAR);
      } else {
        insertLedger.setString(15, node);
      }
      JsonElement extra = obj.get("extra");
      if (extra == null || extra.isJsonNull()) {
        insertLedger.setNull(16, Types.LONGVARCHAR);
      } else {
        insertLedger.setString(16, extra.toString());
      }
      insertLedger.executeUpdate();
    }

    @Override
    public void handleEventSeq(JsonObject obj) throws SQLException {
      byte[] uuid = uuidToBytes(stringOrNull(obj, "uuid"));
      if (uuid == null) {
        throw new SQLException("missing player_event_seq uuid in snapshot");
      }
      insertSeq.setBytes(1, uuid);
      insertSeq.setLong(2, longOrZero(obj, "seq"));
      insertSeq.executeUpdate();
    }

    @Override
    public void closeQuietly() {
      close(insertLedger);
      close(insertAttr);
      close(insertPlayer);
      close(insertSeq);
    }
  }

  private static final class MergeHandler implements SnapshotHandler {
    private final PreparedStatement upsertPlayer;
    private final PreparedStatement upsertAttr;
    private final PreparedStatement insertLedger;
    private final PreparedStatement existsLedger;
    private final PreparedStatement deleteLedger;
    private final PreparedStatement upsertSeq;
    private final boolean overwrite;

    MergeHandler(Connection c, boolean overwrite) throws SQLException {
      this.overwrite = overwrite;
      this.upsertPlayer =
          c.prepareStatement(
              "INSERT INTO players(uuid,name,balance_units,created_at_s,updated_at_s,seen_at_s) "
                  + "VALUES(?,?,?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE name=VALUES(name), balance_units=VALUES(balance_units), "
                  + "updated_at_s=VALUES(updated_at_s), seen_at_s=VALUES(seen_at_s)");
      this.upsertAttr =
          c.prepareStatement(
              "INSERT INTO player_attributes(owner_uuid,attr_key,value_json,created_at_s,updated_at_s) "
                  + "VALUES(?,?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE value_json=VALUES(value_json), "
                  + "updated_at_s=VALUES(updated_at_s), created_at_s=LEAST(created_at_s, VALUES(created_at_s))");
      this.insertLedger =
          c.prepareStatement(
              "INSERT INTO core_ledger("
                  + "ts_s,module_id,op,from_uuid,to_uuid,amount,reason,ok,code,seq,"
                  + "idem_scope,idem_key_hash,old_units,new_units,server_node,extra_json) "
                  + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
      this.existsLedger =
          c.prepareStatement(
              "SELECT id FROM core_ledger WHERE ts_s=? AND module_id=? AND op=? AND seq=? AND reason=? LIMIT 1");
      this.deleteLedger =
          c.prepareStatement(
              "DELETE FROM core_ledger WHERE ts_s=? AND module_id=? AND op=? AND seq=? AND reason=?");
      this.upsertSeq =
          c.prepareStatement(
              "INSERT INTO player_event_seq(uuid,seq) VALUES(?,?) "
                  + "ON DUPLICATE KEY UPDATE seq=GREATEST(seq, VALUES(seq))");
    }

    @Override
    public void handlePlayer(JsonObject obj) throws SQLException {
      byte[] uuid = uuidToBytes(stringOrNull(obj, "uuid"));
      if (uuid == null) {
        throw new SQLException("missing player uuid in snapshot");
      }
      upsertPlayer.setBytes(1, uuid);
      upsertPlayer.setString(2, Objects.requireNonNullElse(stringOrNull(obj, "name"), ""));
      upsertPlayer.setLong(3, longOrZero(obj, "balance"));
      upsertPlayer.setLong(4, longOrZero(obj, "createdAt"));
      upsertPlayer.setLong(5, longOrZero(obj, "updatedAt"));
      long seen = longOrZero(obj, "seenAt");
      if (seen <= 0) {
        upsertPlayer.setNull(6, Types.BIGINT);
      } else {
        upsertPlayer.setLong(6, seen);
      }
      upsertPlayer.executeUpdate();
    }

    @Override
    public void handleAttribute(JsonObject obj) throws SQLException {
      byte[] owner = uuidToBytes(stringOrNull(obj, "owner"));
      if (owner == null) {
        throw new SQLException("missing attribute owner uuid in snapshot");
      }
      upsertAttr.setBytes(1, owner);
      upsertAttr.setString(2, Objects.requireNonNullElse(stringOrNull(obj, "key"), ""));
      String value = obj.get("value").toString();
      upsertAttr.setString(3, value);
      upsertAttr.setLong(4, longOrZero(obj, "createdAt"));
      upsertAttr.setLong(5, longOrZero(obj, "updatedAt"));
      upsertAttr.executeUpdate();
    }

    @Override
    public void handleLedger(JsonObject obj) throws SQLException {
      long ts = longOrZero(obj, "ts");
      String module = moduleField(obj);
      String op = Objects.requireNonNullElse(stringOrNull(obj, "op"), "");
      long seq = longOrZero(obj, "seq");
      String reason = Objects.requireNonNullElse(stringOrNull(obj, "reason"), "");

      existsLedger.setLong(1, ts);
      existsLedger.setString(2, module);
      existsLedger.setString(3, op);
      existsLedger.setLong(4, seq);
      existsLedger.setString(5, reason);
      try (ResultSet rs = existsLedger.executeQuery()) {
        if (rs.next()) {
          if (overwrite) {
            deleteLedger.setLong(1, ts);
            deleteLedger.setString(2, module);
            deleteLedger.setString(3, op);
            deleteLedger.setLong(4, seq);
            deleteLedger.setString(5, reason);
            deleteLedger.executeUpdate();
          } else {
            return;
          }
        }
      }

      insertLedger.setLong(1, ts);
      insertLedger.setString(2, module);
      insertLedger.setString(3, op);
      byte[] from = uuidToBytes(stringOrNull(obj, "from"));
      if (from == null) {
        insertLedger.setNull(4, Types.BINARY);
      } else {
        insertLedger.setBytes(4, from);
      }
      byte[] to = uuidToBytes(stringOrNull(obj, "to"));
      if (to == null) {
        insertLedger.setNull(5, Types.BINARY);
      } else {
        insertLedger.setBytes(5, to);
      }
      insertLedger.setLong(6, longOrZero(obj, "amount"));
      insertLedger.setString(7, reason);
      Boolean ok = boolOrNull(obj, "ok");
      insertLedger.setBoolean(8, ok != null ? ok : false);
      String code = stringOrNull(obj, "code");
      if (code == null) {
        insertLedger.setNull(9, Types.VARCHAR);
      } else {
        insertLedger.setString(9, code);
      }
      insertLedger.setLong(10, seq);
      String scope = stringOrNull(obj, "idemScope");
      if (scope == null) {
        insertLedger.setNull(11, Types.VARCHAR);
      } else {
        insertLedger.setString(11, scope);
      }
      byte[] key = hexToBytes(stringOrNull(obj, "idemKey"));
      if (key == null) {
        insertLedger.setNull(12, Types.BINARY);
      } else {
        insertLedger.setBytes(12, key);
      }
      long oldUnits = longOrZero(obj, "oldUnits");
      if (obj.has("oldUnits") && !obj.get("oldUnits").isJsonNull()) {
        insertLedger.setLong(13, oldUnits);
      } else {
        insertLedger.setNull(13, Types.BIGINT);
      }
      long newUnits = longOrZero(obj, "newUnits");
      if (obj.has("newUnits") && !obj.get("newUnits").isJsonNull()) {
        insertLedger.setLong(14, newUnits);
      } else {
        insertLedger.setNull(14, Types.BIGINT);
      }
      String node = stringOrNull(obj, "serverNode");
      if (node == null) {
        insertLedger.setNull(15, Types.VARCHAR);
      } else {
        insertLedger.setString(15, node);
      }
      JsonElement extra = obj.get("extra");
      if (extra == null || extra.isJsonNull()) {
        insertLedger.setNull(16, Types.LONGVARCHAR);
      } else {
        insertLedger.setString(16, extra.toString());
      }
      insertLedger.executeUpdate();
    }

    @Override
    public void handleEventSeq(JsonObject obj) throws SQLException {
      byte[] uuid = uuidToBytes(stringOrNull(obj, "uuid"));
      if (uuid == null) {
        throw new SQLException("missing player_event_seq uuid in snapshot");
      }
      upsertSeq.setBytes(1, uuid);
      upsertSeq.setLong(2, longOrZero(obj, "seq"));
      upsertSeq.executeUpdate();
    }

    @Override
    public void closeQuietly() {
      close(deleteLedger);
      close(existsLedger);
      close(insertLedger);
      close(upsertAttr);
      close(upsertPlayer);
      close(upsertSeq);
    }
  }

  private static void close(Statement st) {
    if (st != null) {
      try {
        st.close();
      } catch (SQLException ignored) {
      }
    }
  }

  private static void logSqlWarning(String op, SQLException e) {
    ErrorCode code = SqlErrorCodes.classify(e);
    LOG.warn(
        "(mincore) code={} op={} message={} sqlState={} vendor={}",
        code,
        op,
        e.getMessage(),
        e.getSQLState(),
        e.getErrorCode(),
        e);
  }
}
