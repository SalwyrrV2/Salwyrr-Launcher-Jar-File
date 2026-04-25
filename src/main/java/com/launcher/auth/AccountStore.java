package com.launcher.auth;

import com.launcher.Constants;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.*;
import java.util.*;

/**
 * Persists multiple saved accounts (offline + Microsoft) across sessions.
 */
public class AccountStore {

    private static final Path STORE_FILE = Constants.GAME_DIR.resolve("accounts.json");

    public static class Account {
        public final String type;       // "offline" | "microsoft"
        public final String username;
        public final String uuid;
        public final String accessToken;
        public final long   savedAt;

        public Account(String type, String username, String uuid, String accessToken) {
            this.type        = type;
            this.username    = username;
            this.uuid        = uuid;
            this.accessToken = accessToken;
            this.savedAt     = System.currentTimeMillis();
        }

        private Account(JSONObject o) {
            this.type        = o.optString("type", "offline");
            this.username    = o.optString("username", "Player");
            this.uuid        = o.optString("uuid", "");
            this.accessToken = o.optString("access_token", "0");
            this.savedAt     = o.optLong("saved_at", 0);
        }

        public JSONObject toJson() {
            return new JSONObject()
                .put("type",         type)
                .put("username",     username)
                .put("uuid",         uuid)
                .put("access_token", accessToken)
                .put("saved_at",     savedAt);
        }

        @Override public String toString() {
            return "[" + type.toUpperCase() + "] " + username;
        }
    }

    // ── Load / save ───────────────────────────────────────────────────────────

    public static List<Account> load() {
        List<Account> list = new ArrayList<>();
        try {
            if (!Files.exists(STORE_FILE)) return list;
            JSONArray arr = new JSONArray(readFile(STORE_FILE));
            for (int i = 0; i < arr.length(); i++)
                list.add(new Account(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return list;
    }

    public static void save(List<Account> accounts) {
        try {
            Files.createDirectories(STORE_FILE.getParent());
            JSONArray arr = new JSONArray();
            for (Account a : accounts) arr.put(a.toJson());
            Files.write(STORE_FILE, arr.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    public static void addOrUpdate(Account account) {
        List<Account> list = load();
        list.removeIf(a -> a.username.equalsIgnoreCase(account.username)
                        && a.type.equals(account.type));
        list.add(0, account);          // most-recent first
        if (list.size() > 20) list = list.subList(0, 20);  // cap at 20
        save(list);
    }

    public static void remove(String username, String type) {
        List<Account> list = load();
        list.removeIf(a -> a.username.equalsIgnoreCase(username) && a.type.equals(type));
        save(list);
    }

    private static String readFile(java.nio.file.Path path) throws java.io.IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(path);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
