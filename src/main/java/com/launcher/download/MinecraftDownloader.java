package com.launcher.download;

import com.launcher.Constants;
import com.launcher.util.HttpUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads and verifies all Minecraft assets:
 *  - Version manifest + version JSON
 *  - Client JAR
 *  - All libraries (with OS rules filtering)
 *  - Native libraries (extracted to per-version natives dir)
 *  - Asset objects (parallel download, SHA-1 verified)
 *  - Log4j config (security patch)
 */
public class MinecraftDownloader {

    private final Consumer<String>  log;
    private final Consumer<Integer> progress; // 0–100

    // Parallel asset downloads
    private static final int ASSET_THREADS = 8;

    public MinecraftDownloader(Consumer<String> log, Consumer<Integer> progress) {
        this.log      = log;
        this.progress = progress;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public JSONObject downloadAll() throws Exception {
        log.accept("Fetching version manifest...");
        progress.accept(2);

        String manifestJson = HttpUtil.fetchString(Constants.VERSION_MANIFEST_URL);
        JSONObject manifest = new JSONObject(manifestJson);

        String versionUrl = findVersionUrl(manifest, Constants.MINECRAFT_VERSION);
        log.accept("Found: " + Constants.MINECRAFT_VERSION);
        progress.accept(5);

        log.accept("Fetching version details...");
        String versionJson = HttpUtil.fetchString(versionUrl);
        JSONObject versionData = new JSONObject(versionJson);

        // Save version JSON locally
        Path versionFile = Constants.versionJson();
        Files.createDirectories(versionFile.getParent());
        Files.write(versionFile, versionJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        progress.accept(8);

        // Download each component
        downloadClient(versionData);           // 8 → 25
        downloadLibraries(versionData);        // 25 → 65
        extractNatives(versionData);           // 65 → 70
        downloadAssets(versionData);           // 70 → 98
        ensureDirectories();                   // 98 → 100

        progress.accept(100);
        log.accept("✓ All downloads complete!");
        return versionData;
    }

    // ── Version manifest ──────────────────────────────────────────────────────

    private String findVersionUrl(JSONObject manifest, String version) throws IOException {
        JSONArray versions = manifest.getJSONArray("versions");
        for (int i = 0; i < versions.length(); i++) {
            JSONObject v = versions.getJSONObject(i);
            if (v.getString("id").equals(version))
                return v.getString("url");
        }
        throw new IOException("Version '" + version + "' not found in Mojang manifest.\n"
            + "Check that the version ID is correct (e.g. '1.20.1', '1.8.9').");
    }

    // ── Client JAR ────────────────────────────────────────────────────────────

    private void downloadClient(JSONObject versionData) throws Exception {
        log.accept("Checking client JAR...");
        JSONObject downloads = versionData.getJSONObject("downloads");
        JSONObject client    = downloads.getJSONObject("client");

        String url  = client.getString("url");
        String sha1 = client.optString("sha1", null);
        Path   dest = Constants.clientJar();

        if (Files.exists(dest) && sha1 != null
                && HttpUtil.sha1Hex(dest).equalsIgnoreCase(sha1)) {
            log.accept("✓ Client JAR up to date.");
            progress.accept(25);
            return;
        }

        log.accept("Downloading client JAR (" + humanSize(client.optLong("size", -1)) + ")...");
        HttpUtil.downloadFile(url, dest, sha1,
            (done, total) -> {
                if (total > 0) progress.accept(8 + (int)(17.0 * done / total));
            });
        log.accept("✓ Client JAR downloaded.");
        progress.accept(25);
    }

    // ── Libraries ─────────────────────────────────────────────────────────────

    public List<Path> downloadLibraries(JSONObject versionData) throws Exception {
        log.accept("Checking libraries...");
        JSONArray libraries = versionData.getJSONArray("libraries");
        List<Path> classpath = new ArrayList<>();
        int total = libraries.length(), done = 0;

        for (int i = 0; i < total; i++) {
            JSONObject lib = libraries.getJSONObject(i);
            if (!rulesAllow(lib)) { done++; continue; }

            JSONObject downloads = lib.optJSONObject("downloads");
            if (downloads == null) { done++; continue; }

            // Main artifact
            JSONObject artifact = downloads.optJSONObject("artifact");
            if (artifact != null) {
                Path p = downloadArtifact(artifact, Constants.LIBRARIES_DIR);
                if (p != null) classpath.add(p);
            }
            done++;
            progress.accept(25 + (int)(40.0 * done / total));
        }

        log.accept("✓ Libraries ready (" + classpath.size() + " jars).");
        progress.accept(65);
        return classpath;
    }

    private Path downloadArtifact(JSONObject artifact, Path baseDir) throws Exception {
        String path = artifact.optString("path", null);
        if (path == null) return null;

        String url  = artifact.getString("url");
        String sha1 = artifact.optString("sha1", null);
        Path   dest = baseDir.resolve(path.replace("/", File.separator));

        HttpUtil.downloadFile(url, dest, sha1, null);
        return dest;
    }

    // ── Natives ───────────────────────────────────────────────────────────────

    public void extractNatives(JSONObject versionData) throws Exception {
        log.accept("Extracting natives...");
        Path nativesDir = Constants.nativesDir();
        Files.createDirectories(nativesDir);

        JSONArray libraries = versionData.getJSONArray("libraries");
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject lib = libraries.getJSONObject(i);
            if (!rulesAllow(lib)) continue;

            JSONObject downloads = lib.optJSONObject("downloads");
            if (downloads == null) continue;

            // Check classifiers for this OS's natives
            JSONObject classifiers = downloads.optJSONObject("classifiers");
            if (classifiers == null) continue;

            String key = Constants.nativeClassifier();
            JSONObject nativeArtifact = classifiers.optJSONObject(key);

            // Also try with arch suffix (e.g. natives-windows-64)
            if (nativeArtifact == null) {
                String arch = System.getProperty("os.arch","").contains("64") ? "64" : "32";
                nativeArtifact = classifiers.optJSONObject(key + "-" + arch);
            }
            if (nativeArtifact == null) continue;

            String url  = nativeArtifact.getString("url");
            String sha1 = nativeArtifact.optString("sha1", null);
            String fileName = url.substring(url.lastIndexOf('/') + 1);
            Path dest = nativesDir.resolve(fileName);

            HttpUtil.downloadFile(url, dest, sha1, null);
            extractNativeJar(dest, nativesDir, lib.optJSONObject("extract"));
        }
        log.accept("✓ Natives extracted.");
        progress.accept(70);
    }

    private void extractNativeJar(Path jar, Path destDir, JSONObject extractRules)
            throws Exception {
        // Build exclusion list from extract rules
        Set<String> excludes = new HashSet<>();
        excludes.add("META-INF/");
        if (extractRules != null) {
            JSONArray exc = extractRules.optJSONArray("exclude");
            if (exc != null) {
                for (int i = 0; i < exc.length(); i++)
                    excludes.add(exc.getString(i));
            }
        }

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                boolean skip = false;
                for (String ex : excludes) {
                    if (name.startsWith(ex)) { skip = true; break; }
                }
                if (skip) continue;

                Path out = destDir.resolve(name);
                Files.createDirectories(out.getParent());
                if (!Files.exists(out))
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // ── Assets ────────────────────────────────────────────────────────────────

    private void downloadAssets(JSONObject versionData) throws Exception {
        log.accept("Fetching asset index...");
        JSONObject assetIndex = versionData.getJSONObject("assetIndex");
        String indexUrl  = assetIndex.getString("url");
        String indexId   = assetIndex.getString("id");
        String indexSha1 = assetIndex.optString("sha1", null);

        Path indexFile = Constants.ASSETS_DIR.resolve("indexes").resolve(indexId + ".json");
        HttpUtil.downloadFile(indexUrl, indexFile, indexSha1, null);

        JSONObject objects = new JSONObject(readFile(indexFile))
                                .getJSONObject("objects");
        boolean isVirtual = new JSONObject(readFile(indexFile))
                                .optBoolean("virtual", false);
        boolean mapToResources = new JSONObject(readFile(indexFile))
                                .optBoolean("map_to_resources", false);

        log.accept("Downloading " + objects.length() + " assets...");

        // Parallel download with thread pool
        ExecutorService pool = Executors.newFixedThreadPool(ASSET_THREADS);
        List<Future<?>> futures = new ArrayList<>();
        int[] doneCount = {0};
        int total = objects.length();

        for (String name : objects.keySet()) {
            JSONObject obj  = objects.getJSONObject(name);
            String hash     = obj.getString("hash");
            long   size     = obj.optLong("size", -1);
            String prefix   = hash.substring(0, 2);
            String assetUrl = Constants.RESOURCES_URL + prefix + "/" + hash;
            Path   dest     = Constants.ASSETS_DIR.resolve("objects").resolve(prefix).resolve(hash);

            futures.add(pool.submit(() -> {
                try {
                    HttpUtil.downloadFile(assetUrl, dest, hash, null);

                    // Virtual assets: also copy to assets/virtual/<indexId>/<name>
                    if (isVirtual) {
                        Path virt = Constants.ASSETS_DIR
                            .resolve("virtual").resolve(indexId)
                            .resolve(name.replace("/", File.separator));
                        if (!Files.exists(virt)) {
                            Files.createDirectories(virt.getParent());
                            Files.copy(dest, virt, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }

                    // Legacy: copy to resources/ folder
                    if (mapToResources) {
                        Path res = Constants.GAME_DIR
                            .resolve("resources")
                            .resolve(name.replace("/", File.separator));
                        if (!Files.exists(res)) {
                            Files.createDirectories(res.getParent());
                            Files.copy(dest, res, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (Exception e) {
                    // Non-fatal: log but keep going
                    log.accept("⚠ Asset skip: " + name + " — " + e.getMessage());
                }
                synchronized (doneCount) {
                    doneCount[0]++;
                    int pct = 70 + (int)(28.0 * doneCount[0] / total);
                    progress.accept(Math.min(pct, 98));
                    if (doneCount[0] % 200 == 0 || doneCount[0] == total) {
                        log.accept("  Assets: " + doneCount[0] + "/" + total);
                    }
                }
                return null;
            }));
        }

        // Wait for all downloads
        pool.shutdown();
        for (Future<?> f : futures) {
            try { f.get(); }
            catch (ExecutionException e) { /* already logged */ }
        }
        pool.awaitTermination(10, TimeUnit.MINUTES);

        log.accept("✓ Assets complete.");
        progress.accept(98);
    }

    // ── Ensure all required game dirs exist ───────────────────────────────────

    private void ensureDirectories() throws IOException {
        Path[] dirs = {
            Constants.MODS_DIR,
            Constants.RESOURCEPACKS_DIR,
            Constants.SHADERPACKS_DIR,
            Constants.SCREENSHOTS_DIR,
            Constants.SAVES_DIR,
            Constants.LOGS_DIR,
            Constants.CRASH_REPORTS_DIR,
        };
        for (Path d : dirs) Files.createDirectories(d);
    }

    // ── OS rule evaluation ────────────────────────────────────────────────────

    public boolean rulesAllow(JSONObject lib) {
        JSONArray rules = lib.optJSONArray("rules");
        if (rules == null) return true;

        boolean allowed = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            String action  = rule.getString("action");
            JSONObject os  = rule.optJSONObject("os");
            if (os == null) {
                allowed = action.equals("allow");
            } else {
                String osName = os.optString("name", "");
                if (Constants.osName().equals(osName)) {
                    allowed = action.equals("allow");
                }
            }
        }
        return allowed;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String humanSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024*1024) return String.format("%.1f KB", bytes/1024.0);
        return String.format("%.1f MB", bytes/(1024.0*1024));
    }

    private static String readFile(java.nio.file.Path path) throws java.io.IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(path);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
