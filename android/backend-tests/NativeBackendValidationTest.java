package dev.framextv.lumaremote;

import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Host-JVM checks for the native transport's execution-free input boundaries. */
public final class NativeBackendValidationTest {
    private static Object call(String name, Class<?>[] types, Object... values) throws Exception {
        Method method = NativeBackend.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(null, values); }
        catch (InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }
    private static void rejects(String method, Class<?>[] types, String code, Object... values) throws Exception {
        try { call(method, types, values); throw new AssertionError("Input should have been rejected"); }
        catch (NativeBackend.BackendException failure) {
            if (!failure.code.equals(code)) throw new AssertionError("Unexpected fixed error code: " + failure.code);
        }
    }
    public static void main(String[] args) throws Exception {
        Class<?>[] string = { String.class }, object = { Object.class };
        for (String host : new String[] { "10.0.0.2", "172.16.0.1", "172.31.255.254", "192.168.1.2" }) {
            if (!Boolean.TRUE.equals(call("privateAddress", string, host))) throw new AssertionError("Private address rejected");
        }
        for (String host : new String[] { "127.0.0.1", "169.254.1.2", "8.8.8.8", "172.32.0.1", "luma.local", "192.168.01.2", "192.168.1.2\n", "http://192.168.1.2", "0x7f.0.0.1", "2130706433" }) {
            if (!Boolean.FALSE.equals(call("privateAddress", string, host))) throw new AssertionError("Non-private or ambiguous address accepted");
        }
        for (String url : new String[] { "https://media.example/video.m3u8?token=example", "https://8.8.8.8/video.mp4", "https://[2606:4700::1111]/media.webm" }) {
            if (!url.equals(call("publicUrl", object, url))) throw new AssertionError("Public URL changed");
        }
        for (String url : new String[] { "http://127.0.0.1/a", "http://192.168.1.2/a", "http://172.16.1.2/a", "http://10.0.0.1/a", "http://100.64.0.1/a", "http://169.254.1.2/a", "http://192.0.2.1/a", "http://[::1]/a", "http://[fe80::1]/a", "http://[2001:db8::1]/a", "http://projector.local/a", "http://localhost/a", "http://foo.lan/a", "http://0x7f.0.0.1/a", "http://2130706433/a", "http://0177.0.0.1/a", "https://user:password@media.example/a", "https://media.example/a|headers", "https://media.example/a#fragment", "https://media.example/a\n", "file:///tmp/video.mp4", "blob:https://media.example/id" }) {
            rejects("publicUrl", object, "INVALID_MEDIA", url);
        }
        Class<?>[] field = { ByteBuffer.class, int.class };
        ByteBuffer valid = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN).putInt(3).put(new byte[] { 1, 2, 3 }); valid.flip();
        if (((byte[]) call("appField", field, valid, 3)).length != 3 || valid.hasRemaining()) throw new AssertionError("App field framing failed");
        for (ByteBuffer invalid : new ByteBuffer[] { ByteBuffer.allocate(3), ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4).flip(), ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(-1).flip() }) {
            rejects("appField", field, "INVALID_RESPONSE", invalid, 3);
        }
        String clean = (String) call("label", new Class<?>[] { String.class, int.class }, "Português\nhttps://media.example/private \u202eBrasil", 100);
        if (clean.contains("https") || clean.contains("\n") || clean.contains("\u202e")) throw new AssertionError("Display metadata was not sanitized");
        Class<?>[] air = { int.class, int.class, int.class };
        String hover = (String) call("airMouseCommand", air, 640, 360, -1);
        if (!"GYROSCOPEEVENT\r\n640.0\r\n360.0\r\n-1\r\n\r\n".equals(hover)) throw new AssertionError("Air-mouse frame changed");
        String click = (String) call("airMouseClickCommand", new Class<?>[] { int.class, int.class }, 640, 360);
        if (!("GYROSCOPEEVENT\r\n640.0\r\n360.0\r\n0\r\n\r\n"
                + "GYROSCOPEEVENT\r\n640.0\r\n360.0\r\n1\r\n\r\n").equals(click)) throw new AssertionError("Air-mouse click frame changed");
        for (Object[] invalid : new Object[][] { {-1, 360, -1}, {640, -1, -1}, {8193, 360, -1}, {640, 8193, -1}, {640, 360, -2}, {640, 360, 2} }) {
            rejects("airMouseCommand", air, "INVALID_INPUT", invalid);
        }
        Class<?>[] cat = { JSONObject.class };
        JSONObject validCat = new JSONObject().put("active", true).put("manual", false)
                .put("sound", true).put("remainingSeconds", 720);
        JSONObject copiedCat = (JSONObject) call("catStatus", cat, validCat);
        if (!copiedCat.getBoolean("active") || copiedCat.getBoolean("manual")
                || !copiedCat.getBoolean("sound") || copiedCat.getInt("remainingSeconds") != 720) {
            throw new AssertionError("Valid Cat Mode status changed");
        }
        for (JSONObject invalid : new JSONObject[] {
                new JSONObject().put("active", "true").put("manual", false).put("sound", true).put("remainingSeconds", 720),
                new JSONObject().put("active", true).put("manual", false).put("remainingSeconds", 720),
                new JSONObject().put("active", true).put("manual", false).put("sound", true).put("remainingSeconds", 901),
                new JSONObject().put("active", true).put("manual", false).put("sound", true).put("remainingSeconds", 1.5),
        }) rejects("catStatus", cat, "RECEIVER_RESPONSE", invalid);
        System.out.println("Native backend validation boundaries passed.");
    }
}
