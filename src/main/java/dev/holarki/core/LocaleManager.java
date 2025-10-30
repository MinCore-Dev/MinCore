/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages locale resources and translation lookups.
 *
 * <p>The manager loads the configured locales on startup, enforcing the whitelist provided by the
 * configuration. When a translation or locale is requested that is not available, it falls back to
 * the configured default or fallback locale.</p>
 */
public final class LocaleManager {
  private static final Logger LOG = LoggerFactory.getLogger(LocaleManager.class);
  private static final String LANG_PATH = "assets/holarki/lang/";

  private static volatile Locale defaultLocale = Locale.forLanguageTag("en-US");
  private static volatile Locale fallbackLocale = Locale.forLanguageTag("en-US");
  private static volatile Map<String, LocaleBundle> localeBundles = Map.of();
  private static volatile Set<String> enabledCodes = Set.of("en_us");

  private LocaleManager() {}

  /**
   * Initializes the locale manager using the provided configuration.
   *
   * @param cfg runtime configuration containing i18n settings
   */
  public static synchronized void initialize(Config cfg) {
    Objects.requireNonNull(cfg, "cfg");
    Config.I18n i18n = Objects.requireNonNull(cfg.i18n(), "cfg.i18n");

    Map<String, LocaleBundle> bundles = new LinkedHashMap<>();
    Set<String> codes = new LinkedHashSet<>();
    ClassLoader loader = LocaleManager.class.getClassLoader();

    for (String rawCode : i18n.enabledLocales()) {
      String normalized = normalize(rawCode);
      Locale locale = Locale.forLanguageTag(rawCode.replace('_', '-'));
      Map<String, String> translations = loadTranslations(loader, normalized);
      bundles.put(normalized, new LocaleBundle(locale, translations));
      codes.add(normalized);
    }

    String defaultCode = normalize(i18n.defaultLocale());
    if (!bundles.containsKey(defaultCode)) {
      throw new IllegalStateException(
          "Default locale %s is not enabled".formatted(i18n.defaultLocale().toLanguageTag()));
    }

    String fallbackCode = normalize(i18n.fallbackLocale());
    if (!bundles.containsKey(fallbackCode)) {
      throw new IllegalStateException(
          "Fallback locale %s is not enabled".formatted(i18n.fallbackLocale().toLanguageTag()));
    }

    localeBundles = Map.copyOf(bundles);
    enabledCodes = Set.copyOf(codes);
    defaultLocale = localeBundles.get(defaultCode).locale();
    fallbackLocale = localeBundles.get(fallbackCode).locale();

    LOG.info(
        "(holarki) loaded {} locale(s); default={} fallback={} enabled={}",
        localeBundles.size(),
        defaultLocale.toLanguageTag(),
        fallbackLocale.toLanguageTag(),
        enabledCodes);
  }

  private static Map<String, String> loadTranslations(ClassLoader loader, String normalizedCode) {
    String resourcePath = LANG_PATH + normalizedCode + ".json";
    try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
      if (stream == null) {
        throw new IllegalStateException("Missing translation file for locale: " + resourcePath);
      }
      try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
        JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
        Map<String, String> translations = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
          translations.put(entry.getKey(), entry.getValue().getAsString());
        }
        return Map.copyOf(translations);
      } catch (Exception ex) {
        throw new IllegalStateException(
            "Failed to parse translation file for locale: " + resourcePath, ex);
      }
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Failed to read translation file for locale: " + resourcePath, ex);
    }
  }

  private static String normalize(String code) {
    Objects.requireNonNull(code, "code");
    return code.replace('-', '_').toLowerCase(Locale.ROOT);
  }

  private static String normalize(Locale locale) {
    Objects.requireNonNull(locale, "locale");
    return normalize(locale.toLanguageTag());
  }

  /** Returns the configured default locale. */
  public static Locale defaultLocale() {
    return defaultLocale;
  }

  /** Returns the configured fallback locale. */
  public static Locale fallbackLocale() {
    return fallbackLocale;
  }

  /** Returns whether a locale is explicitly enabled. */
  public static boolean isEnabled(Locale locale) {
    if (locale == null) {
      return false;
    }
    String code = normalize(locale);
    return enabledCodes.contains(code);
  }

  /** Resolves the provided locale to a whitelisted locale or the default. */
  public static Locale resolveOrDefault(Locale locale) {
    if (locale == null) {
      return defaultLocale();
    }
    LocaleBundle bundle = localeBundles.get(normalize(locale));
    if (bundle != null) {
      return bundle.locale();
    }
    return defaultLocale();
  }

  /** Returns the translation table for the supplied locale or the default. */
  public static Map<String, String> translations(Locale locale) {
    Locale resolved = resolveOrDefault(locale);
    LocaleBundle direct = localeBundles.get(normalize(resolved));
    if (direct != null) {
      return direct.translations();
    }
    LocaleBundle fallback = localeBundles.get(normalize(defaultLocale));
    return fallback != null ? fallback.translations() : Map.of();
  }

  /**
   * Resolves a translation for the provided key, consulting fallback and default locales when the
   * key is absent.
   */
  public static String translate(String key, Locale locale) {
    if (key == null || key.isBlank()) {
      return "";
    }

    Locale target = resolveOrDefault(locale);
    LocaleBundle direct = localeBundles.get(normalize(target));
    if (direct != null && direct.translations().containsKey(key)) {
      return direct.translations().get(key);
    }

    LocaleBundle fallback = localeBundles.get(normalize(fallbackLocale));
    if (fallback != null && fallback.translations().containsKey(key)) {
      return fallback.translations().get(key);
    }

    LocaleBundle defaults = localeBundles.get(normalize(defaultLocale));
    if (defaults != null && defaults.translations().containsKey(key)) {
      return defaults.translations().get(key);
    }

    return key;
  }

  static synchronized void resetForTests() {
    localeBundles = Map.of();
    enabledCodes = Set.of("en_us");
    defaultLocale = Locale.forLanguageTag("en-US");
    fallbackLocale = Locale.forLanguageTag("en-US");
  }

  private record LocaleBundle(Locale locale, Map<String, String> translations) {}
}
