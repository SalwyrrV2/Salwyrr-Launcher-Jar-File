package com.launcher;

import com.launcher.auth.AuthManager;
import com.launcher.auth.AuthManager.AuthResult;
import com.launcher.download.MinecraftDownloader;
import com.launcher.install.ModLoaderInstaller;
import com.launcher.install.ModLoaderInstaller.Loader;
import com.launcher.mods.ModManager;
import com.launcher.launch.GameLauncher;

import java.util.List;
import java.util.function.Consumer;

/**
 * Entry point for the Salwyrr Launcher JAR.
 *
 * All modes are driven from CLI flags — the Electron launcher calls this
 * in headless mode.  Flags may be combined freely; the last value wins for
 * single-value flags.
 *
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  GAME LAUNCH (normal use from Electron)                                  │
 * │                                                                          │
 * │  java -jar launcher.jar                                                  │
 * │    --headless                                                            │
 * │    --version  1.21.1                                                     │
 * │    --username Steve                                                      │
 * │    --uuid     &lt;uuid&gt;                                                     │
 * │    --token    &lt;accessToken&gt;                                              │
 * │    --usertype mojang                                                     │
 * │    --ram      4096                                                       │
 * │    [--server  play.example.com:25565]                                    │
 * │                                                                          │
 * ├──────────────────────────────────────────────────────────────────────────┤
 * │  MOD LOADER INSTALL                                                      │
 * │                                                                          │
 * │  java -jar launcher.jar                                                  │
 * │    --install-loader fabric|quilt|forge|neoforge|optifine|none            │
 * │    --version 1.21.1                                                      │
 * │    [--loader-version 0.15.11]   (pin a specific loader version)          │
 * │                                                                          │
 * ├──────────────────────────────────────────────────────────────────────────┤
 * │  LIST AVAILABLE LOADER VERSIONS                                          │
 * │                                                                          │
 * │  java -jar launcher.jar                                                  │
 * │    --list-loaders fabric|quilt|forge|neoforge                            │
 * │    --version 1.21.1                                                      │
 * │                                                                          │
 * ├──────────────────────────────────────────────────────────────────────────┤
 * │  QUERY CURRENT LOADER                                                    │
 * │                                                                          │
 * │  java -jar launcher.jar                                                  │
 * │    --get-loader                                                          │
 * │    --version 1.21.1                                                      │
 * └──────────────────────────────────────────────────────────────────────────┘
 */
public class Main {

