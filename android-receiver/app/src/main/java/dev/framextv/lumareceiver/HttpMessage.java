package dev.framextv.lumareceiver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Minimal bounded HTTP/1.1 request parser and response writer. No chunked bodies. */
final class HttpMessage {
    static final int MAX_HEAD = 8 * 1024, MAX_BODY = 512 * 1024, MAX_HEADERS = 48;

    static final class HttpException extends IOException {
        final int status; final String code;
        HttpException(int status, String code) { super(code); this.status = status; this.code = code; }
    }

    final String method, path;
    final Map<String, String> headers;
    final byte[] body;

    private HttpMessage(String method, String path, Map<String, String> headers, byte[] body) {
        this.method = method; this.path = path; this.headers = headers; this.body = body;
    }

    String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }

    static HttpMessage read(InputStream in) throws IOException {
        String requestLine = line(in, MAX_HEAD);
        if (requestLine == null) throw new HttpException(400, "EMPTY_REQUEST");
        String[] parts = requestLine.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) throw new HttpException(400, "BAD_REQUEST_LINE");
        String method = parts[0], target = parts[1];
        if (!method.matches("[A-Z]{3,7}") || !target.startsWith("/") || target.length() > 2048) throw new HttpException(400, "BAD_REQUEST_LINE");
        Map<String, String> headers = new HashMap<>();
        int budget = MAX_HEAD - requestLine.length();
        while (true) {
            String header = line(in, budget);
            if (header == null) throw new HttpException(400, "TRUNCATED_HEADERS");
            if (header.isEmpty()) break;
            budget -= header.length() + 2;
            int colon = header.indexOf(':');
            if (colon <= 0 || headers.size() >= MAX_HEADERS) throw new HttpException(400, "BAD_HEADER");
            headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
        }
        if (headers.containsKey("transfer-encoding")) throw new HttpException(411, "LENGTH_REQUIRED");
        int length = 0;
        String declared = headers.get("content-length");
        if (declared != null) {
            if (!declared.matches("[0-9]{1,7}")) throw new HttpException(400, "BAD_LENGTH");
            length = Integer.parseInt(declared);
            if (length > MAX_BODY) throw new HttpException(413, "BODY_TOO_LARGE");
        }
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int count = in.read(body, read, length - read);
            if (count < 0) throw new HttpException(400, "TRUNCATED_BODY");
            read += count;
        }
        int query = target.indexOf('?');
        return new HttpMessage(method, query < 0 ? target : target.substring(0, query), headers, body);
    }

    /** Reads one CRLF-terminated ISO-8859-1 line; null at EOF before any byte. */
    private static String line(InputStream in, int budget) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int value = in.read();
            if (value < 0) return out.size() == 0 && previous < 0 ? null : out.toString("ISO-8859-1");
            if (value == '\n') {
                byte[] bytes = out.toByteArray();
                int end = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, end, StandardCharsets.ISO_8859_1);
            }
            if (out.size() >= budget) throw new HttpException(431, "HEADERS_TOO_LARGE");
            out.write(value); previous = value;
        }
    }

    static void write(OutputStream out, int status, String contentType, byte[] body) throws IOException {
        String reason = status == 200 ? "OK" : status == 401 ? "Unauthorized" : status == 403 ? "Forbidden"
            : status == 404 ? "Not Found" : status == 413 ? "Payload Too Large" : status >= 500 ? "Server Error" : "Bad Request";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + contentType
            + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
