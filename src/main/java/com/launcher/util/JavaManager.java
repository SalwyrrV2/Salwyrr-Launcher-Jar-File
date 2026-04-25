package com.launcher.util;

import com.launcher.Constants;
import com.launcher.download.HttpUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resolves (and if necessary downloads) the correct Java runtime for a given
 * Minecraft version, using Mojang's official Java runtime manifest.
 *
 * Java component selection:
 *   MC 1.17+  → java-runtime-gamma  (Java 17)
 *   MC 1.16   → java-runtime-alpha  (Java 16)
 *   otherwise → jre-legacy          (Java 8)
 *
 * The JRE is stored under Constants.JAVA_DIR/<component>/
 * and is fully self-contained — no system Java required at runtime.
 */
public class JavaManager {

    // Mojang's runtime manifest endpoint
    private static final String RUNTIME_MANIFEST_URL =
        "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    /**
     * Returns the absolute path to the java executable for the given MC version.
     * Downloads the JRE if it is not already present.
     */
    public static String resolveJava(String mcVersion, Consumer<String> log) throws Exception {
        String component = javaComponent(mcVersion);
        Path javaHome    = Constants.JAVA_DIR.resolve(component);
        Path javaExe     = javaExecutable(javaHome);

        if (Files.exists(javaExe)) {
            log.accept("[Java] Using cached JRE: " + javaExe);
            return javaExe.toAbsolutePath().toString();
        }

        log.accept("[Java] JRE not found — downloading " + component + " …");
        downloadJre(component, javaHome, log);

        if (!Files.exists(javaExe))
            throw new FileNotFoundException("Java executable not found after download: " + javaExe);

        // Make executable on Unix
        tryChmod(javaExe);
        log.accept("[Java] JRE ready: " + javaExe);
        return javaExe.toAbsolutePath().toString();
    }