    public static void main(String[] args) throws Exception {

        // ── Parse all CLI args ────────────────────────────────────────────────
        String  version       = null;
        String  username      = null;
        String  uuid          = null;
        String  token         = null;
        String  userType      = null;
        String  serverArg     = null;
        int     ram           = 2048;
        boolean headless      = false;

        // Loader-related flags
        String  installLoader  = null;  // --install-loader <loader>
        String  listLoaders    = null;  // --list-loaders   <loader>
        boolean getLoader      = false; // --get-loader
        String  loaderVersion  = null;  // --loader-version  <version>
        String  installMod     = null;  // --install-mod <modrinthProjectId>

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--version":        if (i + 1 < args.length) version       = args[++i]; break;
                case "--username":       if (i + 1 < args.length) username      = args[++i]; break;
                case "--uuid":           if (i + 1 < args.length) uuid          = args[++i]; break;
                case "--token":          if (i + 1 < args.length) token         = args[++i]; break;
                case "--usertype":       if (i + 1 < args.length) userType      = args[++i]; break;
                case "--server":         if (i + 1 < args.length) serverArg     = args[++i]; break;
                case "--install-loader": if (i + 1 < args.length) installLoader = args[++i]; break;
                case "--list-loaders":   if (i + 1 < args.length) listLoaders   = args[++i]; break;
                case "--loader-version": if (i + 1 < args.length) loaderVersion = args[++i]; break;
                case "--get-loader":     getLoader = true; break;
                case "--headless":       headless  = true; break;
                case "--install-mod":    if (i + 1 < args.length) installMod = args[++i]; break;
                case "--ram":
                    if (i + 1 < args.length) {
                        try { ram = Integer.parseInt(args[++i]); } catch (Exception ignored) {}
                    }
                    break;
            }
        }

        // Apply version override first so Constants.MINECRAFT_VERSION is set for all paths
        if (version != null && !version.isEmpty()) {
            Constants.MINECRAFT_VERSION = version;
        }

        // ── Mode dispatch ─────────────────────────────────────────────────────

        // 1. Query current loader
        if (getLoader) {
            Loader current = ModLoaderInstaller.getSelectedLoader();
            System.out.println("[LOADER] " + current.name().toLowerCase());
            return;
        }

        // 2. List available loader versions
        if (listLoaders != null && !listLoaders.isEmpty()) {
            runListLoaders(listLoaders);
            return;
        }

        // 3. Install / remove a mod loader
        if (installLoader != null && !installLoader.isEmpty()) {
            runInstallLoader(installLoader, loaderVersion);
            return;
        }

        // 3b. Install a mod from Modrinth by project ID
        if (installMod != null && !installMod.isEmpty()) {
            runInstallMod(installMod);
            return;
        }

        // 4. Headless game launch (normal use from Electron)
        if (headless && username != null) {
            runHeadless(username, uuid, token, userType, ram, serverArg);
            return;
        }

        // 5. No recognised mode — print usage and exit
        printUsage();
        System.exit(1);
    }

    // ── --list-loaders ────────────────────────────────────────────────────────

    private static void runListLoaders(String loaderName) {
        Consumer<String> log = line -> System.out.println("[Salwyrr Launcher JAR] " + line);
        Loader loader = ModLoaderInstaller.parseLoader(loaderName);
        if (loader == Loader.NONE) {
            System.err.println("[ERROR] Unknown loader: " + loaderName
                + "\nValid loaders: fabric, quilt, forge, neoforge");
            System.exit(1);
        }
        try {
            log.accept("Fetching available " + loader.name() + " versions for MC "
                + Constants.MINECRAFT_VERSION + "...");
            List<String> versions = ModLoaderInstaller.listVersions(loader);
            if (versions.isEmpty()) {
                System.out.println("[VERSIONS] (none found for MC " + Constants.MINECRAFT_VERSION + ")");
            } else {
                for (String v : versions) System.out.println("[VERSION] " + v);
            }
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ── --install-mod ─────────────────────────────────────────────────────────

    private static void runInstallMod(String projectId) {
        Consumer<String> log = line -> System.out.println("[Salwyrr Launcher JAR] " + line);
        try {
            ModManager.downloadFromModrinth(projectId, Constants.MINECRAFT_VERSION, log);
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(1);
        }
    }

    // ── --install-loader ──────────────────────────────────────────────────────

    private static void runInstallLoader(String loaderName, String loaderVersion) {
        Consumer<String> log = line -> System.out.println("[Salwyrr Launcher JAR] " + line);
        Loader loader = ModLoaderInstaller.parseLoader(loaderName);

        // "none" is valid — removes the loader
        if (loader == Loader.NONE && !loaderName.equalsIgnoreCase("none")) {
            System.err.println("[ERROR] Unknown loader: " + loaderName
                + "\nValid loaders: fabric, quilt, forge, neoforge, optifine, none");
            System.exit(1);
        }

        try {
            log.accept("Installing " + loader.name().toLowerCase()
                + " for MC " + Constants.MINECRAFT_VERSION + "...");
            ModLoaderInstaller.install(loader, loaderVersion, log);
            System.out.println("[LOADER_INSTALLED] " + loader.name().toLowerCase());
        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ── Headless game launch ───────────────────────────────────────────────────

    private static void runHeadless(String username, String uuid,
                                     String token, String userType,
                                     int ram, String server) {
        Consumer<String>  log      = line -> System.out.println("[Salwyrr Launcher JAR] " + line);
        Consumer<Integer> progress = pct  -> System.out.println("[PROGRESS] " + pct);

        try {
            // 1. Download / verify game files
            log.accept("Checking game files for " + Constants.MINECRAFT_VERSION + "...");
            MinecraftDownloader dl = new MinecraftDownloader(log, progress);
            dl.downloadAll();

            // 2. Auto-install Fabric as the default mod loader (if not already installed)
            Loader currentLoader = ModLoaderInstaller.getSelectedLoader();
            if (currentLoader == Loader.NONE) {
                log.accept("No mod loader found — installing Fabric for MC " + Constants.MINECRAFT_VERSION + "...");
                try {
                    ModLoaderInstaller.install(Loader.FABRIC, null, log);
                    log.accept("[Fabric] ✓ Fabric installed and set as default loader.");
                } catch (Exception fabricEx) {
                    log.accept("[Fabric] ⚠ Could not install Fabric: " + fabricEx.getMessage());
                    log.accept("[Fabric] Continuing with Vanilla...");
                }
            } else {
                log.accept("Mod loader: " + currentLoader.name() + " (already configured)");
            }

            // 3. Build auth from pre-authenticated Electron args
            if (uuid      == null || uuid.isEmpty())      uuid      = "a000000000000000000000000000000000";
            if (token     == null || token.isEmpty())     token     = "0";
            if (userType  == null || userType.isEmpty())  userType  = "legacy";

            AuthResult auth = new AuthResult(username, uuid, token, userType, !token.equals("0"));

            // 4. Launch
            log.accept("Launching as " + username + "...");
            GameLauncher launcher = new GameLauncher(log);
            Process proc = launcher.launch(auth, ram,
                                           (server != null && !server.isEmpty()) ? server : null);

            log.accept("Game started!");

            // 5. Pipe game stdout back to Electron
            new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) System.out.println("[MC] " + line);
                } catch (Exception ignored) {}
                int code = -1;
                try { code = proc.waitFor(); } catch (Exception ignored) {}
                System.out.println("[EXIT] " + code);
            }, "GameOutput").start();

        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ── Usage ─────────────────────────────────────────────────────────────────

    private static void printUsage() {
        System.err.println("Salwyrr Launcher JAR — usage:\n");
        System.err.println("  Launch game:");
        System.err.println("    java -jar launcher.jar --headless --version <ver> --username <name>");
        System.err.println("      [--uuid <uuid>] [--token <token>] [--usertype mojang|legacy]");
        System.err.println("      [--ram <mb>] [--server <host:port>]\n");
        System.err.println("  Install mod loader:");
        System.err.println("    java -jar launcher.jar --install-loader fabric|quilt|forge|neoforge|optifine|none");
        System.err.println("      --version <mc-ver>  [--loader-version <loader-ver>]\n");
        System.err.println("  List available loader versions:");
        System.err.println("    java -jar launcher.jar --list-loaders fabric|quilt|forge|neoforge");
        System.err.println("      --version <mc-ver>\n");
        System.err.println("  Get currently selected loader:");
        System.err.println("    java -jar launcher.jar --get-loader --version <mc-ver>");
    }
}
