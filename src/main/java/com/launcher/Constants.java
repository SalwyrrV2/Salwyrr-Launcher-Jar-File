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
    public static final Path GAME_DIR = Paths.get(
        isWindows() ? System.getenv("APPDATA")
                    : System.getProperty("user.home"),
        isWindows() ? ".Salwyrr"
                    : isMac() ? "Library/Application Support/Salwyrr"
                              : ".Salwyrr"
    );

    // ── Shared (not per-version) directories ──────────────────────────────────
    public static final Path VERSIONS_DIR  = GAME_DIR.resolve("versions");
    public static final Path LIBRARIES_DIR = GAME_DIR.resolve("libraries");
    public static final Path ASSETS_DIR    = GAME_DIR.resolve("assets");
    public static final Path NATIVES_DIR   = GAME_DIR.resolve("natives");
    public static final Path JAVA_DIR      = GAME_DIR.resolve("java");
    public static final Path PREFS_FILE    = GAME_DIR.resolve("launcher.properties");

    // ── Instances directory root — each version gets its own sub-folder ───────
    //    e.g. .Salwyrr/instances/1.21.6/mods
    //         .Salwyrr/instances/26.1/resourcepacks
    public static final Path INSTANCES_DIR = GAME_DIR.resolve("instances");

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

    // ── Legacy static fields kept for any code that still references them ─────
    /** @deprecated Use {@link #modsDir()} instead. */
    @Deprecated public static Path MODS_DIR          = modsDir();
    /** @deprecated Use {@link #resourcePacksDir()} instead. */
    @Deprecated public static Path RESOURCEPACKS_DIR = resourcePacksDir();
    /** @deprecated Use {@link #shaderPacksDir()} instead. */
    @Deprecated public static Path SHADERPACKS_DIR   = shaderPacksDir();
    /** @deprecated Use {@link #screenshotsDir()} instead. */
    @Deprecated public static Path SCREENSHOTS_DIR   = screenshotsDir();
    /** @deprecated Use {@link #savesDir()} instead. */
    @Deprecated public static Path SAVES_DIR         = savesDir();
    /** @deprecated Use {@link #logsDir()} instead. */
    @Deprecated public static Path LOGS_DIR          = logsDir();
    /** @deprecated Use {@link #crashReportsDir()} instead. */
    @Deprecated public static Path CRASH_REPORTS_DIR = crashReportsDir();

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
        String arch = System.getProperty("os.arch", "").contains("64") ? "-64" : "";
        if (isWindows()) return "natives-windows";
        if (isMac())     return "natives-osx";
        return "natives-linux";
    }
}
