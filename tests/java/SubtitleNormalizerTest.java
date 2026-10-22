package dev.framextv.lumaremote;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Synthetic format, encoding, limits and XML rejection checks; never uses downloaded captions. */
public final class SubtitleNormalizerTest {
    private static int passed;
    private static final String SRT = "1\n00:00:01,125 --> 00:00:03,500\nOlá, coração!\nSegunda linha 😀\n";

    public static void main(String[] args) throws Exception {
        srtContentSniff();
        srtNumbersAndOrdering();
        webVtt();
        shortVttClock();
        missingHeaderSeparator();
        unicodeEntities();
        assColumnsAndCommaText();
        ssaAndDrawing();
        bomEncodings();
        declaredLegacyEncodings();
        legacyFallback();
        invalidEncoding();
        invalidResponses();
        malformedCuesAreNotHidden();
        isolatedMalformedCueIsDiscarded();
        invalidTiming();
        oversizedInput();
        oversizedOutput();
        cueCountLimit();
        ttmlText();
        ttmlLegacyAndDurations();
        ttmlNamespaces();
        ttmlUnsupportedTiming();
        ttmlExternalEntities();
        ttmlDepthLimit();
        System.out.println("Subtitle normalizer: " + passed + " checks passed.");
    }

    private static void srtContentSniff() throws Exception {
        SubtitleNormalizer.Result result = normalize(SRT.replace("\n", "\r\n"), "text/vtt");
        require("srt".equals(result.format) && result.cueCount == 1 && "UTF-8".equals(result.encoding), "actual SRT body wins over VTT metadata");
        require(text(result).equals(SRT + "\n"), "Unicode, multiline and CRLF normalization"); passed++;
    }

    private static void srtNumbersAndOrdering() throws Exception {
        String input = "53\n1:02:03.4 --> 1:02:05.6789\nLater\n\ncue-id\n00:01.2 --> 00:02.200\nEarlier\n";
        String output = text(normalize(input, "application/octet-stream"));
        require(output.equals("1\n00:00:01,200 --> 00:00:02,200\nEarlier\n\n2\n01:02:03,400 --> 01:02:05,679\nLater\n\n"), "timestamps, order and sequential numbers"); passed++;
    }

    private static void webVtt() throws Exception {
        String input = "WEBVTT sample\nLanguage: en\n\nNOTE ignored --> metadata\nnever a cue\n\nSTYLE\n::cue { color: lime }\n\nREGION\nid:fred\n\nintro\n00:00:01.010 --> 00:00:03.500 align:start line:20%\n<v Ana><c.green>Olá</c>, <b>mundo</b>!<00:00:02.000>\n<i>Second line</i>\n";
        SubtitleNormalizer.Result result = normalize(input, "application/x-subrip");
        require("vtt".equals(result.format) && result.cueCount == 1, "actual VTT body wins over SRT metadata");
        require(text(result).equals("1\n00:00:01,010 --> 00:00:03,500\nOlá, mundo!\nSecond line\n\n"), "VTT metadata, voice/classes, settings and inline timestamps"); passed++;
    }

    private static void shortVttClock() throws Exception {
        String output = text(normalize("WEBVTT\n\n00:01.005 --> 01:02.007\nMinute clock\n", "text/vtt"));
        require(output.contains("00:00:01,005 --> 00:01:02,007"), "WebVTT MM:SS timestamps"); passed++;
    }

    private static void missingHeaderSeparator() throws Exception {
        require(normalize("WEBVTT\n00:01.000 --> 00:02.000\nText", "text/vtt").cueCount == 1, "tolerate missing VTT header separator");
        require(normalize("WEBVTT\ncue\n00:01.000 --> 00:02.000\nText", "text/vtt").cueCount == 1, "tolerate cue ID without header separator"); passed++;
    }

    private static void unicodeEntities() throws Exception {
        String input = "1\n00:00:00,000 --> 00:00:01,000\n&lt; &gt; &amp; &quot; &apos; &nbsp; &#233; &#x1F600;\n";
        require(text(normalize(input, null)).contains("< > & \" '   é 😀"), "Unicode entity code points");
        expect("SUBTITLE_INVALID", "1\n00:00:00,000 --> 00:00:01,000\n&#0;\n", null);
        expect("SUBTITLE_INVALID", "1\n00:00:00,000 --> 00:00:01,000\n&#xD800;\n", null); passed++;
    }

