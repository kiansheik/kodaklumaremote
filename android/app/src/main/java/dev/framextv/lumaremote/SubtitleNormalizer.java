package dev.framextv.lumaremote;

import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/** Complete, bounded text-subtitle conversion. Contains no networking or Android dependencies. */
public final class SubtitleNormalizer {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_CUES = 20000;
    private static final int MAX_DISCARDED_CUES = 10, MAX_DISCARDED_PERCENT = 2;
    private static final long MAX_TIME = 999L * 3600000 + 59 * 60000 + 59999;
    private static final Pattern CHARSET = Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*[\"']?([^\\s;\"']+)");
    private static final Pattern XML_ENCODING = Pattern.compile("(?i)^\\s*<\\?xml[^>]*\\bencoding\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern CLOCK = Pattern.compile("^(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{2})(?:[.,](\\d{1,9}))?$");
    private static final Pattern TIMING = Pattern.compile("^(\\S+)\\s+-->\\s+(\\S+)(?:\\s+.*)?$");
    private static final Pattern ENTITY = Pattern.compile("&(?:amp|lt|gt|quot|apos|nbsp|#\\d+|#x[0-9a-f]+);", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_TIME = Pattern.compile("<(?:\\d{1,3}:)?\\d{2}:\\d{2}[.,]\\d{1,9}>");
    private static final Pattern TAG = Pattern.compile("</?[A-Za-z][^>\\n]*>");
    private static final Pattern DRAW_MODE = Pattern.compile("\\\\p(\\d+)(?!\\d)");

    private SubtitleNormalizer() {}

    public static final class NormalizeException extends Exception {
        public final String code;
        NormalizeException(String code) { super(code); this.code = code; }
    }

    public static final class Result {
        public final byte[] srt;
        public final String format;
        public final String encoding;
        public final int cueCount, discardedCueCount;
        Result(byte[] srt, String format, String encoding, int cueCount, int discardedCueCount) {
            this.srt = srt; this.format = format; this.encoding = encoding; this.cueCount = cueCount; this.discardedCueCount = discardedCueCount;
        }
    }

    private static final class Decoded {
        final String text, encoding;
        Decoded(String text, String encoding) { this.text = text; this.encoding = encoding; }
    }

    private static final class Cue {
        final long start, end;
        final String text;
        Cue(long start, long end, String text) { this.start = start; this.end = end; this.text = text; }
    }

    private static final class TimedText {
        final List<Cue> cues;
        final int discarded;
        TimedText(List<Cue> cues, int discarded) { this.cues = cues; this.discarded = discarded; }
    }

    public static Result normalize(byte[] body, String contentType) throws NormalizeException {
        if (body == null || body.length == 0) throw failure("SUBTITLE_INVALID");
        if (body.length > MAX_BYTES) throw failure("SUBTITLE_TOO_LARGE");
        Decoded decoded = decode(body, contentType);
        String text = decoded.text.replace("\r\n", "\n").replace('\r', '\n').replaceFirst("^\\uFEFF", "");
        validateText(text);
        String leading = text.trim();
        if (leading.isEmpty()) throw failure("SUBTITLE_INVALID");
        String lower = leading.toLowerCase(Locale.ROOT);
        if (lower.startsWith("#extm3u") || lower.startsWith("{") || lower.startsWith("[") &&
                !lower.matches("(?s)^\\[(script info|v4\\+? styles|events)\\].*")) throw failure("SUBTITLE_FORMAT");
        if (lower.matches("(?s)^<(?:!doctype\\s+html\\b|html\\b|head\\b|body\\b).*")) throw failure("SUBTITLE_FORMAT");
        List<Cue> cues; int discarded = 0;
        String format;
        if (leading.matches("(?s)^WEBVTT(?:[ \\t\\n].*)?$")) {
            format = "vtt"; TimedText parsed = timedText(text, true); cues = parsed.cues; discarded = parsed.discarded;
        } else if (lower.matches("(?s)^\\[(script info|v4\\+? styles|events)\\].*")) {
            format = lower.contains("[v4 styles]") && !lower.contains("[v4+ styles]") ? "ssa" : "ass";
            cues = ass(text);
        } else if (leading.startsWith("<")) {
            format = "ttml"; cues = ttml(text);
        } else if (text.contains("-->")) {
            format = "srt"; TimedText parsed = timedText(text, false); cues = parsed.cues; discarded = parsed.discarded;
        } else throw failure("SUBTITLE_FORMAT");
        if (cues.isEmpty()) throw failure("SUBTITLE_INVALID");
        cues.sort(Comparator.comparingLong(cue -> cue.start));
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            output.append(i + 1).append('\n').append(timestamp(cue.start)).append(" --> ").append(timestamp(cue.end))
                    .append('\n').append(cue.text).append("\n\n");
            if (output.length() > MAX_BYTES) throw failure("SUBTITLE_TOO_LARGE");
        }
        byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw failure("SUBTITLE_TOO_LARGE");
        return new Result(bytes, format, decoded.encoding, cues.size(), discarded);
    }

    private static Decoded decode(byte[] body, String contentType) throws NormalizeException {
        int offset = 0;
        String encoding = null;
        if (body.length >= 4 && (body[0] == 0 && body[1] == 0 && body[2] == (byte) 0xfe && body[3] == (byte) 0xff ||
                body[0] == (byte) 0xff && body[1] == (byte) 0xfe && body[2] == 0 && body[3] == 0)) throw failure("SUBTITLE_FORMAT");
        if (body.length >= 3 && body[0] == (byte) 0xef && body[1] == (byte) 0xbb && body[2] == (byte) 0xbf) {
            encoding = "UTF-8"; offset = 3;
        } else if (body.length >= 2 && body[0] == (byte) 0xff && body[1] == (byte) 0xfe) {
            encoding = "UTF-16LE"; offset = 2;
        } else if (body.length >= 2 && body[0] == (byte) 0xfe && body[1] == (byte) 0xff) {
            encoding = "UTF-16BE"; offset = 2;
        }
        if (encoding == null) {
            Matcher declared = CHARSET.matcher(contentType == null ? "" : contentType);
            String charset = declared.find() ? declared.group(1) : null;
            if (charset == null) {
                Matcher xml = XML_ENCODING.matcher(new String(body, 0, Math.min(body.length, 256), StandardCharsets.ISO_8859_1));
                if (xml.find()) charset = xml.group(1);
            }
            if (charset != null) encoding = charset(charset);
        }
        boolean fallback = encoding == null;
        if (encoding == null) encoding = "UTF-8";
        try {
            return new Decoded(strictDecode(body, offset, encoding), encoding);
        } catch (CharacterCodingException invalid) {
            if (!fallback) throw failure("SUBTITLE_INVALID");
            try { return new Decoded(strictDecode(body, 0, "windows-1252"), "windows-1252"); }
            catch (CharacterCodingException badLegacy) { throw failure("SUBTITLE_INVALID"); }
        }
    }

    private static String charset(String name) throws NormalizeException {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "utf8": case "utf-8": return "UTF-8";
            case "utf-16le": return "UTF-16LE";
            case "utf-16be": return "UTF-16BE";
            case "windows-1252": case "cp1252": case "x-cp1252": return "windows-1252";
            case "iso-8859-1": case "iso8859-1": case "latin1": case "latin-1": return "ISO-8859-1";
            default: throw failure("SUBTITLE_FORMAT");
        }
    }

