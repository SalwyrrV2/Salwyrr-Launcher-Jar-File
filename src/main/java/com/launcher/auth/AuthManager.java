package com.launcher.auth;

import com.launcher.Constants;
import com.launcher.util.HttpUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/**
 * Authentication manager.
 *
 * Supports:
 *  - OFFLINE   : cracked / offline UUID (no account needed)
 *  - MICROSOFT : OAuth 2.0 device-code → XBL → XSTS → Minecraft JWT
 *  - EASYMC    : EasyMC alt token (free alt service)
 *  - ALTENING  : TheAltening token
 *
 * Microsoft tokens are cached to disk and refreshed automatically.
 */
public class AuthManager {

    // Microsoft public client ID (same as official launcher)
    private static final String CLIENT_ID   = "00000000402b5328";
    private static final String TOKEN_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

    private static final Path TOKEN_CACHE = Constants.GAME_DIR.resolve(".auth_cache.json");

    // ── Result ─────────────────────────────────────────────────────────────────

    public static class AuthResult {
        public final String username;
        public final String uuid;
        public final String accessToken;
        public final String userType;   // "msa" | "legacy" | "offline" | "easymc"
        public final boolean online;

        public AuthResult(String username, String uuid, String accessToken,
                          String userType, boolean online) {
            this.username    = username;
            this.uuid        = uuid;
            this.accessToken = accessToken;
            this.userType    = userType;
            this.online      = online;
        }
    }

    // ── Offline ────────────────────────────────────────────────────────────────

    public static AuthResult loginOffline(String username) {
        UUID uuid = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        String uuidStr = uuid.toString().replace("-", "");
        AuthResult result = new AuthResult(username, uuidStr, "0", "legacy", false);
        AccountStore.addOrUpdate(new AccountStore.Account("offline", username, uuidStr, "0"));
        return result;
    }

    // ── EasyMC alt token ──────────────────────────────────────────────────────

    /**
     * Authenticate using an EasyMC token (free alt service).
     * GET https://api.easymc.io/v1/token/redeem?token=TOKEN
     */
    public static AuthResult loginEasyMC(String token) throws Exception {
        String resp = HttpUtil.fetchString("https://api.easymc.io/v1/token/redeem?token="
            + java.net.URLEncoder.encode(token, "UTF-8"));
        JSONObject json = new JSONObject(resp);
        if (json.has("error"))
            throw new IOException("EasyMC error: " + json.optString("error", "unknown"));

        String mcToken   = json.optString("mcToken", "");
        String sessionId = json.optString("session", "");
        String username  = json.optString("minecraftUsername", "AltAccount");
        String uuid      = json.optString("uuid", UUID.randomUUID().toString().replace("-",""));

        // Use the session to authenticate to session server
        String bearerToken = mcToken.isEmpty() ? sessionId : mcToken;
        AuthResult result = new AuthResult(username, uuid, bearerToken, "legacy", true);
        AccountStore.addOrUpdate(new AccountStore.Account("easymc", username, uuid, bearerToken));
        return result;
    }

    // ── TheAltening ──────────────────────────────────────────────────────────

    /**
     * Authenticate via TheAltening API (Mojang-compatible auth endpoint).
     * Uses their Yggdrasil-compatible endpoint.
     */
    public static AuthResult loginAltening(String token) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("username", token);
        payload.put("password", token);
        payload.put("clientToken", "SalwyrrLauncher");
        JSONObject agent = new JSONObject();
        agent.put("name", "Minecraft");
        agent.put("version", 1);
        payload.put("agent", agent);

        String resp = HttpUtil.postJson(
            "https://authserver.thealtening.com/authenticate",
            payload.toString(), null);
        JSONObject json = new JSONObject(resp);

        if (json.has("error"))
            throw new IOException("Altening error: " + json.optString("errorMessage",
                json.optString("error", "Auth failed")));

        String accessToken = json.optString("accessToken", "");
        JSONObject profile  = json.optJSONObject("selectedProfile");
        if (profile == null)
            throw new IOException("Altening: no Minecraft profile on this account.");

        String username = profile.optString("name", "AltAccount");
        String uuid     = profile.optString("id", "");

