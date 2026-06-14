# NeoTab

NeoTab is a lightweight Paper tablist plugin for Minecraft `1.21+` that adds an animated tablist header and a live footer with RAM and ping stats.

Modrinth: https://modrinth.com/plugin/neotab/versions

## Versions

| Version | Type | Minecraft | Notes |
| --- | --- | --- | --- |
| `1.0.2` | Stable | `1.21.11`, `26.1.x` | Original public release. Animated header, RAM footer, ping stats, LuckPerms prefix/suffix support. |
| `1.1.0-beta` | Beta | `1.21+` target, tested with `1.21.x` / `26.1.x` | Adds optional PlaceholderAPI support and Java 21/Paper 1.21 build target. |

Version docs:

- [NeoTab 1.0.2](docs/1.0.2.md)
- [NeoTab 1.1.0 Beta](docs/1.1.0-beta.md)

## Features

- Animated tab header styles: `rainbow`, `purple-pulse`, `gradient-wave`, `static`
- Live footer stats for RAM usage and ping
- Optional LuckPerms prefix/suffix support in player list names
- Optional PlaceholderAPI support in `server-name` and `ram-format` (`1.1.0-beta+`)
- Paper `1.21` API target with Java 21 bytecode

## Installation

1. Download the JAR from Modrinth or GitHub Releases.
2. Put the JAR into your Paper server's `plugins` folder.
3. Restart the server.
4. Edit `plugins/NeoTab/config.yml`.
5. Use `/tab reload` after config changes.

## Build

```powershell
./gradlew build
```

Output:

```text
build/libs/NeoTab-1.1.0.jar
```

## PlaceholderAPI

PlaceholderAPI support is available in `1.1.0-beta` and newer.

If PlaceholderAPI is installed, NeoTab resolves placeholders inside:

- `server-name`
- `ram-format`

Example:

```yaml
server-name: "<gradient:#AA00AA:#BA55D3>NeoTab PAPI Test: %player_name%</gradient>"
ram-format: "<gray>PAPI: <light_purple>%player_name%</light_purple> | RAM: <light_purple>{used}MB / {total}MB</light_purple> | Ping: {playerPing}ms</gray>"
```

## Notes

- PlaceholderAPI is optional and loaded via `softdepend`.
- LuckPerms is optional and loaded via `softdepend`.
- The current source version is `1.1.0`.
