package com.launcher.util;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.function.BiConsumer;

public class HttpUtil {

    private static final int CONNECT_TIMEOUT = 20_000;
    private static final int READ_TIMEOUT    = 60_000;
    private static final int BUFFER          = 32_768;
    private static final int MAX_RETRIES     = 3;

    /** Fetch URL as String (for JSON manifests). Retries on failure. */
    public static String fetchString(String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpURLConnection c = open(url, null);
                try (InputStream in = c.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[BUFFER];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    return out.toString("UTF-8");
                } finally { c.disconnect(); }
            } catch (IOException e) {
                last = e;
                sleep(500L * (attempt + 1));
            }
        }
        throw last;
    }

    /**
     * Download url → dest, with SHA-1 verification and resume-skip.
     * If dest already exists and sha1 matches, skips the download entirely.
     * progress(bytesDownloaded, totalBytes)  — totalBytes may be -1.
     */
    public static void downloadFile(String url, Path dest,
                                    String expectedSha1,
                                    BiConsumer<Long, Long> progress) throws Exception {
        // Already downloaded and hash matches? Skip.
        if (expectedSha1 != null && Files.exists(dest) && Files.size(dest) > 0) {
            if (sha1Hex(dest).equalsIgnoreCase(expectedSha1)) return;
        }

        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");

        Exception last = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpURLConnection c = open(url, null);
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream();
                     OutputStream out = Files.newOutputStream(tmp)) {
                    byte[] buf = new byte[BUFFER];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        if (progress != null) progress.accept(done, total);
                    }
                } finally { c.disconnect(); }

                // Verify hash if provided
                if (expectedSha1 != null) {
                    String actual = sha1Hex(tmp);
                    if (!actual.equalsIgnoreCase(expectedSha1)) {
                        Files.deleteIfExists(tmp);
                        throw new IOException("SHA-1 mismatch for " + dest.getFileName()
                            + "\n  expected: " + expectedSha1
                            + "\n  actual:   " + actual);
                    }
                }

                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                return; // success

            } catch (Exception e) {
                last = e;
                Files.deleteIfExists(tmp);
                sleep(1000L * (attempt + 1));
            }
        }
        throw last;
    }

    /** POST JSON body, return response string. */
    public static String postJson(String url, String jsonBody, String bearerToken)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        if (bearerToken != null)
            c.setRequestProperty("Authorization", "Bearer " + bearerToken);
        c.setRequestProperty("User-Agent", "SalwyrrLauncher/2.0");
        try (OutputStream os = c.getOutputStream()) {
            os.write(jsonBody.getBytes("UTF-8"));
        }
        return readResponse(c);
    }

    /** POST form-encoded body, return response string. */
    public static String postForm(String url, String formBody) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "SalwyrrLauncher/2.0");
        try (OutputStream os = c.getOutputStream()) {
            os.write(formBody.getBytes("UTF-8"));
        }
        return readResponse(c);
    }

    /** GET with optional bearer token. */
    public static String get(String url, String bearerToken) throws IOException {
        HttpURLConnection c = open(url, bearerToken);
        try { return readResponse(c); } finally { c.disconnect(); }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static HttpURLConnection open(String url, String bearerToken) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setRequestProperty("User-Agent", "SalwyrrLauncher/2.0");
        if (bearerToken != null)
            c.setRequestProperty("Authorization", "Bearer " + bearerToken);
        c.connect();
        int code = c.getResponseCode();
        if (code >= 400)
            throw new IOException("HTTP " + code + " for " + url);
        return c;
    }

    private static String readResponse(HttpURLConnection c) throws IOException {
        InputStream in;
        try { in = c.getInputStream(); }
        catch (IOException e) { in = c.getErrorStream(); }
        if (in == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString().trim();
        }
    }

    public static String sha1Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
