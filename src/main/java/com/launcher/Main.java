package com.launcher;

import com.launcher.auth.AuthManager;
import com.launcher.auth.AuthManager.AuthResult;
import com.launcher.download.MinecraftDownloader;
import com.launcher.launch.GameLauncher;
import com.launcher.ui.LauncherFrame;

import javax.swing.*;
import java.nio.file.Files;

public class Main {

    public static void main(String[] args) throws Exception {

        // Parse CLI args passed by the Electron launcher
        String version     = null;
        String username    = null;
        String uuid        = null;
        String token       = null;
        String userType    = null;
        String serverArg   = null;
        int    ram         = 2048;
        boolean headless   = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--version":  if (i+1 < args.length) version   = args[++i]; break;
                case "--username": if (i+1 < args.length) username  = args[++i]; break;
                case "--uuid":     if (i+1 < args.length) uuid      = args[++i]; break;
                case "--token":    if (i+1 < args.length) token     = args[++i]; break;
                case "--usertype": if (i+1 < args.length) userType  = args[++i]; break;
                case "--server":   if (i+1 < args.length) serverArg = args[++i]; break;
                case "--ram":      if (i+1 < args.length) { try { ram = Integer.parseInt(args[++i]); } catch (Exception ignored) {} } break;
                case "--headless": headless = true; break;
            }
        }

        if (version != null) {
            Constants.MINECRAFT_VERSION = version;
        }

        // If called with --headless from Electron: download + launch with no UI
        if (headless && username != null) {
            runHeadless(username, uuid, token, userType, ram, serverArg);
            return;
        }

        // Otherwise open the standalone GUI (user ran the jar directly)
        if (version == null) Constants.MINECRAFT_VERSION = "1.20.1";
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new LauncherFrame().setVisible(true));
    }

    // ── Headless mode — used by the Electron launcher ─────────────────────────

    private static void runHeadless(String username, String uuid,
                                     String token, String userType,
                                     int ram, String server) {
        // All output goes to stdout so Electron can capture and display it
        java.util.function.Consumer<String> log = line -> System.out.println("[Salwyrr Launcher JAR] " + line);
        java.util.function.Consumer<Integer> progress = pct -> System.out.println("[PROGRESS] " + pct);

        try {
            // 1. Download missing files (skips already-present files)
            log.accept("Checking game files for " + Constants.MINECRAFT_VERSION + "...");
            MinecraftDownloader dl = new MinecraftDownloader(log, progress);
            dl.downloadAll();

            // 2. Build auth result from args (already authenticated by Electron side)
            if (uuid == null || uuid.isEmpty()) uuid = "a00000000000000000000000000000000";
            if (token == null || token.isEmpty()) token = "0";
            if (userType == null || userType.isEmpty()) userType = "legacy";

            AuthResult auth = new AuthResult(username, uuid, token, userType,
                                             !token.equals("0"));

            // 3. Launch the game
            log.accept("Launching as " + username + "...");
            GameLauncher launcher = new GameLauncher(log);
            Process proc = launcher.launch(auth, ram,
                                           (server != null && !server.isEmpty()) ? server : null);

            log.accept("Game started!");

            // 4. Pipe game output back to Electron stdout
            new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null)
                        System.out.println("[MC] " + line);
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
}
