/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.api;

/** Indicates that an attribute mutation could not be applied. */
public final class AttributeWriteException extends RuntimeException {
  private final ErrorCode errorCode;

  public AttributeWriteException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public AttributeWriteException(ErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  public ErrorCode errorCode() {
    return errorCode;
  }
}
