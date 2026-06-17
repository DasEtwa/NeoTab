# NeoTab

NeoTab is a lightweight Paper tablist plugin for Minecraft `1.20.6+` that adds an animated tablist header and a live footer with RAM and ping stats.

Modrinth: https://modrinth.com/plugin/neotab/versions

## Versions

| Version | Type | Minecraft | Notes |
| --- | --- | --- | --- |
| `1.0.2` | Stable | `1.21.11`, `26.1.x` | Original public release. Animated header, RAM footer, ping stats, LuckPerms prefix/suffix support. |
| `1.1.0` | Stable | `1.20.6+` target, tested with `1.20.6`, `1.21.x` and `26.1.x` | Adds optional PlaceholderAPI support, Modrinth update checks, and in-game performance presets. |
| `1.1.1` | Patch | `1.20.6+` target, tested with `1.20.6`, `1.21.x`, `26.1.x` and Paper `26.2` beta | Adds ingame header color presets, custom color lists, and improves LuckPerms name color handling. |
| `1.2.0` | Stable | `1.20.6+` target, tested with Paper `26.1.2` | Expands the GUI with direct color controls, scoreboard line presets, deletable scoreboard presets, separate tab/scoreboard intervals, animated scoreboard titles, and configurable ActionBar Timer text. |

Version docs:

- [NeoTab 1.0.2](docs/1.0.2.md)
- [NeoTab 1.1.0](docs/1.1.0.md)
- [NeoTab 1.2.0](docs/1.2.0.md)

## Features

- Animated tab header styles: `rainbow`, `purple-pulse`, `gradient-wave`, `static`
- Live footer stats for RAM usage and ping
- Optional LuckPerms prefix/suffix support in player list names
- Optional PlaceholderAPI support in `server-name` and `ram-format` (`1.1.0-Beta.1+`)
- Optional Modrinth update checker with admin notifications
- In-game performance presets for tab update intervals
- In-game header color presets and custom color lists
- Ingame control panel with `/tab gui`
- Direct GUI color controls for `purple`, `red`, `green`, `gold`, and custom hex colors
- Per-player sidebar scoreboard controls with editable lines, line presets, named presets, and deletable presets
- Separate update intervals for tab and scoreboard rendering
- Optional animated scoreboard title using the same animation styles as the tab header
- Configurable ActionBar Timer text with `{time}` and `timer ends` completion text
- Shared active color palette for tab, scoreboard, chat messages, and timer output
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
build/libs/NeoTab-1.2.0.jar
```

## PlaceholderAPI

PlaceholderAPI support is available in `1.1.0` and newer.

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

## Header Colors

Change the animated header colors in-game:

```text
/tab color purple
/tab color red
/tab color green
/tab color gold
/tab color #AA00AA,#BA55D3,#DDA0DD
```

Custom color lists accept 1-5 hex colors separated by commas. The command saves the colors to `custom-colors` in `config.yml` and applies them live.

Players need `neotab.color` to change header colors.

## Control Panel

Open the ingame control panel with:

```text
/tab gui
```

The GUI has three categories:

- `Tab`: change the tab name, select animation styles, and set color presets or custom hex colors.
- `Scoreboard`: toggle the sidebar, edit lines 1-15, pick line presets, save/load/delete presets, and set the scoreboard title animation.
- `Extras`: set tab and scoreboard update intervals independently and control the ActionBar Timer, including custom durations and custom timer text.

GUI items cannot be taken or moved.

## Scoreboard

Basic commands:

```text
/tab sb on
/tab sb off
/tab sb toggle
/tab sb title <text>
/tab sb style <off|rainbow|purple-pulse|gradient-wave|static>
/tab sb interval <smooth|balanced|light|custom ticks>
/tab sb line <1-15> <text>
/tab sb clear <1-15>
/tab sb clearall
/tab sb save <name>
/tab sb load <name>
/tab sb delete <name>
/tab sb list
```

The GUI can also edit scoreboard lines through presets:

- online players
- player name
- ping
- RAM
- custom text
- clear line

Named scoreboard presets can be saved, loaded, and deleted from the GUI or commands.

Supported built-in placeholders:

```text
{online}
{max}
{ping}
{avg_ping}
{ram_used}
{ram_max}
{ram_percent}
{server_name}
{player}
{player_name}
```

PlaceholderAPI remains optional and is only used when installed and enabled.

## ActionBar Timer

```text
/tab timer start <duration>
/tab timer stop
/tab timer pause
/tab timer resume
/tab timer text <text with {time}>
```

Duration examples: `30s`, `5m`, `10m`, `1h`.

The GUI includes fixed 5 minute and 10 minute starts plus a custom duration chat input. The running timer text is configurable and defaults to only showing `{time}`. When the countdown finishes, NeoTab shows `timer ends`.

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
- The current source version is `1.2.0`.
