package dev.framextv.lumareceiver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Uses only this projector's existing EShare service, never a remote host. */
final class LocalProjectorKeys {
    interface Result { void done(boolean sent); }
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8), work -> new Thread(work, "projector-keys"));
    private Socket socket;
    private volatile boolean ready;
    private volatile String state = "notChecked";

    void probe() { enqueue(() -> { try { connect(); } catch (IOException failure) { closeSocket(); } }); }
    boolean ready() { return ready; }
    String state() { return state; }
    boolean send(int key, Result result) {
        return enqueue(() -> {
            boolean sent = false;
            try { connect(); write("KEYEVENT\r\n" + key + "\r\n\r\n"); sent = true; state = "sentUnconfirmed"; }
            catch (IOException failure) { closeSocket(); }
            if (result != null) result.done(sent);
        });
    }
    private boolean enqueue(Runnable action) {
        try { worker.execute(action); return true; }
        catch (java.util.concurrent.RejectedExecutionException full) { return false; }
    }
    private void connect() throws IOException {
        if (socket != null && !socket.isClosed() && ready) return;
        closeSocket();
        socket = new Socket(); socket.connect(new InetSocketAddress("127.0.0.1", 8121), 700);
        socket.setTcpNoDelay(true);
        if (!exchange("getFeatures\r\nLUMA Controller\r\n\r\n").startsWith("server_features:"))
            throw new IOException("Unexpected projector service");
        if (!exchange("GETPASSWORDCONFIG\r\neshare\r\n\r\n").trim().matches("(?i)configed\\s+none"))
            throw new IOException("Projector service requires pairing");
        ready = true; state = "ready";
    }
    private String exchange(String command) throws IOException {
        write(command);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[1024];
        while (true) {
            socket.setSoTimeout(bytes.size() == 0 ? 700 : 60);
            int count;
            try { count = socket.getInputStream().read(buffer); }
            catch (SocketTimeoutException end) { if (bytes.size() > 0) break; throw end; }
            if (count < 0 || bytes.size() + count > 8192) throw new IOException("Invalid projector reply");
            bytes.write(buffer, 0, count);
        }
        return bytes.toString("UTF-8");
    }
    private void write(String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
    }
    private void closeSocket() {
        ready = false; state = "unavailable";
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        socket = null;
    }
    void close() { enqueue(this::closeSocket); worker.shutdown(); }
}
