package dev.framextv.lumareceiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class HttpMessageTest {
    private static HttpMessage parse(String raw) throws Exception {
        return HttpMessage.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static void rejects(String raw, int status) throws Exception {
        try { parse(raw); fail("accepted"); }
        catch (HttpMessage.HttpException expected) { assertEquals(status, expected.status); }
    }

    @Test public void parsesPostWithBodyAndStripsQuery() throws Exception {
        HttpMessage message = parse("POST /api/v1/player.status?x=1 HTTP/1.1\r\nHost: a\r\nAuthorization: Bearer t\r\nContent-Length: 2\r\n\r\n{}");
        assertEquals("POST", message.method);
        assertEquals("/api/v1/player.status", message.path);
        assertEquals("Bearer t", message.header("authorization"));
        assertEquals("{}", new String(message.body, StandardCharsets.UTF_8));
    }

    @Test public void acceptsBareLineFeeds() throws Exception {
        assertEquals("/api/v1/info", parse("GET /api/v1/info HTTP/1.1\nHost: a\n\n").path);
    }

    @Test public void rejectsMalformedAndOversizedRequests() throws Exception {
        rejects("", 400);
        rejects("GET nope HTTP/1.1\r\n\r\n", 400);
        rejects("POST /x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n", 411);
        rejects("POST /x HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n", 400);
        rejects("POST /x HTTP/1.1\r\nContent-Length: 900000\r\n\r\n", 413);
        rejects("POST /x HTTP/1.1\r\nContent-Length: 5\r\n\r\nab", 400);
        StringBuilder huge = new StringBuilder("GET /x HTTP/1.1\r\nX: ");
        for (int i = 0; i < HttpMessage.MAX_HEAD; i++) huge.append('a');
        rejects(huge.append("\r\n\r\n").toString(), 431);
    }
}
