package dev.framextv.lumareceiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class CaptionParserTest {
    private static List<CaptionParser.Cue> parse(String text) throws Exception {
        return CaptionParser.parse(text.getBytes(StandardCharsets.UTF_8), "text/vtt", "en");
    }

    @Test public void webVttWithHeaderStylesNotesAndTags() throws Exception {
        List<CaptionParser.Cue> cues = parse("WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000\n\nSTYLE\n::cue { color: red }\n\nNOTE skipped\n\n"
            + "1\n00:00:01.000 --> 00:00:02.500 align:start line:90%\n<i>Hello</i> &amp; <c.yellow>welcome</c>\n\n"
            + "00:03.000 --> 00:04.000\nShort &#233;l&eacute;\n");
        assertEquals(2, cues.size());
        assertEquals(1000, cues.get(0).start);
        assertEquals(2500, cues.get(0).end);
        assertEquals("Hello & welcome", cues.get(0).text);
        assertEquals(3000, cues.get(1).start);
        assertEquals("Hello & welcome", CaptionParser.textAt(cues, 1500));
        assertEquals("", CaptionParser.textAt(cues, 2600));
    }

    @Test public void srtToleratesMissingBlankLinesShortFractionsAndBadCues() throws Exception {
        List<CaptionParser.Cue> cues = parse("1\r\n00:00:01,5 --> 00:00:02,000\r\nFirst\r\nline two\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nSecond\r\n\r\n"
            + "3\r\n00:00:09,000 --> 00:00:05,000\r\nReversed is skipped\r\n\r\n4\r\ngarbage --> more\r\nAlso skipped\r\n\r\n5\r\n01:02:03,004 --> 01:02:04,000\r\nLast\r\n");
        assertEquals(3, cues.size());
        assertEquals(1500, cues.get(0).start);
        assertEquals("First\nline two", cues.get(0).text);
        assertEquals("Second", cues.get(1).text);
        assertEquals(3723004, cues.get(2).start);
    }

    @Test public void overlappingCuesAreJoinedInStartOrder() throws Exception {
        List<CaptionParser.Cue> cues = parse("WEBVTT\n\n00:00:01.000 --> 00:00:05.000\nTop\n\n00:00:02.000 --> 00:00:03.000\nBottom\n");
        assertEquals("Top\nBottom", CaptionParser.textAt(cues, 2500));
    }

    @Test public void assAndTtml() throws Exception {
        List<CaptionParser.Cue> ass = parse("[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
            + "Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\\an8}Hello, world\\Nsecond\n");
        assertEquals(1, ass.size());
        assertEquals(1500, ass.get(0).start);
        assertEquals("Hello, world\nsecond", ass.get(0).text);
        List<CaptionParser.Cue> ttml = parse("<?xml version=\"1.0\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>"
            + "<p begin=\"00:00:01.000\" end=\"00:00:02.000\">One<br/>Two</p><p begin=\"3s\" dur=\"1.5s\">Three</p></div></body></tt>");
        assertEquals(2, ttml.size());
        assertEquals("One\nTwo", ttml.get(0).text);
        assertEquals(4500, ttml.get(1).end);
    }

    @Test public void decodesLegacyAndUtf16Encodings() throws Exception {
        String bulgarian = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nЗдравей, свят\n";
        List<CaptionParser.Cue> cp1251 = CaptionParser.parse(bulgarian.getBytes(Charset.forName("windows-1251")), "text/vtt", "bg");
        assertEquals("Здравей, свят", cp1251.get(0).text);
        String arabic = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا بالعالم\n";
        assertEquals("مرحبا بالعالم", CaptionParser.parse(arabic.getBytes(Charset.forName("windows-1256")), "", "ar").get(0).text);
        byte[] utf16 = ("﻿" + bulgarian).getBytes(StandardCharsets.UTF_16LE);
        assertEquals("Здравей, свят", CaptionParser.parse(utf16, "", "en").get(0).text);
        byte[] noBom = bulgarian.getBytes(StandardCharsets.UTF_16BE);
        assertEquals("Здравей, свят", CaptionParser.parse(noBom, "", "en").get(0).text);
        String hebrew = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n‫שלום עולם‬\n";
        assertEquals("‫שלום עולם‬", parse(hebrew).get(0).text);
    }

    @Test public void rejectsFilesWithNoUsableCues() throws Exception {
        try { parse("WEBVTT\n\nNOTE nothing here\n"); fail("accepted"); }
        catch (ApiException expected) { assertEquals("SUBTITLE_INVALID", expected.code); }
    }

    @Test public void repairsPreReversedRtlPunctuationOnlyWhenTheWholeFileIsReversed() throws Exception {
        String reversed = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n.היי, גברת פלין\n.יקירתי-\n\n"
            + "00:00:03.000 --> 00:00:04.000\n?מה שלומך\n\n00:00:05.000 --> 00:00:06.000\n.תודה\n";
        List<CaptionParser.Cue> fixed = parse(reversed);
        assertEquals("היי, גברת פלין.\n-יקירתי.", fixed.get(0).text);
        assertEquals("מה שלומך?", fixed.get(1).text);
        String correct = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n\u200Fתסתכל טוב.\n-כן.\n\n00:00:03.000 --> 00:00:04.000\nשלום.\n\n00:00:05.000 --> 00:00:06.000\nתודה.\n";
        assertEquals("\u200Fתסתכל טוב.\n-כן.", parse(correct).get(0).text);
    }

    @Test public void stripsAssOverridesInsideWebVtt() throws Exception {
        assertEquals("\u202Bألن يُقام القداس؟", parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n\u202B{\\an8}ألن يُقام القداس؟\n").get(0).text);
    }
}
