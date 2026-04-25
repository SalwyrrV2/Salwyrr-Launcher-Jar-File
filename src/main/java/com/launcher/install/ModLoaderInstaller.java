package com.launcher.install;

import com.launcher.Constants;
import com.launcher.download.HttpUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * Downloads and installs mod loaders:
 *  - Fabric  (via meta.fabricmc.net)
 *  - Forge   (via maven.minecraftforge.net)
 *  - NeoForge (via maven.neoforged.net)
 *  - OptiFine (via optifine.net / download mirror)
 *
 * Each loader is installed into its own versioned JSON file that
 * GameLauncher reads and merges on top of the vanilla version JSON.
 *
 * Loader selection is persisted per MC version in
 *   <gameDir>/instances/<version>/loader.txt
 */
public class ModLoaderInstaller {

    public enum Loader { NONE, FABRIC, FORGE, NEOFORGE, OPTIFINE }

    private static final String FABRIC_META   = "https://meta.fabricmc.net/v2";
    private static final String FORGE_MAVEN   = "https://maven.minecraftforge.net/net/minecraftforge/forge";
    private static final String NEO_MAVEN     = "https://maven.neoforged.net/releases/net/neoforged/neoforge";
    private static final String OPTIFINE_LIST = "https://optifine.net/adloadx?f=OptiFine_";

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns the currently selected loader for the active MC version. */
    public static Loader getSelectedLoader() {
        Path f = loaderFile();
        if (!Files.exists(f)) return Loader.NONE;
        try {
            String s = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim();
            return Loader.valueOf(s.toUpperCase());
        } catch (Exception e) { return Loader.NONE; }
    }

