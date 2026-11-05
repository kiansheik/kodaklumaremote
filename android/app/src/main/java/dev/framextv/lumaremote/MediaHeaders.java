package dev.framextv.lumaremote;

import org.json.JSONObject;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The only request headers a captured public stream may carry to the receiver. */
final class MediaHeaders {
    private MediaHeaders() { }

    static Map<String, String> observed(Map<String, String> source) {
        if (source == null) return Collections.emptyMap();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String name = entry.getKey();
            if (name != null && (name.equalsIgnoreCase("Cookie") || name.equalsIgnoreCase("Authorization")
                    || name.equalsIgnoreCase("Proxy-Authorization"))) return null;
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String name : new String[] {"Referer", "Origin", "User-Agent"}) {
            String value = header(source, name);
            if (value.isEmpty()) continue;
            try { result.put(name, validate(name, value)); }
            catch (Exception invalid) { return null; }
        }
        return Collections.unmodifiableMap(result);
    }

    static JSONObject validated(Object source) throws NativeBackend.BackendException {
        if (source == null || source == JSONObject.NULL) return new JSONObject();
        if (!(source instanceof JSONObject)) throw NativeBackend.fail("INVALID_MEDIA");
        JSONObject input = (JSONObject) source, result = new JSONObject();
        java.util.Iterator<?> keys = input.keys();
        while (keys.hasNext()) {
            Object next = keys.next();
            if (!(next instanceof String)) throw NativeBackend.fail("INVALID_MEDIA");
            String key = (String) next;
            if (!(key.equals("Referer") || key.equals("Origin") || key.equals("User-Agent")))
                throw NativeBackend.fail("INVALID_MEDIA");
            Object raw = input.opt(key);
            if (!(raw instanceof String)) throw NativeBackend.fail("INVALID_MEDIA");
            try { result.put(key, validate(key, (String) raw)); }
            catch (Exception invalid) { throw NativeBackend.fail("INVALID_MEDIA"); }
        }
        return result;
    }

    static JSONObject json(Map<String, String> headers) {
        JSONObject result = new JSONObject();
        if (headers == null) return result;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            try { result.put(entry.getKey(), entry.getValue()); } catch (Exception ignored) { }
        }
        return result;
    }

    static boolean same(Object first, Object second) throws NativeBackend.BackendException {
        JSONObject left = validated(first), right = validated(second);
        for (String name : new String[] {"Referer", "Origin", "User-Agent"}) {
            if (left.has(name) != right.has(name) || left.has(name) && !left.optString(name).equals(right.optString(name))) return false;
        }
        return true;
    }

    private static String validate(String name, String value) throws Exception {
        if (value == null || value.isEmpty() || value.length() > 1024) throw new Exception();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 32 || character == 127 || character >= 0x80) throw new Exception();
        }
        if (name.equals("User-Agent")) return value;
        if (name.equals("Referer")) {
            String safe = NativeBackend.publicUrl(value);
            if (!"https".equalsIgnoreCase(new URI(safe).getScheme())) throw new Exception();
            return safe;
        }
        URI origin = new URI(value);
        if (!"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null || origin.getPort() == 0 || origin.getPort() > 65535
                || origin.getPath() != null && !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))) throw new Exception();
        return origin.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + origin.getRawAuthority();
    }

    private static String header(Map<String, String> values, String expected) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (expected.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) return entry.getValue();
        }
        return "";
    }
}
