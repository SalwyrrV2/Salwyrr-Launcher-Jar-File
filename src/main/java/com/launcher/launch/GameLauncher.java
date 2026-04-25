package com.launcher.launch;

import com.launcher.Constants;
import com.launcher.auth.AuthManager.AuthResult;
import com.launcher.download.MinecraftDownloader;
import com.launcher.install.ModLoaderInstaller;
import com.launcher.mods.ModManager;
import com.launcher.util.JavaManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public class GameLauncher {

    private final Consumer<String> log;

    public GameLauncher(Consumer<String> log) {
        this.log = log;
    }

    public Process launch(AuthResult auth, int ramMb) throws Exception {
        return launch(auth, ramMb, null);
    }

    public Process launch(AuthResult auth, int ramMb, String serverAddress) throws Exception {
        Path versionJsonPath = Constants.versionJson();
        if (!Files.exists(versionJsonPath))
            throw new FileNotFoundException(
                "Version JSON missing — click Download first.\nExpected: " + versionJsonPath);
        if (!Files.exists(Constants.clientJar()))
            throw new FileNotFoundException(
                "Client JAR missing — click Download first.\nExpected: " + Constants.clientJar());

        JSONObject versionData = new JSONObject(readFile(versionJsonPath));

        // ── Merge loader JSON on top of vanilla version data ──────────────────
        Path loaderJson = ModLoaderInstaller.getLoaderVersionJson();
        if (loaderJson != null) {
            log.accept("Loader JSON : " + loaderJson.getFileName());
            JSONObject loaderData = new JSONObject(readFile(loaderJson));
            versionData = mergeLoaderData(versionData, loaderData);
        }

        String       javaExe   = JavaManager.resolveJava(Constants.MINECRAFT_VERSION, log);
        List<Path>   classpath = buildClasspath(versionData);
        List<String> jvmArgs   = buildJvmArgs(versionData, ramMb, auth, javaExe);
        List<String> gameArgs  = buildGameArgs(versionData, auth, serverAddress);
        String       mainClass = versionData.optString("mainClass",
                                     "net.minecraft.client.main.Main");

        List<String> cmd = new ArrayList<String>();
        cmd.add(javaExe);
        cmd.addAll(jvmArgs);
        cmd.add("-cp");
        cmd.add(classpathString(classpath));
        cmd.add(mainClass);
        cmd.addAll(gameArgs);

        log.accept("Main class : " + mainClass);
        log.accept("RAM        : " + ramMb + " MB");
        log.accept("Java       : " + javaExe);
        log.accept("Classpath  : " + classpath.size() + " entries");
        log.accept("Modloader  : " + ModManager.getModLoaderLabel());
        if (serverAddress != null) log.accept("Server     : " + serverAddress);

        // Each version runs in its own instance directory
        Path gameDir = Constants.instanceDir();
        Files.createDirectories(gameDir);
        Files.createDirectories(Constants.modsDir());
        Files.createDirectories(Constants.resourcePacksDir());
        Files.createDirectories(Constants.savesDir());
        Files.createDirectories(Constants.screenshotsDir());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(gameDir.toFile());
        pb.redirectErrorStream(true);
        return pb.start();
    }

    // ── Loader merge ──────────────────────────────────────────────────────────

    /**
     * Merges the loader's version JSON on top of the vanilla version data:
     *  - mainClass is replaced by the loader's if present
     *  - libraries arrays are combined (loader's come first for priority)
     *  - arguments.game and arguments.jvm are appended
     *  - minecraftArguments (legacy) is appended
     */
    private JSONObject mergeLoaderData(JSONObject vanilla, JSONObject loader) {
        JSONObject merged = new JSONObject(vanilla.toString()); // deep copy

        // Override mainClass
        String loaderMain = loader.optString("mainClass", null);
        if (loaderMain != null && !loaderMain.isEmpty())
            merged.put("mainClass", loaderMain);

        // Merge libraries (loader's first so they take classpath priority)
        JSONArray vanillaLibs = merged.optJSONArray("libraries");
        JSONArray loaderLibs  = loader.optJSONArray("libraries");
        if (loaderLibs != null) {
            JSONArray combined = new JSONArray();
            for (int i = 0; i < loaderLibs.length(); i++)  combined.put(loaderLibs.get(i));
            if (vanillaLibs != null)
                for (int i = 0; i < vanillaLibs.length(); i++) combined.put(vanillaLibs.get(i));
            merged.put("libraries", combined);
        }

        // Merge arguments (modern format)
        JSONObject loaderArgs = loader.optJSONObject("arguments");
        if (loaderArgs != null) {
            JSONObject mergedArgs = merged.optJSONObject("arguments");
            if (mergedArgs == null) { mergedArgs = new JSONObject(); merged.put("arguments", mergedArgs); }
            appendArgs(mergedArgs, loaderArgs, "game");
            appendArgs(mergedArgs, loaderArgs, "jvm");
        }

        // Merge legacy minecraftArguments
        String loaderLegacy = loader.optString("minecraftArguments", null);
        if (loaderLegacy != null && !loaderLegacy.isEmpty()) {
            String existingLegacy = merged.optString("minecraftArguments", "");
            merged.put("minecraftArguments",
                (existingLegacy.isEmpty() ? loaderLegacy : existingLegacy + " " + loaderLegacy));
        }

        return merged;
    }

    private void appendArgs(JSONObject target, JSONObject source, String key) {
        JSONArray srcArr = source.optJSONArray(key);
        if (srcArr == null) return;
        JSONArray tgtArr = target.optJSONArray(key);
        if (tgtArr == null) { tgtArr = new JSONArray(); target.put(key, tgtArr); }
        for (int i = 0; i < srcArr.length(); i++) tgtArr.put(srcArr.get(i));
    }

    // ── Classpath ─────────────────────────────────────────────────────────────

    private List<Path> buildClasspath(JSONObject versionData) throws Exception {
        MinecraftDownloader helper = new MinecraftDownloader(log, new Consumer<Integer>() {
            public void accept(Integer d) {}
        });
        List<Path> cp = new ArrayList<Path>();

        JSONArray libraries = versionData.getJSONArray("libraries");
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject lib = libraries.getJSONObject(i);
            if (!helper.rulesAllow(lib)) continue;
            JSONObject downloads = lib.optJSONObject("downloads");
            if (downloads == null) continue;
            JSONObject artifact = downloads.optJSONObject("artifact");
            if (artifact == null) continue;
            String path = artifact.optString("path", null);
            if (path == null) continue;
            Path jar = Constants.LIBRARIES_DIR.resolve(path.replace("/", File.separator));
            if (Files.exists(jar)) cp.add(jar);
        }
        cp.add(Constants.clientJar());
        return cp;
    }

    private String classpathString(List<Path> cp) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cp.size(); i++) {
            if (i > 0) sb.append(File.pathSeparator);
            sb.append(cp.get(i).toAbsolutePath().toString());
        }
        return sb.toString();
    }

    // ── JVM Arguments ─────────────────────────────────────────────────────────

    private List<String> buildJvmArgs(JSONObject versionData, int ramMb, AuthResult auth, String javaExe) {
        List<String> args = new ArrayList<String>();

        args.add("-Xms512m");
        args.add("-Xmx" + ramMb + "m");
        args.add("-XX:+UseG1GC");
        args.add("-XX:+ParallelRefProcEnabled");
        args.add("-XX:MaxGCPauseMillis=200");
        args.add("-XX:+UnlockExperimentalVMOptions");
        args.add("-XX:+DisableExplicitGC");
        args.add("-XX:+AlwaysPreTouch");
        args.add("-XX:G1NewSizePercent=30");
        args.add("-XX:G1MaxNewSizePercent=40");
        args.add("-XX:G1HeapRegionSize=8M");
        args.add("-XX:G1ReservePercent=20");
        args.add("-XX:G1HeapWastePercent=5");
        args.add("-XX:G1MixedGCCountTarget=4");
        args.add("-XX:InitiatingHeapOccupancyPercent=15");
        args.add("-XX:G1MixedGCLiveThresholdPercent=90");
        args.add("-XX:G1RSetUpdatingPauseTimePercent=5");
        args.add("-XX:SurvivorRatio=32");
        args.add("-XX:+PerfDisableSharedMem");
        args.add("-XX:MaxTenuringThreshold=1");

        args.add("-Djava.library.path=" + Constants.nativesDir().toAbsolutePath());
        args.add("-Dfile.encoding=UTF-8");
        args.add("-Dlog4j2.formatMsgNoLookups=true");

        if (Constants.isMac()) {
            args.add("-XstartOnFirstThread");
            args.add("-Dapple.awt.application.appearance=system");
        }

        // Java 9+ module opens — check the actual Minecraft JRE we are about to run
        int jvMajor = JavaManager.getJavaMajorVersion(javaExe);
        if (jvMajor >= 9) {
            args.add("--add-opens=java.base/java.util=ALL-UNNAMED");
            args.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
            args.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
            args.add("--add-opens=java.base/java.io=ALL-UNNAMED");
            args.add("--add-exports=java.base/sun.security.util=ALL-UNNAMED");
            args.add("--add-exports=jdk.naming.dns/com.sun.jndi.dns=java.naming");
            args.add("-Djava.rmi.server.useCodebaseOnly=true");
        }

        JSONObject arguments = versionData.optJSONObject("arguments");
        if (arguments != null) {
            JSONArray jvmArr = arguments.optJSONArray("jvm");
            if (jvmArr != null) {
                for (int i = 0; i < jvmArr.length(); i++) {
                    Object item = jvmArr.get(i);
                    if (item instanceof String) {
                        String a = substituteJvmVars((String) item, auth);
                        if (a.isEmpty()) continue;
                        if (a.startsWith("-Djava.library.path=")) continue;
                        if (a.startsWith("-Xms") || a.startsWith("-Xmx")) continue;
                        if (a.equals("-cp") || a.equals("${classpath}")) continue;
                        // Skip flags that require a newer JVM than what we're running
                        if (a.startsWith("--sun-misc-unsafe-memory-access") && jvMajor < 23) continue;
                        args.add(a);
                    } else if (item instanceof JSONObject) {
                        JSONObject cond = (JSONObject) item;
                        if (conditionMet(cond)) {
                            Object val = cond.get("value");
                            if (val instanceof String) {
                                String a = substituteJvmVars((String) val, auth);
                                if (!a.isEmpty() && !(a.startsWith("--sun-misc-unsafe-memory-access") && jvMajor < 23)) args.add(a);
                            } else if (val instanceof JSONArray) {
                                JSONArray arr = (JSONArray) val;
                                for (int j = 0; j < arr.length(); j++) {
                                    String a = substituteJvmVars(arr.getString(j), auth);
                                    if (!a.isEmpty() && !(a.startsWith("--sun-misc-unsafe-memory-access") && jvMajor < 23)) args.add(a);
                                }
                            }
                        }
                    }
                }
            }
        }
        return args;
    }

    // ── Game Arguments ────────────────────────────────────────────────────────

    private List<String> buildGameArgs(JSONObject versionData, AuthResult auth,
                                       String serverAddress) {
        List<String> args = new ArrayList<String>();

        JSONObject arguments = versionData.optJSONObject("arguments");
        if (arguments != null) {
            JSONArray gameArr = arguments.optJSONArray("game");
            if (gameArr != null) {
                for (int i = 0; i < gameArr.length(); i++) {
                    Object item = gameArr.get(i);
                    if (item instanceof String) {
                        args.add(substituteGameVars((String) item, auth));
                    } else if (item instanceof JSONObject) {
                        JSONObject cond = (JSONObject) item;
                        if (conditionMet(cond)) {
                            Object val = cond.get("value");
                            if (val instanceof String) {
                                args.add(substituteGameVars((String) val, auth));
                            } else if (val instanceof JSONArray) {
                                JSONArray arr = (JSONArray) val;
                                for (int j = 0; j < arr.length(); j++)
                                    args.add(substituteGameVars(arr.getString(j), auth));
                            }
                        }
                    }
                }
            }
        } else {
            String legacy = versionData.optString("minecraftArguments", "");
            for (String token : legacy.split(" ")) {
                token = token.trim();
                if (!token.isEmpty())
                    args.add(substituteGameVars(token, auth));
            }
        }

        if (serverAddress != null && !serverAddress.trim().isEmpty()) {
            String[] parts = serverAddress.split(":", 2);
            args.add("--server"); args.add(parts[0]);
            args.add("--port");   args.add(parts.length > 1 ? parts[1] : "25565");
        }

        return args;
    }

    // ── Variable substitution ─────────────────────────────────────────────────

    private String substituteGameVars(String arg, AuthResult auth) {
        return arg
            .replace("${auth_player_name}",  auth.username)
            .replace("${auth_uuid}",         auth.uuid)
            .replace("${auth_access_token}", auth.accessToken)
            .replace("${auth_session}",      auth.accessToken)
            .replace("${user_type}",         auth.userType)
            .replace("${version_name}",      Constants.MINECRAFT_VERSION)
            .replace("${game_directory}",    Constants.instanceDir().toAbsolutePath().toString())
            .replace("${assets_root}",       Constants.ASSETS_DIR.toAbsolutePath().toString())
            .replace("${assets_index_name}", assetsIndexId())
            .replace("${version_type}",      "release")
            .replace("${launcher_name}",     Constants.LAUNCHER_NAME)
            .replace("${launcher_version}",  Constants.LAUNCHER_VERSION)
            .replace("${resolution_width}",  "854")
            .replace("${resolution_height}", "480")
            .replace("${user_properties}",   "{}");
    }

    private String substituteJvmVars(String arg, AuthResult auth) {
        return arg
            .replace("${natives_directory}", Constants.nativesDir().toAbsolutePath().toString())
            .replace("${launcher_name}",     Constants.LAUNCHER_NAME)
            .replace("${launcher_version}",  Constants.LAUNCHER_VERSION)
            .replace("${classpath}",         "");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean conditionMet(JSONObject cond) {
        JSONArray rules = cond.optJSONArray("rules");
        if (rules == null) return true;
        boolean allowed = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            String action   = rule.getString("action");
            JSONObject os   = rule.optJSONObject("os");
            JSONObject feat = rule.optJSONObject("features");
            if (feat != null) continue;
            if (os == null) {
                allowed = action.equals("allow");
            } else {
                String osName = os.optString("name", "");
                if (Constants.osName().equals(osName))
                    allowed = action.equals("allow");
            }
        }
        return allowed;
    }

    private String assetsIndexId() {
        try {
            Path indexDir = Constants.ASSETS_DIR.resolve("indexes");
            if (Files.exists(indexDir)) {
                Path exact = indexDir.resolve(Constants.MINECRAFT_VERSION + ".json");
                if (Files.exists(exact)) return Constants.MINECRAFT_VERSION;
                File[] files = indexDir.toFile().listFiles();
                if (files != null && files.length > 0)
                    return files[0].getName().replace(".json", "");
            }
        } catch (Exception ignored) {}
        return Constants.MINECRAFT_VERSION;
    }

    private static String readFile(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
