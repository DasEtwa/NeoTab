# NeoTab

NeoTab is a Paper tablist plugin for Minecraft `1.21+` with an animated header, live footer stats, LuckPerms prefix support, and optional PlaceholderAPI placeholders.

## Features

- Animated tab header styles: `rainbow`, `purple-pulse`, `gradient-wave`, `static`
- Live footer stats for RAM usage and ping
- Optional LuckPerms prefix/suffix support in player list names
- Optional PlaceholderAPI support in `server-name` and `ram-format`
- Paper `1.21` API target with Java 21 bytecode

## Build

```powershell
./gradlew build
```

Output:

```text
build/libs/NeoTab-1.1.0.jar
```

## PlaceholderAPI

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
- The current project version is `1.1.0`.
