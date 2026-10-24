package dev.turboj.refactorj.transactions;

/** Structured failure; a recovery conflict deliberately preserves the journal and external edits. */
public final class TransactionException extends RuntimeException {
    private final String code;

    public TransactionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public TransactionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
