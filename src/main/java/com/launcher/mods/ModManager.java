package com.launcher.mods;

import com.launcher.Constants;
import com.launcher.download.HttpUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class ModManager {

    private static final String MODRINTH_API = "https://api.modrinth.com/v2";

    // ── Data classes ──────────────────────────────────────────────────────────

    public static class ModInfo {
        public final Path    path;
        public final String  name;
        public final String  fileName;
        public final String  version;
        public final String  loader;
        public final boolean disabled;

        public ModInfo(Path path, String name, String fileName,
                       String version, String loader, boolean disabled) {
            this.path     = path;
            this.name     = name;
            this.fileName = fileName;
            this.version  = version;
            this.loader   = loader;
            this.disabled = disabled;
        }
    }

    public static class ModrinthResult {
        public final String projectId;
        public final String name;
        public final String description;
        public final String downloads;

        public ModrinthResult(String projectId, String name,
                              String description, long downloadCount) {
            this.projectId   = projectId;
            this.name        = name;
            this.description = description;
            this.downloads   = formatDownloads(downloadCount) + " downloads";
        }

        private static String formatDownloads(long n) {
            if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
            if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
            return String.valueOf(n);
        }
    }

    // ── Installed mods ────────────────────────────────────────────────────────

    public static List<ModInfo> listMods() {
        List<ModInfo> mods = new ArrayList<>();
        if (!Files.exists(Constants.modsDir())) return mods;
        try (Stream<Path> stream = Files.list(Constants.modsDir())) {
            stream.sorted().forEach(p -> {
                String fn = p.getFileName().toString();
                if (fn.endsWith(".jar"))
                    mods.add(buildModInfo(p, fn, false));
                else if (fn.endsWith(".jar.disabled"))
                    mods.add(buildModInfo(p, fn, true));
            });
        } catch (IOException ignored) {}
        return mods;
    }

    public static void enableMod(ModInfo mod) throws IOException {
        String fn = mod.path.getFileName().toString();
        if (!fn.endsWith(".jar.disabled")) return;
        Files.move(mod.path,
            mod.path.resolveSibling(fn.replace(".jar.disabled", ".jar")),
            StandardCopyOption.REPLACE_EXISTING);
    }

    public static void disableMod(ModInfo mod) throws IOException {
        String fn = mod.path.getFileName().toString();
        if (!fn.endsWith(".jar")) return;
        Files.move(mod.path,
            mod.path.resolveSibling(fn + ".disabled"),
            StandardCopyOption.REPLACE_EXISTING);
    }

    public static void deleteMod(ModInfo mod) throws IOException {
        Files.deleteIfExists(mod.path);
    }

    // ── Modrinth ──────────────────────────────────────────────────────────────

    public static List<ModrinthResult> searchModrinth(String query,
                                                       String mcVersion) throws Exception {
        String encoded = java.net.URLEncoder.encode(query, "UTF-8");
        String facets  = java.net.URLEncoder.encode(
            "[[\"project_type:mod\"],[\"versions:" + mcVersion + "\"]]", "UTF-8");
        String url = MODRINTH_API + "/search?query=" + encoded
                     + "&facets=" + facets + "&limit=20";

        JSONObject root = new JSONObject(HttpUtil.fetchString(url));
        JSONArray  hits = root.optJSONArray("hits");
        List<ModrinthResult> results = new ArrayList<>();
        if (hits == null) return results;
        for (int i = 0; i < hits.length(); i++) {
            JSONObject h = hits.getJSONObject(i);
            results.add(new ModrinthResult(
                h.optString("project_id", h.optString("slug", "")),
                h.optString("title", "Unknown"),
                h.optString("description", ""),
                h.optLong("downloads", 0)
            ));
        }
        return results;
    }

    public static void downloadFromModrinth(String projectId, String mcVersion,
                                             Consumer<String> log) throws Exception {
        String url = MODRINTH_API + "/project/" + projectId + "/version"
                     + "?game_versions=%5B%22" + mcVersion + "%22%5D";
        JSONArray versions = new JSONArray(HttpUtil.fetchString(url));
        if (versions.isEmpty())
            throw new IOException("No compatible version found for MC " + mcVersion);

        JSONObject ver   = versions.getJSONObject(0);
        JSONArray  files = ver.getJSONArray("files");

        JSONObject primary = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            if (f.optBoolean("primary", false)) { primary = f; break; }
        }
        if (primary == null) primary = files.getJSONObject(0);

        String fileUrl  = primary.getString("url");
        String fileName = primary.getString("filename");
        String sha1     = primary.optJSONObject("hashes") != null
                          ? primary.getJSONObject("hashes").optString("sha1", null)
                          : null;

        Files.createDirectories(Constants.modsDir());
        Path dest = Constants.modsDir().resolve(fileName);
        log.accept("[Mods] Downloading " + fileName + " …");
        HttpUtil.downloadFile(fileUrl, dest, sha1, (done, total) -> {
            if (total > 0 && done * 5 / total > (done - 1) * 5 / total)
                log.accept("[Mods] " + (done * 100 / total) + "% …");
        });
        log.accept("[Mods] ✓ Installed: " + fileName);
    }

    // ── Mod loader detection ──────────────────────────────────────────────────

    public static String getModLoaderLabel() {
        Path versionDir = Constants.VERSIONS_DIR.resolve(Constants.MINECRAFT_VERSION);
        if (hasFile(versionDir, "fabric-loader"))  return "Fabric";
        if (hasFile(versionDir, "forge"))           return "Forge";
        if (hasFile(versionDir, "quilt-loader"))    return "Quilt";
        if (hasFile(Constants.modsDir(), "fabric"))  return "Fabric (mods present)";
        return "Vanilla";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ModInfo buildModInfo(Path path, String fileName, boolean disabled) {
        String base    = disabled
            ? fileName.replace(".jar.disabled", "")
            : fileName.replace(".jar", "");
        String name    = base;
        String version = "";
        String loader  = detectLoader(fileName);
        int dash = base.lastIndexOf('-');
        if (dash > 0 && dash < base.length() - 1) {
            String after = base.substring(dash + 1);
            if (after.matches("[0-9].*")) {
                name    = base.substring(0, dash);
                version = after;
            }
        }
        return new ModInfo(path, name, fileName, version, loader, disabled);
    }

    private static String detectLoader(String fileName) {
        String f = fileName.toLowerCase();
        if (f.contains("fabric"))   return "Fabric";
        if (f.contains("forge"))    return "Forge";
        if (f.contains("quilt"))    return "Quilt";
        if (f.contains("neoforge")) return "NeoForge";
        return "Unknown";
    }

    private static boolean hasFile(Path dir, String keyword) {
        if (!Files.exists(dir)) return false;
        try (Stream<Path> s = Files.list(dir)) {
            return s.anyMatch(p -> p.getFileName().toString()
                                    .toLowerCase().contains(keyword.toLowerCase()));
        } catch (IOException e) { return false; }
    }
}
