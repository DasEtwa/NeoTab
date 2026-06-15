# NeoTab

NeoTab is a lightweight Paper tablist plugin for Minecraft `1.21+` that adds an animated tablist header and a live footer with RAM and ping stats.

Modrinth: https://modrinth.com/plugin/neotab/versions

## Versions

| Version | Type | Minecraft | Notes |
| --- | --- | --- | --- |
| `1.0.2` | Stable | `1.21.11`, `26.1.x` | Original public release. Animated header, RAM footer, ping stats, LuckPerms prefix/suffix support. |
| `1.1.0` | Stable | `1.20.6+` target, tested with `1.20.6`, `1.21.x` and `26.1.x` | Adds optional PlaceholderAPI support, Modrinth update checks, and in-game performance presets. |

Version docs:

- [NeoTab 1.0.2](docs/1.0.2.md)
- [NeoTab 1.1.0](docs/1.1.0.md)

## Features

- Animated tab header styles: `rainbow`, `purple-pulse`, `gradient-wave`, `static`
- Live footer stats for RAM usage and ping
- Optional LuckPerms prefix/suffix support in player list names
- Optional PlaceholderAPI support in `server-name` and `ram-format` (`1.1.0-Beta.1+`)
- Optional Modrinth update checker with admin notifications
- In-game performance presets for tab update intervals
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

PlaceholderAPI support is available in `1.1.0-Beta.1` and newer.

If PlaceholderAPI is installed, NeoTab resolves placeholders inside:

- `server-name`
- `ram-format`

Example:

```yaml
server-name: "<gradient:#AA00AA:#BA55D3>NeoTab PAPI Test: %player_name%</gradient>"
ram-format: "<gray>PAPI: <light_purple>%player_name%</light_purple> | RAM: <light_purple>{used}MB / {total}MB</light_purple> | Ping: {playerPing}ms</gray>"
```

## Update Checker

NeoTab can check Modrinth for newer compatible versions on startup. It does not download or install updates.

```yaml
update-checker:
  enabled: true
  include-beta: false
  notify-admins: true
  check-delay-seconds: 5
```

Players with `neotab.update.notify` receive update messages when `notify-admins` is enabled.

`include-beta: false` only considers Modrinth release versions. Set it to `true` to include beta versions.

## Performance Presets

NeoTab's tab update interval can be changed in-game:

```text
/tab performance smooth
/tab performance balanced
/tab performance light
/tab performance custom 10
/tab performance save event
```

Default preset values:

```yaml
performance:
  active-preset: "smooth"
  presets:
    smooth: 3
    balanced: 10
    light: 20
  saved-presets: {}
update-interval-ticks: 3
```

Every `/tab performance ...` change is saved to `config.yml`. `save [name]` stores the current interval under `performance.saved-presets` and makes it usable again with `/tab performance [name]`.

Players need `neotab.performance` to change these settings.

## Header Bold

Animated headers are no longer forced bold. To restore the old bold animation style:

```yaml
header:
  bold-animation: true
```

## Notes

- PlaceholderAPI is optional and loaded via `softdepend`.
- LuckPerms is optional and loaded via `softdepend`.
- The update checker uses Modrinth's public API and a NeoTab User-Agent.
- The current source version is `1.1.0`.