    private static String strictDecode(byte[] body, int offset, String encoding) throws CharacterCodingException {
        return Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body, offset, body.length - offset)).toString();
    }

    private static void validateText(String text) throws NormalizeException {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 32 && c != '\n' && c != '\r' && c != '\t' || c >= 127 && c <= 159) throw failure("SUBTITLE_INVALID");
        }
    }

    private static TimedText timedText(String text, boolean vtt) throws NormalizeException {
        String[] lines = text.split("\n", -1);
        List<Cue> cues = new ArrayList<>();
        int i = 0, discarded = 0;
        if (vtt) {
            while (i < lines.length && lines[i].trim().isEmpty()) i++;
            i++;
            // Header metadata precedes the first blank. Also tolerate a missing
            // separator when the very next line already starts a real cue.
            while (i < lines.length && !lines[i].trim().isEmpty() && !lines[i].contains("-->") &&
                    !(i + 1 < lines.length && lines[i + 1].contains("-->"))) i++;
        }
        while (i < lines.length) {
            while (i < lines.length && lines[i].trim().isEmpty()) i++;
            if (i == lines.length) break;
            if (vtt && lines[i].matches("^(?:NOTE|STYLE|REGION)(?:[ \\t].*)?$")) {
                while (i < lines.length && !lines[i].trim().isEmpty()) i++;
                continue;
            }
            int first = i;
            while (i < lines.length && !lines[i].trim().isEmpty()) i++;
            int endOfBlock = i;
            try {
                int timingLine = lines[first].contains("-->") ? first : first + 1;
                if (timingLine >= endOfBlock) throw failure("SUBTITLE_INVALID");
                Matcher match = TIMING.matcher(lines[timingLine].trim());
                if (!match.matches()) throw failure("SUBTITLE_INVALID");
                long start = clock(match.group(1)), end = clock(match.group(2));
                StringBuilder body = new StringBuilder();
                for (int line = timingLine + 1; line < endOfBlock; line++) {
                    if (body.length() > 0) body.append('\n');
                    body.append(lines[line]);
                }
                add(cues, start, end, plain(body.toString()));
            } catch (NormalizeException invalid) {
                if ("SUBTITLE_TOO_LARGE".equals(invalid.code)) throw invalid;
                discarded++;
            }
        }
        int total = cues.size() + discarded;
        if (discarded > MAX_DISCARDED_CUES || (long) discarded * 100 > (long) total * MAX_DISCARDED_PERCENT) throw failure("SUBTITLE_INVALID");
        return new TimedText(cues, discarded);
    }

    private static List<Cue> ass(String text) throws NormalizeException {
        List<Cue> cues = new ArrayList<>();
        String[] columns = null;
        boolean events = false;
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith(";")) continue;
            if (line.startsWith("[") && line.endsWith("]")) { events = "[events]".equalsIgnoreCase(line); continue; }
            if (!events) continue;
            int colon = line.indexOf(':');
            if (colon < 0) throw failure("SUBTITLE_INVALID");
            String key = line.substring(0, colon).trim();
            if ("Format".equalsIgnoreCase(key)) {
                if (line.length() > 8192) throw failure("SUBTITLE_FORMAT");
                columns = line.substring(colon + 1).toLowerCase(Locale.ROOT).split(",", -1);
                if (columns.length > 32) throw failure("SUBTITLE_FORMAT");
                for (int j = 0; j < columns.length; j++) columns[j] = columns[j].trim();
            } else if ("Dialogue".equalsIgnoreCase(key)) {
                if (columns == null) throw failure("SUBTITLE_FORMAT");
                int startColumn = column(columns, "start"), endColumn = column(columns, "end"), textColumn = column(columns, "text");
                String[] fields = splitDialogue(line.substring(colon + 1), columns.length, textColumn);
                add(cues, clock(fields[startColumn].trim()), clock(fields[endColumn].trim()), assText(fields[textColumn]));
            } else if (!"Comment".equalsIgnoreCase(key)) throw failure("SUBTITLE_FORMAT");
        }
        return cues;
    }

    private static int column(String[] columns, String name) throws NormalizeException {
        int found = -1;
        for (int i = 0; i < columns.length; i++) if (name.equals(columns[i])) {
            if (found >= 0) throw failure("SUBTITLE_INVALID");
            found = i;
        }
        if (found < 0) throw failure("SUBTITLE_FORMAT");
        return found;
    }

    private static String[] splitDialogue(String line, int count, int text) throws NormalizeException {
        String[] fields = new String[count]; int left = 0, right = line.length();
        for (int i = 0; i < text; i++) {
            int comma = line.indexOf(',', left);
            if (comma < 0) throw failure("SUBTITLE_INVALID");
            fields[i] = line.substring(left, comma); left = comma + 1;
        }
        for (int i = count - 1; i > text; i--) {
            int comma = line.lastIndexOf(',', right - 1);
            if (comma < left) throw failure("SUBTITLE_INVALID");
            fields[i] = line.substring(comma + 1, right); right = comma;
        }
        if (right < left) throw failure("SUBTITLE_INVALID");
        fields[text] = line.substring(left, right);
        return fields;
    }

    private static String assText(String text) throws NormalizeException {
        StringBuilder output = new StringBuilder(); boolean drawing = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                int end = text.indexOf('}', i + 1);
                if (end < 0) throw failure("SUBTITLE_INVALID");
                Matcher mode = DRAW_MODE.matcher(text.substring(i + 1, end));
                while (mode.find()) drawing = !"0".equals(mode.group(1));
                i = end;
            } else if (!drawing && c == '\\' && i + 1 < text.length()) {
                char escaped = text.charAt(i + 1);
                if (escaped == 'N' || escaped == 'n') { output.append('\n'); i++; }
                else if (escaped == 'h') { output.append(' '); i++; }
                else output.append(c);
            } else if (!drawing) output.append(c);
        }
        return output.toString().trim();
    }

    private static String plain(String text) throws NormalizeException {
        String value = TAG.matcher(INLINE_TIME.matcher(text).replaceAll("")).replaceAll("");
        Matcher matcher = ENTITY.matcher(value); StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String entity = matcher.group().toLowerCase(Locale.ROOT), replacement;
            switch (entity) {
                case "&amp;": replacement = "&"; break;
                case "&lt;": replacement = "<"; break;
                case "&gt;": replacement = ">"; break;
                case "&quot;": replacement = "\""; break;
                case "&apos;": replacement = "'"; break;
                case "&nbsp;": replacement = " "; break;
                default:
                    try {
                        int code = entity.startsWith("&#x") ? Integer.parseInt(entity.substring(3, entity.length() - 1), 16) :
                                Integer.parseInt(entity.substring(2, entity.length() - 1));
                        if (!Character.isValidCodePoint(code) || code >= 0xd800 && code <= 0xdfff || code < 32 && code != 10 && code != 9 || code >= 127 && code <= 159) throw failure("SUBTITLE_INVALID");
                        replacement = new String(Character.toChars(code));
                    } catch (NumberFormatException invalid) { throw failure("SUBTITLE_INVALID"); }
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString().trim();
    }

    private static long clock(String value) throws NormalizeException {
        Matcher match = CLOCK.matcher(value);
        if (!match.matches()) throw failure("SUBTITLE_INVALID");
        int hours = match.group(1) == null ? 0 : Integer.parseInt(match.group(1));
        int minutes = Integer.parseInt(match.group(2)), seconds = Integer.parseInt(match.group(3));
        if (minutes > 59 || seconds > 59) throw failure("SUBTITLE_INVALID");
        long ms = match.group(4) == null ? 0 : new BigDecimal("0." + match.group(4)).movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValue();
        return boundedTime(hours * 3600000L + minutes * 60000L + seconds * 1000L + ms);
    }

    private static long boundedTime(long value) throws NormalizeException {
        if (value < 0 || value > MAX_TIME) throw failure("SUBTITLE_INVALID");
        return value;
    }

    private static String timestamp(long ms) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }

    private static void add(List<Cue> cues, long start, long end, String text) throws NormalizeException {
        if (end <= start || text.trim().isEmpty() || text.contains("\n\n")) throw failure("SUBTITLE_INVALID");
        if (cues.size() >= MAX_CUES) throw failure("SUBTITLE_TOO_LARGE");
        cues.add(new Cue(boundedTime(start), boundedTime(end), text));
    }

    /** Common media-time TTML/DFXP text profile. Unsupported timing is rejected, never guessed. */
    private static List<Cue> ttml(String text) throws NormalizeException {
        if (Pattern.compile("<!\\s*(?:DOCTYPE|ENTITY)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) throw failure("SUBTITLE_FORMAT");
        TtmlHandler handler = new TtmlHandler();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance(); factory.setNamespaceAware(true);
            // Some Android XML providers do not implement every Xerces feature.
            // DTDs are independently forbidden above, and the resolver denies all external entities.
            for (String feature : Arrays.asList("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities", "http://apache.org/xml/features/nonvalidating/load-external-dtd")) {
                try { factory.setFeature(feature, false); } catch (Exception unsupported) { /* independent guards above/below */ }
            }
            try { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception unsupported) { /* bounded SAX handler */ }
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("SUBTITLE_FORMAT"); });
            reader.setContentHandler(handler); reader.setErrorHandler(handler);
            reader.parse(new InputSource(new StringReader(text)));
            if (handler.failure != null) throw handler.failure;
            if (!handler.bodySeen) throw failure("SUBTITLE_FORMAT");
            return handler.cues;
        } catch (NormalizeException known) { throw known; }
        catch (Exception invalid) {
            // Android Expat can replace a callback's SAXException without its
            // original cause. Preserve our fixed validation result explicitly.
            if (handler.failure != null) throw handler.failure;
            Throwable cause = invalid;
            for (int depth = 0; cause != null && depth < 8; depth++) {
                if (cause instanceof NormalizeException) throw (NormalizeException) cause;
                Throwable nested = cause instanceof SAXException ? ((SAXException) cause).getException() : null;
                Throwable next = nested == null ? cause.getCause() : nested;
                if (next == cause) break;
                cause = next;
            }
            throw failure("SUBTITLE_INVALID");
        }
    }

    private static final class TtmlHandler extends DefaultHandler {
        final List<Cue> cues = new ArrayList<>();
        final List<Boolean> preserve = new ArrayList<>();
        int depth, elements, bodyDepth, pDepth;
        boolean bodySeen;
        String namespace;
        NormalizeException failure;
        long start, end;
        StringBuilder cue;

        private void reject(String code) throws SAXException { failure = failure(code); throw new SAXException(failure); }
        @Override public void startElement(String uri, String local, String qName, Attributes attrs) throws SAXException {
            depth++; elements++;
            if (depth > 32 || elements > 100000) reject("SUBTITLE_TOO_LARGE");
            boolean space = !preserve.isEmpty() && preserve.get(preserve.size() - 1);
            String specified = attrs.getValue(XMLConstants.XML_NS_URI, "space");
            if (specified != null) {
                if (!"default".equals(specified) && !"preserve".equals(specified)) reject("SUBTITLE_FORMAT");
                space = "preserve".equals(specified);
            }
            preserve.add(space);
            if (depth == 1) {
                if (!"tt".equals(local) || !(uri.isEmpty() || "http://www.w3.org/ns/ttml".equals(uri) || "http://www.w3.org/2006/10/ttaf1".equals(uri))) reject("SUBTITLE_FORMAT");
                namespace = uri;
                String timeBase = attribute(attrs, "timeBase");
                if (timeBase != null && !"media".equals(timeBase)) reject("SUBTITLE_FORMAT");
            }
            if ("seq".equals(attribute(attrs, "timeContainer")) || attribute(attrs, "repeatCount") != null || attribute(attrs, "repeatDur") != null) reject("SUBTITLE_FORMAT");
            if ("body".equals(local)) { if (bodySeen || depth != 2) reject("SUBTITLE_FORMAT"); bodyDepth = depth; bodySeen = true; }
            if (bodyDepth == 0) return;
            if (!uri.equals(namespace) || !("body".equals(local) || "div".equals(local) || "p".equals(local) || "span".equals(local) || "br".equals(local))) reject("SUBTITLE_FORMAT");
            if ("p".equals(local)) {
                if (pDepth != 0) reject("SUBTITLE_FORMAT");
                pDepth = depth; cue = new StringBuilder();
                try {
                    String begin = attribute(attrs, "begin"), finish = attribute(attrs, "end"), duration = attribute(attrs, "dur");
                    start = begin == null ? 0 : ttmlTime(begin);
                    if (finish == null && duration == null) throw failure("SUBTITLE_INVALID");
                    end = finish == null ? boundedTime(start + ttmlTime(duration)) : ttmlTime(finish);
                    if (duration != null) end = Math.min(end, boundedTime(start + ttmlTime(duration)));
                } catch (NormalizeException invalid) { failure = invalid; throw new SAXException(invalid); }
            } else {
                if (attribute(attrs, "begin") != null || attribute(attrs, "end") != null || attribute(attrs, "dur") != null) reject("SUBTITLE_FORMAT");
                if (pDepth != 0) {
                    if ("br".equals(local)) cue.append('\n');
                    else if (!"span".equals(local)) reject("SUBTITLE_FORMAT");
                }
            }
        }

        @Override public void characters(char[] chars, int offset, int length) {
            if (pDepth == 0) return;
            if (preserve.get(preserve.size() - 1)) cue.append(chars, offset, length);
            else for (int i = offset; i < offset + length; i++) {
                char c = chars[i];
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    if (cue.length() > 0 && cue.charAt(cue.length() - 1) != ' ' && cue.charAt(cue.length() - 1) != '\n') cue.append(' ');
                } else cue.append(c);
            }
        }

        @Override public void endElement(String uri, String local, String qName) throws SAXException {
            if (depth == pDepth) {
                try { add(cues, start, end, cue.toString().replaceAll(" *\\n *", "\n").trim()); }
                catch (NormalizeException invalid) { failure = invalid; throw new SAXException(invalid); }
                pDepth = 0; cue = null;
            }
            if (depth == bodyDepth) bodyDepth = 0;
            preserve.remove(preserve.size() - 1); depth--;
        }
        @Override public void error(SAXParseException invalid) throws SAXException { throw invalid; }
        @Override public void fatalError(SAXParseException invalid) throws SAXException { throw invalid; }
    }

    private static String attribute(Attributes attrs, String local) {
        for (int i = 0; i < attrs.getLength(); i++) if (local.equals(attrs.getLocalName(i))) return attrs.getValue(i);
        return null;
    }

    private static long ttmlTime(String value) throws NormalizeException {
        if (value.matches("\\d{1,3}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?")) return clock(value);
        Matcher offset = Pattern.compile("^(\\d{1,9}(?:\\.\\d{1,9})?)(h|m|s|ms)$").matcher(value);
        if (!offset.matches()) throw failure("SUBTITLE_FORMAT");
        BigDecimal factor;
        switch (offset.group(2)) {
            case "h": factor = BigDecimal.valueOf(3600000); break;
            case "m": factor = BigDecimal.valueOf(60000); break;
            case "s": factor = BigDecimal.valueOf(1000); break;
            default: factor = BigDecimal.ONE;
        }
        try { return boundedTime(new BigDecimal(offset.group(1)).multiply(factor).setScale(0, RoundingMode.HALF_UP).longValueExact()); }
        catch (ArithmeticException invalid) { throw failure("SUBTITLE_INVALID"); }
    }

    private static NormalizeException failure(String code) { return new NormalizeException(code); }
}
