package dev.framextv.lumareceiver;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tolerant caption decoding and parsing for the receiver's own renderer:
 * WebVTT, SRT, ASS/SSA and basic TTML in any common encoding. Malformed cues
 * are skipped individually; a file is only rejected when nothing usable remains.
 * Pure Java so it is unit-testable.
 */
final class CaptionParser {
    static final int MAX_CUES = 20_000;

    static final class Cue {
        final long start, end; final String text;
        Cue(long start, long end, String text) { this.start = start; this.end = end; this.text = text; }
    }

    private static final Pattern TIME = Pattern.compile("(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{1,2})(?:[.,:](\\d{1,3}))?");
    private static final Pattern ARROW = Pattern.compile("^\\s*(\\S+)\\s*-->\\s*(\\S+)");
    private static final Pattern TAG = Pattern.compile("<[^>\\n]{0,200}>");
    private static final Pattern ASS_OVERRIDE = Pattern.compile("\\{[^}\\n]{0,400}\\}");
    private static final Pattern TTML_P = Pattern.compile("<(?:tt:)?p\\b([^>]*)>(.*?)</(?:tt:)?p>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("\\b(begin|end|dur)\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|amp|lt|gt|quot|apos|nbsp);");

    static List<Cue> parse(byte[] body, String contentType, String language) throws ApiException {
        String text = decode(body, contentType, language);
        List<Cue> cues;
        String head = text.length() > 4000 ? text.substring(0, 4000) : text;
        if (head.contains("[Script Info]") || head.contains("[Events]") || head.contains("\nDialogue:")) cues = parseAss(text);
        else if (head.contains("<tt") || head.contains("<tt:tt")) cues = parseTtml(text);
        else cues = parseTimed(text);
        if (cues.isEmpty()) throw new ApiException("SUBTITLE_INVALID", 422);
        Collections.sort(cues, (a, b) -> Long.compare(a.start, b.start));
        return repairReversedRtl(cues);
    }

    // ---- decoding ----

    static String decode(byte[] body, String contentType, String language) {
        int length = body.length;
        if (length >= 3 && (body[0] & 0xff) == 0xEF && (body[1] & 0xff) == 0xBB && (body[2] & 0xff) == 0xBF)
            return new String(body, 3, length - 3, StandardCharsets.UTF_8);
        if (length >= 2 && (body[0] & 0xff) == 0xFF && (body[1] & 0xff) == 0xFE) return new String(body, 2, length - 2, StandardCharsets.UTF_16LE);
        if (length >= 2 && (body[0] & 0xff) == 0xFE && (body[1] & 0xff) == 0xFF) return new String(body, 2, length - 2, StandardCharsets.UTF_16BE);
        Charset utf16 = utf16WithoutBom(body);
        if (utf16 != null) return new String(body, utf16);
        String strict = strict(body, StandardCharsets.UTF_8);
        if (strict != null) return strict;
        Charset declared = declared(contentType);
        if (declared != null && !declared.equals(StandardCharsets.UTF_8)) {
            String value = strict(body, declared);
            if (value != null) return value;
        }
        Charset legacy = legacy(language);
        String value = strict(body, legacy);
        return value != null ? value : new String(body, legacy);
    }

    private static Charset utf16WithoutBom(byte[] body) {
        int sample = Math.min(body.length, 4000) & ~1, evenZero = 0, oddZero = 0;
        if (sample < 40) return null;
        for (int i = 0; i < sample; i += 2) { if (body[i] == 0) evenZero++; if (body[i + 1] == 0) oddZero++; }
        int half = sample / 2;
        if (oddZero > half * 0.4 && evenZero < half * 0.05) return StandardCharsets.UTF_16LE;
        if (evenZero > half * 0.4 && oddZero < half * 0.05) return StandardCharsets.UTF_16BE;
        return null;
    }