    /** Persists the chosen loader for the active MC version. */
    public static void setSelectedLoader(Loader loader) {
        try {
            Path f = loaderFile();
            Files.createDirectories(f.getParent());
            Files.write(f, loader.name().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    /**
     * Returns the installed loader version JSON path (the merged/patched JSON
     * that GameLauncher uses instead of the vanilla one), or null if no loader
     * is installed.
     */
    public static Path getLoaderVersionJson() {
        Path p = loaderVersionJsonPath();
        return Files.exists(p) ? p : null;
    }

    /**
     * Install the given loader for the current MC version.
     * Writes a loader-<type>.json into the version directory.
     */
    public static void install(Loader loader, Consumer<String> log) throws Exception {
        String mcVer = Constants.MINECRAFT_VERSION;
        switch (loader) {
            case FABRIC:   installFabric(mcVer, log);   break;
            case FORGE:    installForge(mcVer, log);    break;
            case NEOFORGE: installNeoForge(mcVer, log); break;
            case OPTIFINE: installOptiFine(mcVer, log); break;
            case NONE:
                // Remove loader json if present
                try { Files.deleteIfExists(loaderVersionJsonPath()); } catch (Exception ignored) {}
                log.accept("Loader removed — vanilla mode.");
                break;
        }
        if (loader != Loader.NONE) {
            setSelectedLoader(loader);
        } else {
            setSelectedLoader(Loader.NONE);
        }
    }

    // ── Fabric ────────────────────────────────────────────────────────────────

    private static void installFabric(String mcVer, Consumer<String> log) throws Exception {
        log.accept("[Fabric] Fetching loader versions...");

        // Get latest stable fabric-loader version
        String loaderListJson = HttpUtil.fetchString(FABRIC_META + "/versions/loader");
        JSONArray loaderList  = new JSONArray(loaderListJson);
        String loaderVer = null;
        for (int i = 0; i < loaderList.length(); i++) {
            JSONObject lv = loaderList.getJSONObject(i);
            if (lv.optBoolean("stable", false)) {
                loaderVer = lv.optString("version");
                break;
            }
        }
        if (loaderVer == null && loaderList.length() > 0)
            loaderVer = loaderList.getJSONObject(0).optString("version");
        if (loaderVer == null) throw new IOException("No Fabric loader version found.");

        log.accept("[Fabric] Loader version: " + loaderVer);

        // Fetch the launch profile JSON from Fabric meta
        String profileUrl = FABRIC_META + "/versions/loader/" + mcVer + "/" + loaderVer + "/profile/json";
        log.accept("[Fabric] Downloading profile JSON...");
        String profileJson = HttpUtil.fetchString(profileUrl);

        // Download all Fabric libraries
        JSONObject profile = new JSONObject(profileJson);
        downloadLoaderLibraries(profile, log);

        // Save the profile JSON as our loader version file
        Path dest = loaderVersionJsonPath();
        Files.createDirectories(dest.getParent());
        Files.write(dest, profileJson.getBytes(StandardCharsets.UTF_8));
        log.accept("[Fabric] ✓ Fabric " + loaderVer + " installed for MC " + mcVer);
    }

    // ── Forge ─────────────────────────────────────────────────────────────────

    private static void installForge(String mcVer, Consumer<String> log) throws Exception {
        log.accept("[Forge] Resolving Forge version for MC " + mcVer + "...");

        // Forge version list page — we scrape the promotions_slim.json
        String promoUrl  = FORGE_MAVEN + "/promotions_slim.json";
        String promoJson = HttpUtil.fetchString(promoUrl);
        JSONObject promos = new JSONObject(promoJson).optJSONObject("promos");

        String forgeVer = null;
        // Try recommended first, then latest
        if (promos != null) {
            String recKey = mcVer + "-recommended";
            String latKey = mcVer + "-latest";
            if (promos.has(recKey)) forgeVer = promos.getString(recKey);
            else if (promos.has(latKey)) forgeVer = promos.getString(latKey);
        }

        if (forgeVer == null)
            throw new IOException("No Forge build found for MC " + mcVer
                + ".\nCheck https://files.minecraftforge.net for supported versions.");

        String fullForgeVer = mcVer + "-" + forgeVer;
        log.accept("[Forge] Version: " + fullForgeVer);

        // Download the installer jar
        String installerUrl = FORGE_MAVEN + "/" + fullForgeVer
            + "/forge-" + fullForgeVer + "-installer.jar";
        Path installerJar = Constants.GAME_DIR.resolve("forge-" + fullForgeVer + "-installer.jar");

        log.accept("[Forge] Downloading installer...");
        HttpUtil.downloadFile(installerUrl, installerJar, null, null);

        // Run the Forge installer headlessly
        log.accept("[Forge] Running Forge installer (this may take a minute)...");
        runForgeInstaller(installerJar, log);

        // Forge installer puts its version JSON in a subdirectory like:
        // .minecraft/versions/<mcVer>-forge-<forgeVer>/<mcVer>-forge-<forgeVer>.json
        // We need to find it and copy to our loader JSON path
        String forgeVersionId = mcVer + "-forge-" + forgeVer;
        Path forgeVersionJson = Constants.VERSIONS_DIR.resolve(forgeVersionId)
                                    .resolve(forgeVersionId + ".json");

        if (!Files.exists(forgeVersionJson)) {
            // Alternate naming Forge sometimes uses
            forgeVersionJson = Constants.VERSIONS_DIR.resolve(fullForgeVer)
                                   .resolve(fullForgeVer + ".json");
        }

        if (!Files.exists(forgeVersionJson))
            throw new IOException("Forge installer ran but version JSON not found.\n"
                + "Expected: " + forgeVersionJson);

        String profileJson = new String(Files.readAllBytes(forgeVersionJson), StandardCharsets.UTF_8);

        // Download any extra libraries Forge added
        downloadLoaderLibraries(new JSONObject(profileJson), log);

        Path dest = loaderVersionJsonPath();
        Files.createDirectories(dest.getParent());
        Files.write(dest, profileJson.getBytes(StandardCharsets.UTF_8));

        // Cleanup installer
        try { Files.deleteIfExists(installerJar); } catch (Exception ignored) {}

        log.accept("[Forge] ✓ Forge " + forgeVer + " installed for MC " + mcVer);
    }

    private static void runForgeInstaller(Path installerJar, Consumer<String> log) throws Exception {
        // Run: java -jar forge-installer.jar --installClient <gameDir>
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(
            java, "-jar", installerJar.toAbsolutePath().toString(),
            "--installClient", Constants.GAME_DIR.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                final String l = line;
                log.accept("[Forge] " + l);
            }
        }
        int code = proc.waitFor();
        if (code != 0) throw new IOException("Forge installer exited with code " + code);
    }

    // ── NeoForge ──────────────────────────────────────────────────────────────

    private static void installNeoForge(String mcVer, Consumer<String> log) throws Exception {
        log.accept("[NeoForge] Resolving NeoForge version for MC " + mcVer + "...");

        // NeoForge version = <mcMinor>.<mcPatch>.x  e.g. MC 1.21.1 → 21.1.x
        // Fetch maven metadata to find latest
        String neoMcVer = toNeoVersion(mcVer);
        String metaUrl  = NEO_MAVEN + "/maven-metadata.xml";
        String metaXml  = HttpUtil.fetchString(metaUrl);

        String neoVer = extractLatestNeoForgeVersion(metaXml, neoMcVer);
        if (neoVer == null)
            throw new IOException("No NeoForge build found for MC " + mcVer
                + " (looked for prefix " + neoMcVer + ").\n"
                + "Check https://neoforged.net for supported versions.");

        log.accept("[NeoForge] Version: " + neoVer);

        // Download installer jar
        String installerUrl = NEO_MAVEN + "/" + neoVer
            + "/neoforge-" + neoVer + "-installer.jar";
        Path installerJar = Constants.GAME_DIR.resolve("neoforge-" + neoVer + "-installer.jar");

        log.accept("[NeoForge] Downloading installer...");
        HttpUtil.downloadFile(installerUrl, installerJar, null, null);

        // Run installer headlessly (same flags as Forge)
        log.accept("[NeoForge] Running installer...");
        runForgeInstaller(installerJar, log); // same headless flags

        // NeoForge version id pattern: neoforge-<neoVer>
        String neoVersionId = "neoforge-" + neoVer;
        Path neoVersionJson = Constants.VERSIONS_DIR.resolve(neoVersionId)
                                  .resolve(neoVersionId + ".json");

        if (!Files.exists(neoVersionJson))
            throw new IOException("NeoForge installer ran but version JSON not found.\n"
                + "Expected: " + neoVersionJson);

        String profileJson = new String(Files.readAllBytes(neoVersionJson), StandardCharsets.UTF_8);
        downloadLoaderLibraries(new JSONObject(profileJson), log);

        Path dest = loaderVersionJsonPath();
        Files.createDirectories(dest.getParent());
        Files.write(dest, profileJson.getBytes(StandardCharsets.UTF_8));

        try { Files.deleteIfExists(installerJar); } catch (Exception ignored) {}
        log.accept("[NeoForge] ✓ NeoForge " + neoVer + " installed for MC " + mcVer);
    }

    /** Convert MC version to NeoForge major-minor prefix, e.g. "1.21.1" → "21.1" */
    private static String toNeoVersion(String mcVer) {
        String[] parts = mcVer.split("\\.");
        if (parts.length >= 3) return parts[1] + "." + parts[2];
        if (parts.length == 2) return parts[1] + ".0";
        return mcVer;
    }

    private static String extractLatestNeoForgeVersion(String xml, String prefix) {
        // Simple XML parse — find all <version> tags with our prefix, return the last one
        String latest = null;
        int idx = 0;
        while (true) {
            int start = xml.indexOf("<version>", idx);
            if (start < 0) break;
            int end = xml.indexOf("</version>", start);
            if (end < 0) break;
            String v = xml.substring(start + 9, end).trim();
            if (v.startsWith(prefix)) latest = v;
            idx = end + 10;
        }
        return latest;
    }

    // ── OptiFine ──────────────────────────────────────────────────────────────

    private static void installOptiFine(String mcVer, Consumer<String> log) throws Exception {
        log.accept("[OptiFine] Finding OptiFine for MC " + mcVer + "...");

        // Fetch the OptiFine adload page which lists available files
        String listUrl  = OPTIFINE_LIST + mcVer + "_HD_U";
        String listHtml;
        try {
            listHtml = HttpUtil.fetchString(listUrl);
        } catch (Exception e) {
            throw new IOException("Could not reach OptiFine server.\n"
                + "Download OptiFine manually from https://optifine.net and place the jar "
                + "in: " + Constants.modsDir());
        }

        // Parse the download link — look for the first adloadx?f=OptiFine_<mcVer>_HD_U_*.jar
        String downloadUrl = extractOptiFineUrl(listHtml, mcVer);
        if (downloadUrl == null)
            throw new IOException("OptiFine not yet available for MC " + mcVer
                + ".\nCheck https://optifine.net and install manually into: "
                + Constants.modsDir());

        String fileName = downloadUrl.substring(downloadUrl.lastIndexOf('=') + 1);
        log.accept("[OptiFine] Found: " + fileName);

        // OptiFine goes into the mods folder (as a mod/coremods jar)
        Files.createDirectories(Constants.modsDir());
        Path dest = Constants.modsDir().resolve(fileName);

        if (Files.exists(dest)) {
            log.accept("[OptiFine] Already installed: " + fileName);
        } else {
            log.accept("[OptiFine] Downloading...");
            HttpUtil.downloadFile(downloadUrl, dest, null, null);
        }

        // OptiFine works as a Vanilla tweak — write a minimal loader JSON
        // that adds the OptiFine jar to the classpath and sets the tweaker
        String optifineJson = buildOptiFineLoaderJson(mcVer, dest);
        Path loaderJson = loaderVersionJsonPath();
        Files.createDirectories(loaderJson.getParent());
        Files.write(loaderJson, optifineJson.getBytes(StandardCharsets.UTF_8));

        log.accept("[OptiFine] ✓ OptiFine installed for MC " + mcVer);
        log.accept("[OptiFine] Note: OptiFine works best with vanilla or Forge.");
    }

    private static String extractOptiFineUrl(String html, String mcVer) {
        // Look for href containing OptiFine_<mcVer>_HD_U
        String marker = "OptiFine_" + mcVer + "_HD_U";
        int idx = html.indexOf(marker);
        if (idx < 0) return null;
        // Walk backwards to find href="
        int hrefStart = html.lastIndexOf("href=\"", idx);
        if (hrefStart < 0) return null;
        hrefStart += 6;
        int hrefEnd = html.indexOf("\"", hrefStart);
        if (hrefEnd < 0) return null;
        String href = html.substring(hrefStart, hrefEnd);
        if (!href.startsWith("http")) href = "https://optifine.net/" + href;
        return href;
    }

    private static String buildOptiFineLoaderJson(String mcVer, Path optifineJar) {
        // Minimal version JSON that adds OptiFine's tweaker as a game arg
        // and ensures the OptiFine jar is on the classpath via a local library entry
        JSONObject json = new JSONObject();
        json.put("id", "optifine-" + mcVer);
        json.put("type", "release");
        json.put("mainClass", "net.minecraft.launchwrapper.Launch");

        JSONObject arguments = new JSONObject();
        JSONArray gameArgs = new JSONArray();
        gameArgs.put("--tweakClass");
        gameArgs.put("optifine.OptiFineTweaker");
        arguments.put("game", gameArgs);
        json.put("arguments", arguments);

        // Add OptiFine jar as a library with a local file path
        JSONArray libraries = new JSONArray();
        JSONObject lib = new JSONObject();
        lib.put("name", "optifine:OptiFine:" + mcVer);
        JSONObject downloads = new JSONObject();
        JSONObject artifact = new JSONObject();
        // Use path relative to libraries dir — we'll copy the jar there
        String libPath = "optifine/OptiFine/" + mcVer + "/OptiFine-" + mcVer + ".jar";
        Path libDest = Constants.LIBRARIES_DIR.resolve(libPath.replace("/", File.separator));
        try {
            Files.createDirectories(libDest.getParent());
            if (!Files.exists(libDest))
                Files.copy(optifineJar, libDest, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {}
        artifact.put("path", libPath);
        artifact.put("url", "");
        artifact.put("sha1", "");
        artifact.put("size", 0);
        downloads.put("artifact", artifact);
        lib.put("downloads", downloads);
        libraries.put(lib);
        json.put("libraries", libraries);

        return json.toString(2);
    }

    // ── Library downloader (shared) ───────────────────────────────────────────

    /**
     * Downloads all libraries listed in a loader profile JSON that aren't
     * already present locally.
     */
    private static void downloadLoaderLibraries(JSONObject profile, Consumer<String> log) {
        JSONArray libs = profile.optJSONArray("libraries");
        if (libs == null) return;
        int total = libs.length(), ok = 0, skip = 0;
        for (int i = 0; i < total; i++) {
            JSONObject lib = libs.getJSONObject(i);
            JSONObject downloads = lib.optJSONObject("downloads");
            if (downloads == null) {
                // Some loaders use "url" + "name" directly (Fabric style)
                String urlStr = lib.optString("url", "");
                String name   = lib.optString("name", "");
                if (!urlStr.isEmpty() && !name.isEmpty()) {
                    try { downloadMavenLib(name, urlStr, log); ok++; }
                    catch (Exception e) { log.accept("  ⚠ " + name + ": " + e.getMessage()); }
                }
                skip++;
                continue;
            }
            JSONObject artifact = downloads.optJSONObject("artifact");
            if (artifact == null) { skip++; continue; }
            String path = artifact.optString("path", null);
            String url  = artifact.optString("url", "");
            String sha1 = artifact.optString("sha1", null);
            if (path == null || url.isEmpty()) { skip++; continue; }
            Path dest = Constants.LIBRARIES_DIR.resolve(path.replace("/", File.separator));
            if (Files.exists(dest)) { skip++; continue; }
            try {
                Files.createDirectories(dest.getParent());
                HttpUtil.downloadFile(url, dest, sha1, null);
                ok++;
            } catch (Exception e) {
                log.accept("  ⚠ Library skip: " + path + " — " + e.getMessage());
            }
        }
        log.accept("  Libraries: " + ok + " downloaded, " + skip + " already present/skipped.");
    }

    /** Download a Maven-coordinate library from a base URL. */
    private static void downloadMavenLib(String name, String repoUrl, Consumer<String> log)
            throws Exception {
        // name format: group:artifact:version  e.g. net.fabricmc:fabric-loader:0.15.0
        String[] parts = name.split(":");
        if (parts.length < 3) return;
        String group   = parts[0].replace('.', '/');
        String art     = parts[1];
        String ver     = parts[2];
        String jarName = art + "-" + ver + ".jar";
        String path    = group + "/" + art + "/" + ver + "/" + jarName;
        String url     = (repoUrl.endsWith("/") ? repoUrl : repoUrl + "/") + path;
        Path dest      = Constants.LIBRARIES_DIR.resolve(path.replace("/", File.separator));
        if (Files.exists(dest)) return;
        Files.createDirectories(dest.getParent());
        HttpUtil.downloadFile(url, dest, null, null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Path to the persisted loader choice for the current MC version. */
    private static Path loaderFile() {
        return Constants.instanceDir().resolve("loader.txt");
    }

    /** Path where the installed loader's version JSON is saved. */
    public static Path loaderVersionJsonPath() {
        return Constants.versionDir().resolve("loader-" + Constants.MINECRAFT_VERSION + ".json");
    }
}
