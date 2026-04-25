package com.launcher.download;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.function.BiConsumer;

public class HttpUtil {

    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT    = 30_000;
    private static final int BUFFER_SIZE     = 8192;

    /**
     * Fetch a URL as a String (used for JSON manifests).
     */
    public static String fetchString(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Download a URL to a local file, with optional progress callback.
     * progress(bytesDownloaded, totalBytes)  — totalBytes is -1 if unknown.
     * Skips download if file already exists AND sha1 matches (pass null to skip check).
     */
    public static void downloadFile(String url, Path dest, String expectedSha1,
                                    BiConsumer<Long, Long> progress) throws IOException {

        // Skip if already present and hash matches
        if (expectedSha1 != null && Files.exists(dest)) {
            try {
                String actual = sha1Hex(dest);
                if (actual.equalsIgnoreCase(expectedSha1)) return; // already good
            } catch (Exception ignored) {}
        }

        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");

        HttpURLConnection conn = open(url);
        long total = conn.getContentLengthLong();

        try (InputStream  in  = conn.getInputStream();
             OutputStream out = Files.newOutputStream(tmp)) {

            byte[] buf = new byte[BUFFER_SIZE];
            long done = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                if (progress != null) progress.accept(done, total);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        } finally {
            conn.disconnect();
        }

        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", "SalwyrrFix/1.0");
        conn.connect();
        int code = conn.getResponseCode();
        if (code != 200)
            throw new IOException("HTTP " + code + " for " + url);
        return conn;
    }

    public static String sha1Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
