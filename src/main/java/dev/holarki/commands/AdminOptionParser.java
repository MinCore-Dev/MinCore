/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Utility for tokenizing and parsing admin command options. */
final class AdminOptionParser {
  private final List<String> tokens;
  private int index;

  private AdminOptionParser(List<String> tokens) {
    this.tokens = tokens;
  }

  static AdminOptionParser from(String raw) {
    return new AdminOptionParser(tokenize(raw));
  }

  static List<String> tokenize(String raw) {
    List<String> tokens = new ArrayList<>();
    if (raw == null) {
      return tokens;
    }
    String s = raw.trim();
    if (s.isEmpty()) {
      return tokens;
    }
    StringBuilder current = new StringBuilder();
    boolean inQuote = false;
    char quoteChar = 0;
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (inQuote) {
        if (ch == quoteChar) {
          inQuote = false;
        } else {
          current.append(ch);
        }
        continue;
      }
      if (ch == '\'' || ch == '"') {
        inQuote = true;
        quoteChar = ch;
        continue;
      }
      if (Character.isWhitespace(ch)) {
        if (current.length() > 0) {
          tokens.add(current.toString());
          current.setLength(0);
        }
        continue;
      }
      current.append(ch);
    }
    if (inQuote) {
      throw new IllegalArgumentException("unterminated quote in arguments");
    }
    if (current.length() > 0) {
      tokens.add(current.toString());
    }
    return tokens;
  }

  boolean isEmpty() {
    return tokens.isEmpty();
  }

  private boolean hasNext() {
    return index < tokens.size();
  }

  private String next() {
    return tokens.get(index++);
  }

  <E extends Enum<E> & NamedOption> List<ParsedOption<E>> collect(E[] options) {
    Objects.requireNonNull(options, "options");
    List<ParsedOption<E>> parsed = new ArrayList<>();
    while (hasNext()) {
      String token = next();
      if (token.isEmpty()) {
        continue;
      }
      E option = resolve(options, token);
      if (option == null) {
        throw new IllegalArgumentException("unknown option: " + token);
      }
      String value = null;
      if (option.requiresValue()) {
        if (!hasNext()) {
          throw new IllegalArgumentException(option.missingValueMessage());
        }
        value = next();
      }
      parsed.add(new ParsedOption<>(option, value));
    }
    return parsed;
  }

  private static <E extends Enum<E> & NamedOption> E resolve(E[] options, String token) {
    for (E option : options) {
      if (option.matches(token)) {
        return option;
      }
    }
    return null;
  }

  /** Specification for an option flag handled by {@link AdminOptionParser}. */
  interface NamedOption {
    List<String> tokens();

    default boolean requiresValue() {
      return false;
    }

    default String missingValueMessage() {
      List<String> tokens = tokens();
      if (tokens.isEmpty()) {
        return "option requires a value";
      }
      return tokens.get(0) + " requires a value";
    }

    default boolean matches(String token) {
      for (String alias : tokens()) {
        if (alias.equals(token)) {
          return true;
        }
      }
      return false;
    }
  }

  /** Parsed option emitted by {@link AdminOptionParser}. */
  static final class ParsedOption<E extends Enum<E> & NamedOption> {
    private final E option;
    private final String value;

    ParsedOption(E option, String value) {
      this.option = option;
      this.value = value;
    }

    E option() {
      return option;
    }

    String value() {
      return value;
    }
  }
}