    private static void assColumnsAndCommaText() throws Exception {
        String input = "[Script Info]\nScriptType: v4.00+\n[V4+ Styles]\nFormat: Name, Fontname\n[Events]\nFormat: Layer, End, Start, Style, Text\nComment: 0,0:00:03.00,0:00:01.00,Default,ignored\nDialogue: 0,0:00:03.25,0:00:01.05,Default,{\\an8}{\\i1}Olá, mundo!{\\i0}\\NSecond\\hline\n";
        SubtitleNormalizer.Result result = normalize(input, "text/plain");
        require("ass".equals(result.format) && result.cueCount == 1, "ASS detected");
        require(text(result).equals("1\n00:00:01,050 --> 00:00:03,250\nOlá, mundo!\nSecond line\n\n"), "ASS column mapping, text commas, overrides and newlines");
        input = "[Events]\nFormat: Text, End, Start\nDialogue: Hello, comma,0:00:02.00,0:00:01.00";
        require(text(normalize(input, null)).contains("Hello, comma"), "ASS non-final Text column isolated from both ends"); passed++;
    }

    private static void ssaAndDrawing() throws Exception {
        String input = "[Script Info]\nScriptType: v4.00\n[V4 Styles]\n[Events]\nFormat: Marked, Start, End, Text\nDialogue: Marked=0,0:00:01.00,0:00:03.00,{\\p1}m 0 0 l 20 20{\\p0}Visible\\nSecond\n";
        SubtitleNormalizer.Result result = normalize(input, null);
        require("ssa".equals(result.format) && text(result).contains("Visible\nSecond") && !text(result).contains("20 20"), "SSA plus ASS vector-drawing exclusion"); passed++;
    }

