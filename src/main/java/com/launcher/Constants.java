package com.launcher;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Constants {

    // ── Mojang APIs ───────────────────────────────────────────────────────────
    public static final String VERSION_MANIFEST_URL =
        "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    public static final String RESOURCES_URL =
        "https://resources.download.minecraft.net/";
    public static final String LIBRARIES_URL =
        "https://libraries.minecraft.net/";

    // ── Launcher identity ─────────────────────────────────────────────────────
    public static final String LAUNCHER_NAME    = "Salwyrr";
    public static final String LAUNCHER_VERSION = "2.0.0";

    // ── Minecraft version (set by CLI --version arg from Electron) ────────────
    public static String MINECRAFT_VERSION = "1.20.1";

    // ── Game directory — matches Electron launcher's dataPath ─────────────────
    //    Windows : %APPDATA%\.Salwyrr
    //    macOS   : ~/Library/Application Support/Salwyrr
    //    Linux   : ~/.Salwyrr
    //    Overridable at runtime via --data-dir (setDataDir).
    public static Path GAME_DIR = defaultGameDir();

    // ── Shared (not per-version) directories ──────────────────────────────────
    public static Path VERSIONS_DIR  = GAME_DIR.resolve("versions");
    public static Path LIBRARIES_DIR = GAME_DIR.resolve("libraries");
    public static Path ASSETS_DIR    = GAME_DIR.resolve("assets");
    public static Path NATIVES_DIR   = GAME_DIR.resolve("natives");
    public static Path JAVA_DIR      = GAME_DIR.resolve("java");
    public static Path PREFS_FILE    = GAME_DIR.resolve("launcher.properties");

    // ── Instances directory root — each version gets its own sub-folder ───────
    //    e.g. .Salwyrr/instances/1.21.6/mods
    //         .Salwyrr/instances/26.1/resourcepacks
    public static Path INSTANCES_DIR = GAME_DIR.resolve("instances");

    /**
     * Default game directory, derived from the OS. Falls back to the user's
     * home directory on Windows when %APPDATA% is unset (server/CI runs).
     */
    private static Path defaultGameDir() {
        if (isWindows()) {
            String appData = System.getenv("APPDATA");
            if (appData == null || appData.isEmpty())
                appData = System.getProperty("user.home");
            return Paths.get(appData, ".Salwyrr");
        }
        if (isMac())
            return Paths.get(System.getProperty("user.home"),
                             "Library/Application Support/Salwyrr");
        return Paths.get(System.getProperty("user.home"), ".Salwyrr");
    }

    /**
     * Override the game directory. Called with the Electron launcher's data
     * directory via the {@code --data-dir} CLI argument so that a custom
     * {@code launcher.dataDirectory} setting is honoured.
     */
    public static void setDataDir(String dir) {
        GAME_DIR = (dir == null || dir.isEmpty()) ? defaultGameDir() : Paths.get(dir);
        VERSIONS_DIR  = GAME_DIR.resolve("versions");
        LIBRARIES_DIR = GAME_DIR.resolve("libraries");
        ASSETS_DIR    = GAME_DIR.resolve("assets");
        NATIVES_DIR   = GAME_DIR.resolve("natives");
        JAVA_DIR      = GAME_DIR.resolve("java");
        PREFS_FILE    = GAME_DIR.resolve("launcher.properties");
        INSTANCES_DIR = GAME_DIR.resolve("instances");
    }

    // ── Per-version accessors (depend on MINECRAFT_VERSION) ───────────────────
    public static Path instanceDir() {
        return INSTANCES_DIR.resolve(MINECRAFT_VERSION);
    }

    public static Path modsDir() {
        return instanceDir().resolve("mods");
    }

    public static Path resourcePacksDir() {
        return instanceDir().resolve("resourcepacks");
    }

    public static Path shaderPacksDir() {
        return instanceDir().resolve("shaderpacks");
    }

    public static Path screenshotsDir() {
        return instanceDir().resolve("screenshots");
    }

    public static Path savesDir() {
        return instanceDir().resolve("saves");
    }

    public static Path logsDir() {
        return instanceDir().resolve("logs");
    }

    public static Path crashReportsDir() {
        return instanceDir().resolve("crash-reports");
    }

    // ── Derived paths ─────────────────────────────────────────────────────────
    public static Path versionDir() {
        return VERSIONS_DIR.resolve(MINECRAFT_VERSION);
    }

    public static Path clientJar() {
        return versionDir().resolve(MINECRAFT_VERSION + ".jar");
    }

    public static Path versionJson() {
        return versionDir().resolve(MINECRAFT_VERSION + ".json");
    }

    public static Path nativesDir() {
        return NATIVES_DIR.resolve(MINECRAFT_VERSION);
    }

    // ── OS helpers ────────────────────────────────────────────────────────────
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    public static boolean isLinux() {
        return !isWindows() && !isMac();
    }

    public static String osName() {
        if (isWindows()) return "windows";
        if (isMac())     return "osx";
        return "linux";
    }

    public static String nativeClassifier() {
        if (isWindows()) return "natives-windows";
        if (isMac())     return "natives-osx";
        return "natives-linux";
    }
}
