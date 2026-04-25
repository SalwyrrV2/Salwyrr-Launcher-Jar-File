# Salwyrr Launcher JAR

> A Java backend that powers the Salwyrr Minecraft Launcher — handling authentication, game file downloads, mod management, and game launching inside the Electron shell.

---

## Features

- **Multi-account auth** — Offline, Microsoft (device-code OAuth), EasyMC token, and TheAltening token support
- **Account manager** — Save and switch between up to 20 accounts in one click
- **Version picker** — Launch any Minecraft version from a dropdown
- **Automatic downloads** — Fetches missing game files, libraries, assets, and Mojang JRE on demand
- **Mod manager** — List, enable/disable, and delete mods; search and install from Modrinth in-app
- **Direct server connect** — Enter a server IP and connect immediately on launch
- **Headless mode** — Can be driven entirely from the Electron launcher via CLI args (no UI required)
- **G1GC JVM tuning** — Sensible garbage collection flags applied automatically
- **Cross-platform** — Windows, macOS, and Linux supported

---

## Project Structure

```
Salwyrr Launcher JAR/
├── src/main/java/com/launcher/
│   ├── Main.java                  # Entry point; handles CLI args + headless mode
│   ├── Constants.java             # Paths, URLs, launcher identity
│   ├── auth/
│   │   ├── AuthManager.java       # OAuth + token auth flows
│   │   └── AccountStore.java      # Persistent account storage (accounts.json)
│   ├── download/
│   │   ├── MinecraftDownloader.java # Game files, libraries, assets
│   │   └── HttpUtil.java          # Download helpers
│   ├── launch/
│   │   └── GameLauncher.java      # Builds and fires the Minecraft process
│   ├── mods/
│   │   └── ModManager.java        # Modrinth search + local mod management
│   └── ui/
│       └── LauncherFrame.java     # Standalone Swing GUI (optional / direct jar run)
├── libs/                          # Local JARs (if any)
└── pom.xml                        # Maven build + shade + antrun copy
```

---

## Building

**Requirements:** Java 17+, Maven 3.8+

```bash
# From the Salwyrr Launcher JAR directory:
mvn package -q
```

The Maven shade plugin bundles all dependencies into a fat JAR. The antrun plugin then copies `launcher.jar` into `../extracted-source/libraries/java/` automatically.

Expected folder layout:

```
your-project/
├── extracted-source/          ← Electron launcher source
│   └── libraries/java/        ← launcher.jar lands here after build
└── Salwyrr Launcher JAR/                ← this repo
```

---

## Running

### With the Electron Launcher (normal use)

```bash
cd extracted-source
npm install && npm start
```

The Electron side calls `launcher.jar` in headless mode, passing auth and version arguments automatically.

### Standalone (GUI mode)

```bash
java -jar target/launcher.jar
```

Opens the Swing GUI directly. Defaults to Minecraft 1.20.1.

### Headless (CLI)

```bash
java -jar target/launcher.jar \
  --headless \
  --version 1.20.1 \
  --username Steve \
  --uuid <uuid> \
  --token <token> \
  --usertype mojang \
  --ram 4096 \
  --server play.example.com:25565
```

---

## Data Directory

| OS      | Path                                     |
|---------|------------------------------------------|
| Windows | `%APPDATA%\.Salwyrr`                     |
| macOS   | `~/Library/Application Support/Salwyrr` |
| Linux   | `~/.Salwyrr`                             |

Key files:

| File                  | Purpose                            |
|-----------------------|------------------------------------|
| `accounts.json`       | Saved account list                 |
| `.auth_cache.json`    | Microsoft OAuth refresh token      |
| `launcher.properties` | RAM allocation and other prefs     |
| `instances/<ver>/mods/` | Per-version mod folder           |

> Rename a mod file to `.jar.disabled` to disable it without deleting it.

---

## Auth Modes

| Mode        | What you need             | Online? |
|-------------|---------------------------|---------|
| Offline     | Username only             | No      |
| Microsoft   | Browser + Microsoft account | Yes   |
| EasyMC      | Free EasyMC token         | Yes     |
| TheAltening | Altening token            | Yes     |

---

## License

This project is unlicensed and intended for personal / educational use.  
Minecraft is a trademark of Mojang Studios / Microsoft.
