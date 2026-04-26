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
 *  - Fabric   (via meta.fabricmc.net)
 *  - Quilt    (via meta.quiltmc.org)
 *  - Forge    (via maven.minecraftforge.net)
 *  - NeoForge (via maven.neoforged.net)
 *  - OptiFine (via optifine.net download mirror)
 *
 * Each loader is installed into its own versioned JSON file that
 * GameLauncher reads and merges on top of the vanilla version JSON.
 *
 * Loader selection is persisted per MC version in
 *   &lt;gameDir&gt;/instances/&lt;version&gt;/loader.txt
 *
 * CLI usage (from Main.java --install-loader):
 *   --install-loader fabric
 *   --install-loader quilt
 *   --install-loader forge
 *   --install-loader neoforge
 *   --install-loader optifine
 *   --install-loader none       (revert to vanilla)
 *
 * Optionally pin a loader version:
 *   --loader-version 0.15.11   (fabric-loader, quilt-loader, forge, or neoforge version)
 */
public class ModLoaderInstaller {

    public enum Loader { NONE, FABRIC, QUILT, FORGE, NEOFORGE, OPTIFINE }

    // ── Upstream API / Maven roots ────────────────────────────────────────────

    private static final String FABRIC_META   = "https://meta.fabricmc.net/v2";
    private static final String QUILT_META    = "https://meta.quiltmc.org/v3";
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
     * is installed for the current MC version.
     */
    public static Path getLoaderVersionJson() {
        Path p = loaderVersionJsonPath();
        return Files.exists(p) ? p : null;
    }

    /**
     * Install the given loader for the current MC version.
     *
     * @param loader        Which loader to install (NONE removes any existing loader).
     * @param loaderVersion Optional pinned loader version string; null = use latest.
     * @param log           Progress callback for UI / stdout.
     */
    public static void install(Loader loader, String loaderVersion,
                               Consumer<String> log) throws Exception {
        String mcVer = Constants.MINECRAFT_VERSION;
        switch (loader) {
            case FABRIC:   installFabric(mcVer, loaderVersion, log);   break;
            case QUILT:    installQuilt(mcVer, loaderVersion, log);    break;
            case FORGE:    installForge(mcVer, loaderVersion, log);    break;
            case NEOFORGE: installNeoForge(mcVer, loaderVersion, log); break;
            case OPTIFINE: installOptiFine(mcVer, log);                break;
            case NONE:
                try { Files.deleteIfExists(loaderVersionJsonPath()); } catch (Exception ignored) {}
                log.accept("Loader removed — vanilla mode.");
                break;
        }
        setSelectedLoader(loader);
    }

    /** Convenience overload — always picks the latest stable loader version. */
    public static void install(Loader loader, Consumer<String> log) throws Exception {
        install(loader, null, log);
    }

    /**
     * Fetch available loader versions for a given loader + current MC version.
     * Returns a list of version strings, newest first.
     * OPTIFINE and NONE return an empty list.
     */
    public static List<String> listVersions(Loader loader) throws Exception {
        String mcVer = Constants.MINECRAFT_VERSION;
        switch (loader) {
            case FABRIC:   return listFabricVersions(mcVer);
            case QUILT:    return listQuiltVersions(mcVer);
            case FORGE:    return listForgeVersions(mcVer);
            case NEOFORGE: return listNeoForgeVersions(mcVer);
            default:       return Collections.emptyList();
        }
    }

    // ── Fabric ────────────────────────────────────────────────────────────────