    private static void bomEncodings() throws Exception {
        for (String encoding : new String[] {"UTF-8", "UTF-16LE", "UTF-16BE"}) {
            byte[] prefix = encoding.equals("UTF-8") ? new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf} :
                    encoding.equals("UTF-16LE") ? new byte[] {(byte) 0xff, (byte) 0xfe} : new byte[] {(byte) 0xfe, (byte) 0xff};
            byte[] encoded = SRT.getBytes(Charset.forName(encoding)); byte[] bytes = Arrays.copyOf(prefix, prefix.length + encoded.length);
            System.arraycopy(encoded, 0, bytes, prefix.length, encoded.length);
            SubtitleNormalizer.Result result = SubtitleNormalizer.normalize(bytes, "text/vtt; charset=windows-1252");
            require(encoding.equals(result.encoding) && text(result).contains("coração") && text(result).contains("😀"), "BOM authoritative: " + encoding);
        }
        passed++;
    }

    private static void declaredLegacyEncodings() throws Exception {
        String caption = "1\n00:00:01,000 --> 00:00:03,000\nAção, coração e café\n";
        for (String encoding : new String[] {"windows-1252", "ISO-8859-1"}) {
            SubtitleNormalizer.Result result = SubtitleNormalizer.normalize(caption.getBytes(Charset.forName(encoding)), "text/plain; charset=\"" + encoding + "\"");
            require(encoding.equals(result.encoding) && text(result).contains("Ação, coração e café"), "declared charset: " + encoding);
        }
        passed++;
    }

    private static void legacyFallback() throws Exception {
        String caption = "1\n00:00:01,000 --> 00:00:03,000\n“Coração” — café\n";
        SubtitleNormalizer.Result result = SubtitleNormalizer.normalize(caption.getBytes(Charset.forName("windows-1252")), "text/vtt");
        require("windows-1252".equals(result.encoding) && text(result).contains("“Coração” — café"), "strict UTF8 failure permits valid legacy text fallback");
        require("UTF-8".equals(normalize(SRT, null).encoding), "valid UTF8 never falls back"); passed++;
    }

    private static void invalidEncoding() throws Exception {
        byte[] bytes = "1\n00:00:01,000 --> 00:00:02,000\nCafé".getBytes(Charset.forName("windows-1252"));
        expect("SUBTITLE_INVALID", bytes, "text/plain; charset=utf-8");
        expect("SUBTITLE_FORMAT", SRT, "text/plain; charset=shift-jis");
        expect("SUBTITLE_INVALID", new byte[] {(byte) 0xff, (byte) 0xfe, 1}, null);
        expect("SUBTITLE_FORMAT", new byte[] {(byte) 0xff, (byte) 0xfe, 0, 0, 10}, null);
        expect("SUBTITLE_INVALID", new byte[] {(byte) 0x81}, null);
        passed++;
    }

    private static void invalidResponses() throws Exception {
        for (String input : new String[] {"<!DOCTYPE html><html><body>Denied</body></html>", "<html><head>Error</head></html>", "{\"error\":\"expired\"}", "[\"not a subtitle\"]", "#EXTM3U\n#EXT-X-TARGETDURATION:10\npart.vtt", "Access denied", "<?xml version='1.0'?><Error>Denied</Error>"}) {
            expect("SUBTITLE_FORMAT", input, "text/vtt");
        }
        expect("SUBTITLE_INVALID", "", "text/vtt");
        expect("SUBTITLE_INVALID", " \n\t ", null);
        expect("SUBTITLE_INVALID", SRT + "\0", null); passed++;
    }

    private static void malformedCuesAreNotHidden() throws Exception {
        expect("SUBTITLE_INVALID", SRT + "\n2\nnot timing\nBroken\n", null);
        expect("SUBTITLE_INVALID", "WEBVTT\n\n00:01.000 --> 00:02.000\nGood\n\nbad-id\nBAD TIMING\nInvisible\n", null);
        expect("SUBTITLE_INVALID", "[Events]\nFormat: Start, End, Text\nDialogue: 0:00:01.00,0:00:02.00,Good\nDialogue: wrong,0:00:03.00,Bad", null);
        expect("SUBTITLE_INVALID", "WEBVTT\n\nNOTE no captions", null);
        expect("SUBTITLE_INVALID", "1\n00:00:01,000 --> 00:00:03,000\n<i></i>\n", null); passed++;
    }

    private static void isolatedMalformedCueIsDiscarded() throws Exception {
        StringBuilder input = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < 273; i++) input.append("00:00:01.000 --> 00:00:02.000\nGood ").append(i).append("\n\n");
        input.append("00:23:06.900 --> 00:00:00.000\nMalformed provider cue\n");
        SubtitleNormalizer.Result result = normalize(input.toString(), "text/vtt");
        require(result.cueCount == 273 && result.discardedCueCount == 1, "one isolated malformed provider cue is discarded and reported");
        passed++;
    }

    private static void invalidTiming() throws Exception {
        for (String timing : new String[] {"00:00:03,000 --> 00:00:01,000", "00:60:01,000 --> 01:02:02,000", "00:00:60,000 --> 00:02:02,000", "-1:00:00,000 --> 00:02:02,000", "1000:00:00,000 --> 1000:02:02,000", "00:00:01,000 --> 00:00:01,000"}) {
            expect("SUBTITLE_INVALID", "1\n" + timing + "\nText", null);
        }
        passed++;
    }

    private static void oversizedInput() throws Exception {
        expect("SUBTITLE_TOO_LARGE", new byte[SubtitleNormalizer.MAX_BYTES + 1], null); passed++;
    }

    private static void oversizedOutput() throws Exception {
        // Input fits; added SRT numbering/timestamps must never silently truncate output.
        String prefix = "00:01.000 --> 00:02.000\n";
        String input = prefix + "x".repeat(SubtitleNormalizer.MAX_BYTES - prefix.length());
        require(input.getBytes(StandardCharsets.UTF_8).length <= SubtitleNormalizer.MAX_BYTES, "synthetic input fits");
        expect("SUBTITLE_TOO_LARGE", input, null); passed++;
    }

    private static void cueCountLimit() throws Exception {
        StringBuilder input = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < SubtitleNormalizer.MAX_CUES; i++) input.append("00:01.000 --> 00:02.000\nx\n\n");
        require(normalize(input.toString(), null).cueCount == SubtitleNormalizer.MAX_CUES, "exact cue limit accepted");
        expect("SUBTITLE_TOO_LARGE", input + "00:01.000 --> 00:02.000\nx\n", null); passed++;
    }

    private static void ttmlText() throws Exception {
        String input = "<?xml version='1.0' encoding='UTF-8'?><tt xmlns='http://www.w3.org/ns/ttml'><head><styling><style xml:id='s1'/></styling></head><body><div><p begin='00:00:01.125' end='00:00:03.500'>Olá, <span>coração &amp; café</span><br/>Second &#x1F600;</p></div></body></tt>";
        SubtitleNormalizer.Result result = normalize(input, "application/ttml+xml");
        require("ttml".equals(result.format) && text(result).equals("1\n00:00:01,125 --> 00:00:03,500\nOlá, coração & café\nSecond 😀\n\n"), "namespaced TTML text, span, br and entities"); passed++;
    }

    private static void ttmlLegacyAndDurations() throws Exception {
        String input = "<tt xmlns='http://www.w3.org/2006/10/ttaf1'><body><div><p begin='1.5s' dur='500ms'>First</p><p begin='0.05m' end='0.002h' dur='2s'>Second</p><p end='1s'>Zero start</p></div></body></tt>";
        String output = text(normalize(input, "application/dfxp+xml"));
        require(output.contains("00:00:01,500 --> 00:00:02,000") && output.contains("00:00:03,000 --> 00:00:05,000") && output.contains("00:00:00,000 --> 00:00:01,000"), "DFXP media offsets/durations and minimum active duration"); passed++;
    }

    private static void ttmlNamespaces() throws Exception {
        String input = "<t:tt xmlns:t='http://www.w3.org/ns/ttml' xmlns:ttp='http://www.w3.org/ns/ttml#parameter' ttp:timeBase='media'><t:body><t:p begin='1s' end='2s' xml:space='preserve'>One\nTwo</t:p></t:body></t:tt>";
        require(text(normalize(input, null)).contains("One\nTwo"), "namespace prefix and xml:space");
        expect("SUBTITLE_FORMAT", "<tt xmlns='urn:unrelated'><body><p begin='1s' end='2s'>Text</p></body></tt>", null); passed++;
    }

    private static void ttmlUnsupportedTiming() throws Exception {
        for (String input : new String[] {
            "<tt><body begin='1s'><p begin='1s' end='2s'>Text</p></body></tt>",
            "<tt><body><div timeContainer='seq'><p begin='1s' end='2s'>Text</p></div></body></tt>",
            "<tt><body><p begin='00:00:01:15' end='00:00:02:15'>Text</p></body></tt>",
            "<tt><body><p begin='10f' end='30f'>Text</p></body></tt>",
            "<tt><body><p begin='10t' end='30t'>Text</p></body></tt>",
            "<tt><body><p begin='1s' end='2s'><span begin='0.5s'>Text</span></p></body></tt>",
            "<tt xmlns:ttp='http://www.w3.org/ns/ttml#parameter' ttp:timeBase='smpte'><body><p begin='1s' end='2s'>Text</p></body></tt>"}) expect("SUBTITLE_FORMAT", input, null);
        expect("SUBTITLE_INVALID", "<tt><body><p begin='1s'>No end</p></body></tt>", null);
        expect("SUBTITLE_INVALID", "<tt><body><p begin='2s' end='1s'>Reverse</p></body></tt>", null); passed++;
    }

    private static void ttmlExternalEntities() throws Exception {
        expect("SUBTITLE_FORMAT", "<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///not-a-real-file'>]><tt><body><p begin='1s' end='2s'>&x;</p></body></tt>", null);
        expect("SUBTITLE_FORMAT", "<!DOCTYPE tt SYSTEM 'http://127.0.0.1:9/no-request'><tt><body><p begin='1s' end='2s'>Text</p></body></tt>", null);
        expect("SUBTITLE_INVALID", "<tt><body><p begin='1s' end='2s'>&undefined;</p></body></tt>", null);
        expect("SUBTITLE_INVALID", "<tt><body><p begin='1s' end='2s'>Bad close</body></tt>", null); passed++;
    }

    private static void ttmlDepthLimit() throws Exception {
        String input = "<tt><body>" + "<div>".repeat(33) + "<p begin='1s' end='2s'>Text</p>" + "</div>".repeat(33) + "</body></tt>";
        expect("SUBTITLE_TOO_LARGE", input, null); passed++;
    }

    private static SubtitleNormalizer.Result normalize(String text, String type) throws Exception {
        return SubtitleNormalizer.normalize(text.getBytes(StandardCharsets.UTF_8), type);
    }
    private static String text(SubtitleNormalizer.Result result) { return new String(result.srt, StandardCharsets.UTF_8); }
    private static void expect(String code, String text, String type) throws Exception { expect(code, text.getBytes(StandardCharsets.UTF_8), type); }
    private static void expect(String code, byte[] bytes, String type) throws Exception {
        try { SubtitleNormalizer.normalize(bytes, type); throw new AssertionError("expected " + code); }
        catch (SubtitleNormalizer.NormalizeException actual) { require(code.equals(actual.code), "expected " + code + ", received " + actual.code); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
