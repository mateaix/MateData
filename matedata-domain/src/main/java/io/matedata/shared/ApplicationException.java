package io.matedata.shared;

/** A use-case failure independent of its delivery transport. */
public class ApplicationException extends RuntimeException {
  public enum Kind {
    UNAUTHENTICATED,
    INVALID_CREDENTIALS,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    BUSY,
    INVALID_REQUEST,
    CONNECTION_FAILED
  }

  private final Kind kind;

  public ApplicationException(Kind kind, String message) {
    super(message);
    this.kind = java.util.Objects.requireNonNull(kind);
  }

  public Kind kind() {
    return kind;
  }
}