    private static List<String> listFabricVersions(String mcVer) throws Exception {
        String json = HttpUtil.fetchString(FABRIC_META + "/versions/loader/" + mcVer);
        JSONArray arr = new JSONArray(json);
        List<String> versions = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject entry  = arr.getJSONObject(i);
            JSONObject loader = entry.optJSONObject("loader");
            if (loader != null) versions.add(loader.optString("version", ""));
        }
        return versions;
    }

    private static void installFabric(String mcVer, String pinnedLoaderVer,
                                      Consumer<String> log) throws Exception {
        log.accept("[Fabric] Fetching loader versions for MC " + mcVer + "...");

        String loaderVer = pinnedLoaderVer;
        if (loaderVer == null || loaderVer.isEmpty()) {
            String loaderListJson = HttpUtil.fetchString(FABRIC_META + "/versions/loader");
            JSONArray loaderList  = new JSONArray(loaderListJson);
            for (int i = 0; i < loaderList.length(); i++) {
                JSONObject lv = loaderList.getJSONObject(i);
                if (lv.optBoolean("stable", false)) {
                    loaderVer = lv.optString("version");
                    break;
                }
            }
            if ((loaderVer == null || loaderVer.isEmpty()) && loaderList.length() > 0)
                loaderVer = loaderList.getJSONObject(0).optString("version");
        }
        if (loaderVer == null || loaderVer.isEmpty())
            throw new IOException("No Fabric loader version found for MC " + mcVer + ".");

        log.accept("[Fabric] Loader version: " + loaderVer);

        String profileUrl = FABRIC_META + "/versions/loader/" + mcVer
                          + "/" + loaderVer + "/profile/json";
        log.accept("[Fabric] Downloading launch profile...");
        String profileJson = HttpUtil.fetchString(profileUrl);

        downloadLoaderLibraries(new JSONObject(profileJson), log);

        Path dest = loaderVersionJsonPath();
        Files.createDirectories(dest.getParent());
        Files.write(dest, profileJson.getBytes(StandardCharsets.UTF_8));
        log.accept("[Fabric] ✓ Fabric " + loaderVer + " installed for MC " + mcVer);
    }

    // ── Quilt ─────────────────────────────────────────────────────────────────

    private static List<String> listQuiltVersions(String mcVer) throws Exception {
        String json = HttpUtil.fetchString(QUILT_META + "/versions/loader/" + mcVer);
        JSONArray arr = new JSONArray(json);
        List<String> versions = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject entry  = arr.getJSONObject(i);
            JSONObject loader = entry.optJSONObject("loader");
            if (loader != null) versions.add(loader.optString("version", ""));
        }
        return versions;
    }

    private static void installQuilt(String mcVer, String pinnedLoaderVer,
                                     Consumer<String> log) throws Exception {
        log.accept("[Quilt] Fetching loader versions for MC " + mcVer + "...");

        String loaderVer = pinnedLoaderVer;
        if (loaderVer == null || loaderVer.isEmpty()) {
            String loaderListJson = HttpUtil.fetchString(QUILT_META + "/versions/loader");
            JSONArray loaderList  = new JSONArray(loaderListJson);
            // Quilt marks stable releases similarly to Fabric
            for (int i = 0; i < loaderList.length(); i++) {
                JSONObject lv = loaderList.getJSONObject(i);
                if (lv.optBoolean("stable", false)) {
                    loaderVer = lv.optString("version");
                    break;
                }
            }
            if ((loaderVer == null || loaderVer.isEmpty()) && loaderList.length() > 0)
                loaderVer = loaderList.getJSONObject(0).optString("version");
        }
        if (loaderVer == null || loaderVer.isEmpty())
            throw new IOException("No Quilt loader version found for MC " + mcVer + ".");

        log.accept("[Quilt] Loader version: " + loaderVer);

        // Quilt meta mirrors the same /profile/json endpoint structure as Fabric
        String profileUrl = QUILT_META + "/versions/loader/" + mcVer
                          + "/" + loaderVer + "/profile/json";
        log.accept("[Quilt] Downloading launch profile...");
        String profileJson = HttpUtil.fetchString(profileUrl);

        downloadLoaderLibraries(new JSONObject(profileJson), log);

        Path dest = loaderVersionJsonPath();
        Files.createDirectories(dest.getParent());
        Files.write(dest, profileJson.getBytes(StandardCharsets.UTF_8));
        log.accept("[Quilt] ✓ Quilt " + loaderVer + " installed for MC " + mcVer);
    }

    // ── Forge ─────────────────────────────────────────────────────────────────

    private static List<String> listForgeVersions(String mcVer) throws Exception {
        String promoJson  = HttpUtil.fetchString(FORGE_MAVEN + "/promotions_slim.json");
        JSONObject promos = new JSONObject(promoJson).optJSONObject("promos");
        if (promos == null) return Collections.emptyList();
        List<String> versions = new ArrayList<>();
        String rec = mcVer + "-recommended";
        String lat = mcVer + "-latest";
        if (promos.has(rec)) versions.add(promos.getString(rec) + " (recommended)");
        if (promos.has(lat)) versions.add(promos.getString(lat) + " (latest)");
        return versions;
    }

    private static void installForge(String mcVer, String pinnedForgeVer,
                                     Consumer<String> log) throws Exception {
        log.accept("[Forge] Resolving Forge version for MC " + mcVer + "...");

        String forgeVer = pinnedForgeVer;
        if (forgeVer == null || forgeVer.isEmpty()) {
            JSONObject promos = new JSONObject(
                HttpUtil.fetchString(FORGE_MAVEN + "/promotions_slim.json"))
                .optJSONObject("promos");
            if (promos != null) {
                String recKey = mcVer + "-recommended";
                String latKey = mcVer + "-latest";
                if (promos.has(recKey))      forgeVer = promos.getString(recKey);
                else if (promos.has(latKey)) forgeVer = promos.getString(latKey);
            }
        }
        if (forgeVer == null || forgeVer.isEmpty())
            throw new IOException("No Forge build found for MC " + mcVer
                + ".\nCheck https://files.minecraftforge.net for supported versions.");

        String fullForgeVer = mcVer + "-" + forgeVer;
        log.accept("[Forge] Version: " + fullForgeVer);

        String installerUrl = FORGE_MAVEN + "/" + fullForgeVer
            + "/forge-" + fullForgeVer + "-installer.jar";
        Path installerJar = Constants.GAME_DIR.resolve("forge-" + fullForgeVer + "-installer.jar");

        log.accept("[Forge] Downloading installer...");
        HttpUtil.downloadFile(installerUrl, installerJar, null, null);

        log.accept("[Forge] Running Forge installer (this may take a minute)...");
        runHeadlessInstaller(installerJar, log, "Forge");

        // Forge writes its version JSON to versions/<mcVer>-forge-<forgeVer>/ or versions/<fullVer>/
        String forgeVersionId = mcVer + "-forge-" + forgeVer;
        Path forgeVersionJson = findVersionJson(forgeVersionId, fullForgeVer);
        if (!Files.exists(forgeVersionJson))
            throw new IOException("Forge installer ran but version JSON not found.\n"
                + "Expected: " + forgeVersionJson);

        String profileJson = new String(Files.readAllBytes(forgeVersionJson), StandardCharsets.UTF_8);
        downloadLoaderLibraries(new JSONObject(profileJson), log);

        Path dest = loaderVersionJsonPath();
        Files.createDirectories(dest.getParent());
        Files.write(dest, profileJson.getBytes(StandardCharsets.UTF_8));
        try { Files.deleteIfExists(installerJar); } catch (Exception ignored) {}
        log.accept("[Forge] ✓ Forge " + forgeVer + " installed for MC " + mcVer);
    }

    // ── NeoForge ──────────────────────────────────────────────────────────────

    private static List<String> listNeoForgeVersions(String mcVer) throws Exception {
        String neoMcVer = toNeoVersion(mcVer);
        String metaXml  = HttpUtil.fetchString(NEO_MAVEN + "/maven-metadata.xml");
        List<String> versions = new ArrayList<>();
        int idx = 0;
        while (true) {
            int start = metaXml.indexOf("<version>", idx);
            if (start < 0) break;
            int end = metaXml.indexOf("</version>", start);
            if (end < 0) break;
            String v = metaXml.substring(start + 9, end).trim();
            if (v.startsWith(neoMcVer)) versions.add(0, v); // newest last in XML → prepend
            idx = end + 10;
        }
        return versions;
    }

    private static void installNeoForge(String mcVer, String pinnedNeoVer,
                                        Consumer<String> log) throws Exception {
        log.accept("[NeoForge] Resolving NeoForge version for MC " + mcVer + "...");

        String neoVer = pinnedNeoVer;
        if (neoVer == null || neoVer.isEmpty()) {
            List<String> available = listNeoForgeVersions(mcVer);
            if (available.isEmpty())
                throw new IOException("No NeoForge build found for MC " + mcVer
                    + ".\nCheck https://neoforged.net for supported versions.");
            neoVer = available.get(0); // list is newest-first after our reversal above
        }

        log.accept("[NeoForge] Version: " + neoVer);

        String installerUrl = NEO_MAVEN + "/" + neoVer
            + "/neoforge-" + neoVer + "-installer.jar";
        Path installerJar = Constants.GAME_DIR.resolve("neoforge-" + neoVer + "-installer.jar");

        log.accept("[NeoForge] Downloading installer...");
        HttpUtil.downloadFile(installerUrl, installerJar, null, null);

        log.accept("[NeoForge] Running installer...");
        runHeadlessInstaller(installerJar, log, "NeoForge");

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

    /** Convert MC version to NeoForge major-minor prefix — e.g. "1.21.1" → "21.1". */
    private static String toNeoVersion(String mcVer) {
        String[] parts = mcVer.split("\\.");
        if (parts.length >= 3) return parts[1] + "." + parts[2];
        if (parts.length == 2) return parts[1] + ".0";
        return mcVer;
    }

    // ── OptiFine ──────────────────────────────────────────────────────────────

    private static void installOptiFine(String mcVer, Consumer<String> log) throws Exception {
        log.accept("[OptiFine] Finding OptiFine for MC " + mcVer + "...");

        String listUrl  = OPTIFINE_LIST + mcVer + "_HD_U";
        String listHtml;
        try {
            listHtml = HttpUtil.fetchString(listUrl);
        } catch (Exception e) {
            throw new IOException("Could not reach OptiFine server.\n"
                + "Download OptiFine manually from https://optifine.net and place the jar "
                + "in: " + Constants.modsDir());
        }

        String downloadUrl = extractOptiFineUrl(listHtml, mcVer);
        if (downloadUrl == null)
            throw new IOException("OptiFine not yet available for MC " + mcVer
                + ".\nCheck https://optifine.net and install manually into: "
                + Constants.modsDir());

        String fileName = downloadUrl.substring(downloadUrl.lastIndexOf('=') + 1);
        log.accept("[OptiFine] Found: " + fileName);

        Files.createDirectories(Constants.modsDir());
        Path dest = Constants.modsDir().resolve(fileName);

        if (Files.exists(dest)) {
            log.accept("[OptiFine] Already installed: " + fileName);
        } else {
            log.accept("[OptiFine] Downloading...");
            HttpUtil.downloadFile(downloadUrl, dest, null, null);
        }

        String optifineJson = buildOptiFineLoaderJson(mcVer, dest);
        Path loaderJson = loaderVersionJsonPath();
        Files.createDirectories(loaderJson.getParent());
        Files.write(loaderJson, optifineJson.getBytes(StandardCharsets.UTF_8));

        log.accept("[OptiFine] ✓ OptiFine installed for MC " + mcVer);
        log.accept("[OptiFine] Note: OptiFine works best with vanilla or Forge.");
    }

    private static String extractOptiFineUrl(String html, String mcVer) {
        String marker = "OptiFine_" + mcVer + "_HD_U";
        int idx = html.indexOf(marker);
        if (idx < 0) return null;
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
        JSONObject json = new JSONObject();
        json.put("id", "optifine-" + mcVer);
        json.put("type", "release");
        json.put("mainClass", "net.minecraft.launchwrapper.Launch");

        JSONObject arguments = new JSONObject();
        JSONArray gameArgs   = new JSONArray();
        gameArgs.put("--tweakClass");
        gameArgs.put("optifine.OptiFineTweaker");
        arguments.put("game", gameArgs);
        json.put("arguments", arguments);

        JSONArray  libraries = new JSONArray();
        JSONObject lib       = new JSONObject();
        lib.put("name", "optifine:OptiFine:" + mcVer);
        JSONObject downloads = new JSONObject();
        JSONObject artifact  = new JSONObject();
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

    // ── Shared installer runner ───────────────────────────────────────────────

    private static void runHeadlessInstaller(Path installerJar, Consumer<String> log,
                                              String label) throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(
            java, "-jar", installerJar.toAbsolutePath().toString(),
            "--installClient", Constants.GAME_DIR.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) log.accept("[" + label + "] " + line);
        }
        int code = proc.waitFor();
        if (code != 0) throw new IOException(label + " installer exited with code " + code);
    }

    private static Path findVersionJson(String primaryId, String fallbackId) {
        Path primary = Constants.VERSIONS_DIR.resolve(primaryId).resolve(primaryId + ".json");
        if (Files.exists(primary)) return primary;
        return Constants.VERSIONS_DIR.resolve(fallbackId).resolve(fallbackId + ".json");
    }

    // ── Library downloader (shared) ───────────────────────────────────────────

    /**
     * Downloads all libraries listed in a loader profile JSON that aren't
     * already present locally.  Package-private so LauncherFrame can reuse it.
     */
    static void downloadLoaderLibraries(JSONObject profile, Consumer<String> log) {
        JSONArray libs = profile.optJSONArray("libraries");
        if (libs == null) return;
        int ok = 0, skip = 0;
        for (int i = 0; i < libs.length(); i++) {
            JSONObject lib       = libs.getJSONObject(i);
            JSONObject downloads = lib.optJSONObject("downloads");
            if (downloads == null) {
                // Fabric / Quilt style: just "url" + "name"
                String urlStr = lib.optString("url", "");
                String name   = lib.optString("name", "");
                if (!urlStr.isEmpty() && !name.isEmpty()) {
                    try { downloadMavenLib(name, urlStr, log); ok++; }
                    catch (Exception e) { log.accept("  ⚠ " + name + ": " + e.getMessage()); }
                } else {
                    skip++;
                }
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

    private static void downloadMavenLib(String name, String repoUrl,
                                          Consumer<String> log) throws Exception {
        // name format: group:artifact:version[:classifier]
        String[] parts = name.split(":");
        if (parts.length < 3) return;
        String group      = parts[0].replace('.', '/');
        String art        = parts[1];
        String ver        = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        String jarName    = art + "-" + ver + classifier + ".jar";
        String path       = group + "/" + art + "/" + ver + "/" + jarName;
        String url        = (repoUrl.endsWith("/") ? repoUrl : repoUrl + "/") + path;
        Path dest         = Constants.LIBRARIES_DIR.resolve(path.replace("/", File.separator));
        if (Files.exists(dest)) return;
        Files.createDirectories(dest.getParent());
        HttpUtil.downloadFile(url, dest, null, null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Path loaderFile() {
        return Constants.instanceDir().resolve("loader.txt");
    }

    /** Path where the installed loader's version JSON is saved. */
    public static Path loaderVersionJsonPath() {
        return Constants.versionDir().resolve("loader-" + Constants.MINECRAFT_VERSION + ".json");
    }

    /**
     * Parse a loader name string (from CLI or UI) into a Loader enum value.
     * Case-insensitive; returns NONE for unknown names.
     */
    public static Loader parseLoader(String name) {
        if (name == null) return Loader.NONE;
        switch (name.toLowerCase().trim()) {
            case "fabric":   return Loader.FABRIC;
            case "quilt":    return Loader.QUILT;
            case "forge":    return Loader.FORGE;
            case "neoforge": return Loader.NEOFORGE;
            case "optifine": return Loader.OPTIFINE;
            default:         return Loader.NONE;
        }
    }
}