        AuthResult result = new AuthResult(username, uuid, accessToken, "legacy", true);
        AccountStore.addOrUpdate(new AccountStore.Account("altening", username, uuid, accessToken));
        return result;
    }

    // ── Microsoft — Device Code flow ──────────────────────────────────────────

    public static JSONObject requestDeviceCode() throws Exception {
        String body = "client_id=" + CLIENT_ID + "&scope=" + encode(TOKEN_SCOPE);
        String resp = HttpUtil.postForm("https://login.live.com/oauth20_connect.srf", body);
        return new JSONObject(resp);
    }

    public static AuthResult pollDeviceCode(String deviceCode) throws Exception {
        String body = "client_id=" + CLIENT_ID
                    + "&device_code=" + encode(deviceCode)
                    + "&grant_type=urn:ietf:params:oauth:grant-type:device_code";
        String resp;
        try {
            resp = HttpUtil.postForm("https://login.live.com/oauth20_token.srf", body);
        } catch (IOException e) {
            return null;
        }

        JSONObject json = new JSONObject(resp);
        if (json.has("error")) {
            String err = json.getString("error");
            if (err.equals("authorization_pending") || err.equals("slow_down"))
                return null;
            throw new IOException("Login error: " + err + " — "
                + json.optString("error_description", ""));
        }

        String msAccessToken  = json.getString("access_token");
        String msRefreshToken = json.optString("refresh_token", "");
        AuthResult result = exchangeForMinecraft(msAccessToken);
        saveRefreshToken(msRefreshToken, result.username);
        AccountStore.addOrUpdate(new AccountStore.Account(
            "microsoft", result.username, result.uuid, result.accessToken));
        return result;
    }

    public static AuthResult trySilentLogin() {
        try {
            if (!Files.exists(TOKEN_CACHE)) return null;
            JSONObject cache = new JSONObject(readFile(TOKEN_CACHE));
            String refreshToken = cache.optString("refresh_token", "");
            if (refreshToken.isEmpty()) return null;

            String body = "client_id=" + CLIENT_ID
                        + "&refresh_token=" + encode(refreshToken)
                        + "&grant_type=refresh_token"
                        + "&scope=" + encode(TOKEN_SCOPE);
            String resp = HttpUtil.postForm("https://login.live.com/oauth20_token.srf", body);
            JSONObject j = new JSONObject(resp);
            if (j.has("error")) return null;

            String newRefresh    = j.optString("refresh_token", refreshToken);
            String msAccessToken = j.getString("access_token");
            AuthResult result    = exchangeForMinecraft(msAccessToken);
            saveRefreshToken(newRefresh, result.username);
            AccountStore.addOrUpdate(new AccountStore.Account(
                "microsoft", result.username, result.uuid, result.accessToken));
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    public static void logout() {
        try { Files.deleteIfExists(TOKEN_CACHE); } catch (Exception ignored) {}
    }

    // ── Microsoft OAuth chain ─────────────────────────────────────────────────

    private static AuthResult exchangeForMinecraft(String msAccessToken) throws Exception {
        // 1. XBL
        JSONObject xblBody = new JSONObject()
            .put("Properties", new JSONObject()
                .put("AuthMethod", "RPS")
                .put("SiteName",   "user.auth.xboxlive.com")
                .put("RpsTicket",  "d=" + msAccessToken))
            .put("RelyingParty", "http://auth.xboxlive.com")
            .put("TokenType",    "JWT");
        JSONObject xbl = new JSONObject(HttpUtil.postJson(
            "https://user.auth.xboxlive.com/user/authenticate", xblBody.toString(), null));
        String xblToken = xbl.getString("Token");
        String userHash = xbl.getJSONObject("DisplayClaims")
                             .getJSONArray("xui").getJSONObject(0).getString("uhs");

        // 2. XSTS
        JSONObject xstsBody = new JSONObject()
            .put("Properties", new JSONObject()
                .put("SandboxId",  "RETAIL")
                .put("UserTokens", new JSONArray().put(xblToken)))
            .put("RelyingParty", "rp://api.minecraftservices.com/")
            .put("TokenType",    "JWT");
        JSONObject xsts = new JSONObject(HttpUtil.postJson(
            "https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody.toString(), null));
        if (xsts.has("XErr"))
            throw new IOException(xboxErrorMessage(xsts.getLong("XErr")));
        String xstsToken = xsts.getString("Token");

        // 3. Minecraft token
        JSONObject mcBody = new JSONObject()
            .put("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
        JSONObject mc = new JSONObject(HttpUtil.postJson(
            "https://api.minecraftservices.com/authentication/login_with_xbox",
            mcBody.toString(), null));
        String mcToken = mc.getString("access_token");

        // 4. Ownership check
        JSONObject ent = new JSONObject(HttpUtil.get(
            "https://api.minecraftservices.com/entitlements/mcstore", mcToken));
        JSONArray items = ent.optJSONArray("items");
        if (items == null || items.length() == 0)
            throw new IOException("This Microsoft account does not own Minecraft.\n"
                + "Please purchase it at minecraft.net, or use Offline mode.");

        // 5. Profile
        JSONObject profile = new JSONObject(HttpUtil.get(
            "https://api.minecraftservices.com/minecraft/profile", mcToken));
        if (profile.has("error"))
            throw new IOException("Profile error: " + profile.optString("errorMessage",
                profile.getString("error")));

        return new AuthResult(
            profile.getString("name"), profile.getString("id"),
            mcToken, "msa", true);
    }

    // ── Token cache ───────────────────────────────────────────────────────────

    private static void saveRefreshToken(String refreshToken, String username) {
        try {
            Files.createDirectories(Constants.GAME_DIR);
            JSONObject cache = new JSONObject()
                .put("refresh_token", refreshToken)
                .put("username",      username)
                .put("saved_at",      System.currentTimeMillis());
            Files.write(TOKEN_CACHE, cache.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    public static String getCachedUsername() {
        try {
            if (!Files.exists(TOKEN_CACHE)) return null;
            return new JSONObject(readFile(TOKEN_CACHE)).optString("username", null);
        } catch (Exception e) { return null; }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String encode(String s) throws Exception {
        return java.net.URLEncoder.encode(s, "UTF-8");
    }

    private static String xboxErrorMessage(long xerr) {
        if (xerr == 2148916233L) return "No Xbox profile. Visit xbox.com to create one.";
        if (xerr == 2148916235L) return "Xbox Live not available in your region.";
        if (xerr == 2148916236L || xerr == 2148916237L) return "Adult verification required.";
        if (xerr == 2148916238L) return "Child account — add to a Microsoft Family.";
        return "Xbox authentication error: " + xerr;
    }

    private static String readFile(java.nio.file.Path path) throws java.io.IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(path);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
