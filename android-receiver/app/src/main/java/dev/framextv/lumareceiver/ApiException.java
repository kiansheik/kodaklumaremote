package dev.framextv.lumareceiver;

/** A fixed, client-visible failure code. Messages never carry URLs or tokens. */
final class ApiException extends Exception {
    final String code; final int status;
    ApiException(String code) { this(code, 400); }
    ApiException(String code, int status) { super(code); this.code = code; this.status = status; }
}