    /**
     * Returns the major version number reported by the given java binary.
     * Returns 8 on failure (safe fallback — no module flags will be added).
     */
    public static int getJavaMajorVersion(String javaExe) {
        try {
            Process p = new ProcessBuilder(javaExe, "-XshowSettings:property",
                                           "-version")
                .redirectErrorStream(true).start();
            String out = new String(readStreamBytes(p.getInputStream()));
            p.waitFor();

            // "java.version = 17.0.9" or "17" or "1.8.0_392"
            for (String line : out.split("\\R")) {
                if (line.contains("java.version")) {
                    String ver = line.replaceAll(".*=\\s*", "").trim();
                    return parseMajor(ver);
                }
            }
            // fallback: parse first line of `java -version` output
            for (String line : out.split("\\R")) {
                if (line.contains("version")) {
                    String[] parts = line.split("\"");
                    if (parts.length >= 2) return parseMajor(parts[1]);
                }
            }
        } catch (Exception ignored) {}
        return 8;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Pick Mojang's component name based on MC version. */
    private static String javaComponent(String mcVersion) {
        int[] v = parseVersion(mcVersion);
        int major = v[0];
        int minor = v[1];
        // New-style versioning (26.x, 27.x …) — Mojang dropped the "1." prefix
        if (major >= 26) return "java-runtime-epsilon"; // MC 26+    → Java 25
        // Legacy 1.x versioning
        if (minor >= 26) return "java-runtime-epsilon"; // MC 1.26+  → Java 25
        if (minor >= 21) return "java-runtime-delta";   // MC 1.21+  → Java 21
        if (minor >= 17) return "java-runtime-gamma";   // MC 1.17+  → Java 17
        if (minor >= 16) return "java-runtime-alpha";   // MC 1.16   → Java 16
        return "jre-legacy";                            // MC ≤ 1.15 → Java 8
    }

    /** Download and unpack the JRE for the given component. */
    private static void downloadJre(String component, Path dest,
                                    Consumer<String> log) throws Exception {
        String os  = mojangOs();
        String arch = mojangArch();
        String osKey = os + "-" + arch;   // e.g. "windows-x64"

        log.accept("[Java] Fetching runtime manifest …");
        String manifestJson = HttpUtil.fetchString(RUNTIME_MANIFEST_URL);
        JSONObject root     = new JSONObject(manifestJson);

        // Navigate: root → <osKey> → <component> → [0] → manifest url
        JSONObject osBlock = root.optJSONObject(osKey);
        if (osBlock == null)
            throw new IOException("No JRE entry for OS key: " + osKey);

        JSONArray componentArr = osBlock.optJSONArray(component);
        if (componentArr == null || componentArr.isEmpty())
            throw new IOException("No JRE entry for component: " + component
                                  + " on " + osKey);

        JSONObject entry       = componentArr.getJSONObject(0);
        JSONObject manifest    = entry.getJSONObject("manifest");
        String     manifestUrl = manifest.getString("url");

        log.accept("[Java] Fetching file list …");
        String fileListJson = HttpUtil.fetchString(manifestUrl);
        JSONObject files    = new JSONObject(fileListJson).getJSONObject("files");

        int total   = files.length();
        int current = 0;
        for (String filePath : files.keySet()) {
            current++;
            JSONObject fileEntry = files.getJSONObject(filePath);
            String type = fileEntry.optString("type", "file");

            Path target = dest.resolve(filePath.replace("/", File.separator));

            if ("directory".equals(type)) {
                Files.createDirectories(target);
                continue;
            }
            if ("link".equals(type)) {
                // Symlinks — skip on Windows, create on Unix
                if (!Constants.isWindows()) {
                    String linkTarget = fileEntry.optString("target", "");
                    if (!linkTarget.isEmpty()) {
                        Files.createDirectories(target.getParent());
                        if (!Files.exists(target))
                            Files.createSymbolicLink(target, Path.of(linkTarget));
                    }
                }
                continue;
            }

            // Regular file
            JSONObject downloads = fileEntry.optJSONObject("downloads");
            if (downloads == null) continue;
            JSONObject raw = downloads.optJSONObject("raw");
            if (raw == null) continue;

            String url  = raw.getString("url");
            String sha1 = raw.optString("sha1", null);

            if (current % 50 == 0 || current == total)
                log.accept("[Java] Downloading files … " + current + "/" + total);
            log.accept("[JAVA_PROGRESS] " + current + " " + total);

            Files.createDirectories(target.getParent());
            HttpUtil.downloadFile(url, target, sha1, null);

            // Restore exec bit
            boolean isExec = fileEntry.optBoolean("executable", false);
            if (isExec && !Constants.isWindows()) tryChmod(target);
        }

        log.accept("[Java] Download complete (" + total + " files).");
    }

    /** Path to the java binary inside a JRE home directory. */
    private static Path javaExecutable(Path javaHome) {
        // Mojang unpacks into <component>/bin/java (or java.exe)
        // Some components have an extra level, e.g. java-runtime-gamma/bin/java
        String exe = Constants.isWindows() ? "bin\\java.exe" : "bin/java";
        return javaHome.resolve(exe);
    }

    private static void tryChmod(Path path) {
        try {
            Files.setPosixFilePermissions(path,
                PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (Exception ignored) {}
    }

    /** Parse "17.0.9" or "1.8.0_392" → major version int. */
    private static int parseMajor(String ver) {
        try {
            String[] parts = ver.split("[._]");
            int first = Integer.parseInt(parts[0]);
            if (first == 1 && parts.length > 1)
                return Integer.parseInt(parts[1]); // 1.8 → 8
            return first;
        } catch (Exception e) { return 8; }
    }

    /** Parse MC version string "1.20.4" or "26.2-snapshot-4" → int[]{1, 20, 4}. */
    private static int[] parseVersion(String v) {
        // Strip snapshot/pre-release suffix (everything from first '-' onward)
        int dash = v.indexOf('-');
        if (dash != -1) v = v.substring(0, dash);
        String[] parts = v.replaceAll("[^0-9.]", "").split("\\.");
        int[] out = new int[3];
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try { out[i] = Integer.parseInt(parts[i]); } catch (Exception ignored) {}
        }
        return out;
    }

    /** Map OS name to Mojang's key format. */
    private static String mojangOs() {
        if (Constants.isWindows()) return "windows";
        if (Constants.isMac())     return "mac-os";
        return "linux";
    }

    /** Map CPU arch to Mojang's key format. */
    private static String mojangArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) return "arm64";
        return "x64";
    }

    private static byte[] readStreamBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
        return buffer.toByteArray();
    }
}