    private static String strict(byte[] body, Charset charset) {
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException invalid) { return null; }
    }

    private static Charset declared(String contentType) {
        if (contentType == null) return null;
        Matcher match = Pattern.compile("charset\\s*=\\s*\"?([A-Za-z0-9._:-]{1,40})", Pattern.CASE_INSENSITIVE).matcher(contentType);
        if (!match.find()) return null;
        try { return Charset.forName(match.group(1)); } catch (Exception unsupported) { return null; }
    }

    /** The usual pre-Unicode code page for a language, used only when UTF-8 fails. */
    static Charset legacy(String language) {
        String lang = language == null ? "" : language.toLowerCase(Locale.ROOT);
        String base = lang.contains("-") ? lang.substring(0, lang.indexOf('-')) : lang;
        String name;
        switch (base) {
            case "ru": case "bg": case "uk": case "be": case "sr": case "mk": case "kk": name = "windows-1251"; break;
            case "el": name = "windows-1253"; break;
            case "tr": case "az": name = "windows-1254"; break;
            case "he": case "iw": case "yi": name = "windows-1255"; break;
            case "ar": case "fa": case "ur": name = "windows-1256"; break;
            case "lt": case "lv": case "et": name = "windows-1257"; break;
            case "vi": name = "windows-1258"; break;
            case "th": name = "x-windows-874"; break;
            case "cs": case "sk": case "pl": case "hu": case "ro": case "hr": case "sl": case "bs": case "sq": name = "windows-1250"; break;
            case "ja": name = "Shift_JIS"; break;
            case "ko": name = "EUC-KR"; break;
            case "zh": name = lang.contains("tw") || lang.contains("hk") || lang.contains("hant") ? "Big5" : "GB18030"; break;
            default: name = "windows-1252";
        }
        try { return Charset.forName(name); } catch (Exception unsupported) { return StandardCharsets.ISO_8859_1; }
    }

    // ---- WebVTT and SRT ----

    static List<Cue> parseTimed(String text) {
        List<Cue> cues = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length && cues.size() < MAX_CUES; i++) {
            Matcher arrow = ARROW.matcher(lines[i]);
            if (!arrow.find()) continue;
            long start = time(arrow.group(1)), end = time(arrow.group(2));
            StringBuilder body = new StringBuilder();
            int j = i + 1;
            for (; j < lines.length && !lines[j].trim().isEmpty(); j++) {
                if (ARROW.matcher(lines[j]).find()) break; // missing blank line before the next cue
                // ...where the line before it is that cue's SRT number.
                if (lines[j].trim().matches("\\d{1,6}") && j + 1 < lines.length && ARROW.matcher(lines[j + 1]).find()) break;
                if (body.length() > 0) body.append('\n');
                body.append(lines[j]);
            }
            i = j - 1;
            add(cues, start, end, clean(body.toString()));
        }
        return cues;
    }

    /** Milliseconds, or -1. Accepts h:mm:ss.mmm, mm:ss.mmm, comma decimals and 1-3 digit fractions. */
    static long time(String value) {
        Matcher match = TIME.matcher(value.trim());
        if (!match.matches()) return -1;
        long hours = match.group(1) == null ? 0 : Long.parseLong(match.group(1));
        long minutes = Long.parseLong(match.group(2)), seconds = Long.parseLong(match.group(3));
        if (minutes > 59 && match.group(1) != null || seconds > 59) return -1;
        String fraction = match.group(4) == null ? "0" : match.group(4);
        long millis = Long.parseLong((fraction + "00").substring(0, 3));
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis;
    }

    // ---- ASS / SSA ----

    static List<Cue> parseAss(String text) {
        List<Cue> cues = new ArrayList<>();
        int startColumn = 1, endColumn = 2, textColumn = 9, columns = 10;
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = raw.trim();
            if (line.startsWith("Format:")) {
                String[] names = line.substring(7).split(",");
                for (int i = 0; i < names.length; i++) {
                    String name = names[i].trim().toLowerCase(Locale.ROOT);
                    if (name.equals("start")) startColumn = i; else if (name.equals("end")) endColumn = i; else if (name.equals("text")) textColumn = i;
                }
                columns = names.length;
            } else if (line.startsWith("Dialogue:") && cues.size() < MAX_CUES) {
                String[] fields = line.substring(9).split(",", columns);
                if (fields.length <= Math.max(textColumn, Math.max(startColumn, endColumn))) continue;
                String body = ASS_OVERRIDE.matcher(fields[textColumn]).replaceAll("")
                    .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ");
                add(cues, time(fields[startColumn]), time(fields[endColumn]), clean(body));
            }
        }
        return cues;
    }

    // ---- TTML (basic) ----

    static List<Cue> parseTtml(String text) {
        List<Cue> cues = new ArrayList<>();
        Matcher paragraph = TTML_P.matcher(text);
        while (paragraph.find() && cues.size() < MAX_CUES) {
            long begin = -1, end = -1, duration = -1;
            Matcher attribute = ATTR.matcher(paragraph.group(1));
            while (attribute.find()) {
                long value = ttmlTime(attribute.group(2));
                if (attribute.group(1).equals("begin")) begin = value; else if (attribute.group(1).equals("end")) end = value; else duration = value;
            }
            if (end < 0 && begin >= 0 && duration >= 0) end = begin + duration;
            String body = paragraph.group(2).replaceAll("(?i)<(?:tt:)?br\\s*/?>", "\n");
            add(cues, begin, end, clean(body));
        }
        return cues;
    }

    private static long ttmlTime(String value) {
        String trimmed = value.trim();
        try {
            if (trimmed.endsWith("ms")) return (long) Double.parseDouble(trimmed.substring(0, trimmed.length() - 2));
            if (trimmed.endsWith("s")) return (long) (Double.parseDouble(trimmed.substring(0, trimmed.length() - 1)) * 1000);
            if (trimmed.endsWith("t")) return Long.parseLong(trimmed.substring(0, trimmed.length() - 1)) / 10_000; // 10 MHz ticks
        } catch (NumberFormatException invalid) { return -1; }
        return time(trimmed);
    }

    // ---- shared ----

    private static void add(List<Cue> cues, long start, long end, String text) {
        if (start < 0 || end <= start || text.isEmpty() || end - start > 60L * 60 * 1000) return;
        cues.add(new Cue(start, end, text));
    }

    static String clean(String text) {
        // ASS-style overrides such as {\an8} also appear inside some WebVTT/SRT files.
        String stripped = TAG.matcher(ASS_OVERRIDE.matcher(text).replaceAll("")).replaceAll("");
        Matcher entity = ENTITY.matcher(stripped);
        StringBuffer out = new StringBuffer();
        while (entity.find()) {
            String name = entity.group(1), replacement;
            if (name.startsWith("#x")) replacement = codePoint(Integer.parseInt(name.substring(2), 16));
            else if (name.startsWith("#")) replacement = codePoint(Integer.parseInt(name.substring(1)));
            else replacement = name.equals("amp") ? "&" : name.equals("lt") ? "<" : name.equals("gt") ? ">" : name.equals("quot") ? "\"" : name.equals("apos") ? "'" : " ";
            entity.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        entity.appendTail(out);
        StringBuilder lines = new StringBuilder();
        for (String line : out.toString().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (lines.length() > 0) lines.append('\n');
            lines.append(trimmed);
        }
        return lines.toString();
    }

    private static final Pattern LEADING = Pattern.compile("^([\\p{Cf}\\s]*)([.,!?:;\\-\u2026\u060C\u061F\"'\u2014]+)(.*?)([.,!?:;\\-\u2026\u060C\u061F\"'\u2014]*)([\\p{Cf}\\s]*)$", Pattern.DOTALL);

    private static boolean rtl(String line) {
        for (int i = 0; i < line.length(); i++) {
            byte direction = Character.getDirectionality(line.charAt(i));
            if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true;
            if (direction == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false;
        }
        return false;
    }

    private static boolean terminal(char c) { return c == '.' || c == '!' || c == '?' || c == '\u2026' || c == '\u061F'; }

    /**
     * Many Hebrew/Arabic files were authored for players without bidi support and
     * store sentence punctuation at the wrong end (".שלום" for "שלום."). Detect that
     * per file (correct text never starts lines with a full stop more often than it
     * ends them) and swap the leading and trailing punctuation runs back.
     */
    static List<Cue> repairReversedRtl(List<Cue> cues) {
        int leading = 0, trailing = 0;
        for (Cue cue : cues) for (String line : cue.text.split("\n")) {
            String core = line.replaceAll("[\\p{Cf}\\s]", "");
            if (core.isEmpty() || !rtl(core)) continue;
            if (terminal(core.charAt(0))) leading++;
            if (terminal(core.charAt(core.length() - 1))) trailing++;
        }
        if (leading < 3 || leading <= trailing) return cues;
        List<Cue> repaired = new ArrayList<>(cues.size());
        for (Cue cue : cues) {
            StringBuilder text = new StringBuilder();
            for (String line : cue.text.split("\n")) {
                if (text.length() > 0) text.append('\n');
                Matcher parts = LEADING.matcher(line);
                if (rtl(line) && parts.matches()) text.append(parts.group(1)).append(parts.group(4)).append(parts.group(3)).append(parts.group(2)).append(parts.group(5));
                else text.append(line);
            }
            repaired.add(new Cue(cue.start, cue.end, text.toString()));
        }
        return repaired;
    }

    private static String codePoint(int value) {
        return Character.isValidCodePoint(value) && value != 0 ? new String(Character.toChars(value)) : "";
    }

    /** Text of every cue active at {@code positionMs}, in start order, or "". */
    static String textAt(List<Cue> cues, long positionMs) {
        int low = 0, high = cues.size() - 1, last = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (cues.get(mid).start <= positionMs) { last = mid; low = mid + 1; } else high = mid - 1;
        }
        if (last < 0) return "";
        StringBuilder text = new StringBuilder();
        for (int i = Math.max(0, last - 20); i <= last; i++) {
            Cue cue = cues.get(i);
            if (cue.start <= positionMs && positionMs < cue.end) { if (text.length() > 0) text.append('\n'); text.append(cue.text); }
        }
        return text.toString();
    }
}
