# Salwyrr Launcher JAR 2.1 — Integration Guide

## New Features in 2.1

- **Multiple account types**: Offline, Microsoft (device-code OAuth), EasyMC token, TheAltening token
- **Account manager**: Save up to 20 accounts, switch between them with one click
- **Tabbed UI**: Play / Accounts / Mods / Settings — clean and organized
- **Version picker**: Choose any MC version from the dropdown
- **Mods tab**: List, enable/disable, delete mods; search & install from Modrinth in-app
- **Direct server connect**: Enter a server IP and connect on launch
- **Improved offline UUID**: Proper vanilla-compatible UUID generation
- **G1GC JVM tuning** and Java auto-download (Mojang JRE)

## Build & Deploy

1. Ensure sibling folder layout:
   ```
   your-project/
   ├── extracted-source/      ← Electron launcher
   │   └── libraries/java/    ← launcher.jar lands here
   └── Salwyrr Launcher JAR/            ← this project
   ```

2. Build:
   ```bash
   cd Salwyrr Launcher JAR
   mvn package -q
   ```
   Maven shade-plugin produces `target/launcher.jar`. Copy it into
   `../extracted-source/libraries/java/launcher.jar` (the antrun auto-copy
   mentioned in older docs was never implemented — copy it manually, or let
   the Electron CI build it from source).

3. Run:
   ```bash
   cd extracted-source
   npm install && npm start
   ```

## CLI contract (headless mode)

The Electron launcher spawns:

```
java -jar launcher.jar --headless --version <id> --username <name> --uuid <uuid> \
     --token <token> --usertype <msa|legacy> --ram <mb> [--data-dir <dir>] \
     [--server <host:port>]
```

- `--data-dir` overrides the game directory (used for a custom
  `launcher.dataDirectory` setting). Defaults to the OS path below.
- `--server <host:port>` enables direct server connect on launch.

### stdout protocol

All progress/status output is printed to stdout, prefixed:

| Prefix                 | Meaning                                  |
|------------------------|------------------------------------------|
| `[Salwyrr Launcher JAR]` | Human-readable log line                |
| `[PROGRESS] <0-100>`     | Overall download/launch progress        |
| `[MC] <line>`            | Minecraft's own stdout line             |
| `[EXIT] <code>`          | Game process exited with this code      |

Errors go to stderr as `[ERROR] <message>` (plus a stack trace) before
`System.exit(1)`.

## Data directory

| OS      | Path                                    |
|---------|-----------------------------------------|
| Windows | `%APPDATA%\.Salwyrr`                    |
| macOS   | `~/Library/Application Support/Salwyrr`|
| Linux   | `~/.Salwyrr`                            |

Key files:
- `accounts.json` — saved account list
- `.auth_cache.json` — Microsoft refresh token cache
- `launcher.properties` — RAM and other prefs
- `mods/` — drop .jar mods here; rename to .jar.disabled to disable

## Auth modes

| Mode         | Requires              | Online? |
|--------------|-----------------------|---------|
| Offline      | Username only         | No      |
| Microsoft    | Browser + MS account  | Yes     |
| EasyMC       | Free EasyMC token     | Yes     |
| TheAltening  | Altening token        | Yes     |
