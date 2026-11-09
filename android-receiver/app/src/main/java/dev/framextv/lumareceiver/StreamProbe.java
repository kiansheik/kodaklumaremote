package dev.framextv.lumareceiver;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small uncached reads: a catalog success flag is not proof of reachable media. */
final class StreamProbe {
    static boolean usable(String url, String type, long deadline) {
        try {
            Read result = read(url, MediaRequest.HLS.equals(type) ? 131072 : 4096, deadline);
            if (MediaRequest.HLS.equals(type)) {
                for (int depth=0; depth<3; depth++) {
                    String playlist = new String(result.bytes, StandardCharsets.UTF_8).trim();
                    if (!playlist.startsWith("#EXTM3U")) return false;
                    String child = firstResource(playlist);
                    if (child == null) return false;
                    boolean media = playlist.contains("#EXTINF:");
                    result = read(new URI(result.url).resolve(child).toString(), media ? 1024 : 131072, deadline);
                    if (media) return mediaBytes(result.bytes);
                }
                return false;
            }
            if (MediaRequest.DASH.equals(type)) return new String(result.bytes,StandardCharsets.UTF_8).contains("<MPD");
            return mediaBytes(result.bytes);
        } catch (Exception failed) { return false; }
    }
    static String firstResource(String playlist) {
        if (!playlist.trim().startsWith("#EXTM3U")) return null;
        boolean expected=false;
        for(String raw:playlist.split("\\r?\\n")) {
            String line=raw.trim();
            if(line.startsWith("#EXT-X-STREAM-INF:") || line.startsWith("#EXTINF:")) expected=true;
            else if(!line.isEmpty() && !line.startsWith("#") && expected) return line;
        }
        return null;
    }
    static boolean mediaBytes(byte[] bytes) {
        if(bytes.length<16) return false;
        String prefix=new String(bytes,0,Math.min(bytes.length,128),StandardCharsets.US_ASCII).trim().toLowerCase(Locale.ROOT);
        return !(prefix.startsWith("<") || prefix.startsWith("{") || prefix.startsWith("[") || prefix.startsWith("#extm3u"));
    }
    private static final class Read {
        final String url; final byte[] bytes;
        Read(String url,byte[] bytes){this.url=url;this.bytes=bytes;}
    }
    private static Read read(String raw,int limit,long deadline) throws Exception {
        String url=MediaRequest.httpUrl(raw);
        for(int redirects=0;redirects<4;redirects++) {
            if(Thread.currentThread().isInterrupted() || System.currentTimeMillis()>=deadline) throw new IOException("cancelled");
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection(Proxy.NO_PROXY);
            int timeout=(int)Math.max(1,Math.min(2500,deadline-System.currentTimeMillis()));
            Timer timer=new Timer(true); timer.schedule(new TimerTask(){public void run(){c.disconnect();}},timeout);
            try {
                c.setUseCaches(false); c.setInstanceFollowRedirects(false); c.setConnectTimeout(timeout); c.setReadTimeout(timeout);
                c.setRequestProperty("Cache-Control","no-cache"); c.setRequestProperty("Accept-Encoding","identity");
                c.setRequestProperty("User-Agent","LumaReceiver/1");
                if(limit<131072) c.setRequestProperty("Range","bytes=0-"+(limit-1));
                int status=c.getResponseCode();
                if(status>=300&&status<400) {String location=c.getHeaderField("Location");if(location==null)throw new IOException();
                    url=MediaRequest.httpUrl(new URI(url).resolve(location).toString());continue;}
                if(status!=200&&status!=206) throw new IOException("http");
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                try(InputStream in=c.getInputStream()) {
                    byte[] buf=new byte[4096];int count;
                    while(out.size()<limit&&(count=in.read(buf,0,Math.min(buf.length,limit-out.size())))>=0) {
                        if(Thread.currentThread().isInterrupted()||System.currentTimeMillis()>=deadline)throw new IOException("cancelled");
                        out.write(buf,0,count);
                    }
                }
                return new Read(url,out.toByteArray());
            } finally {timer.cancel();c.disconnect();}
        }
        throw new IOException("redirects");
    }
}
