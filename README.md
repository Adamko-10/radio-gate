# Radio Gate

Live radio only, with one-time operator approval per station, for **Project Radio** (NeoForge 1.21.1, MIT licensed).

Project Radio lets every player play any URL, and every player's game then opens that link. Radio Gate puts the server in charge:

- A station has to be approved **once by an operator**. After that it works for everyone (saved in the world, `data/radiogate.dat`).
- Local files (the Walkman's `my_music` folder), `file:`, `jar:`, `data:` and similar links never play.
- Loopback and LAN addresses (`localhost`, `127.x`, `192.168.x`, `10.x`, `172.16-31.x`, link-local, `.local`/`.lan`/`.internal` names, and host names that resolve to such addresses) never play.
- Checked on the server for every packet and radio block, and again on every client.

## Requirements

- NeoForge 1.21.1, Java 21.
- **Project Radio** (modId `project_radio`) version 1.5 or newer, on the server **and** on every client. Radio Gate is required on both sides too: a client without it cannot join a server that has it.
- Built and tested against Project Radio **1.5** only. Newer versions may change the classes Radio Gate hooks; in that case the game refuses to start instead of silently leaving the gate open.

Project Radio is a separate mod by n2styles (All Rights Reserved). It is not included in or bundled with Radio Gate: get it from [Project Radio on CurseForge](https://www.curseforge.com/minecraft/mc-mods/project-radio).

## How operators approve a station

1. A player enters a stream URL in Project Radio and presses Launch.
2. If the station is not approved yet, the player is told it was sent to the operators and every online operator gets a chat message with clickable **[Approve]** and **[Deny]** buttons.
3. An operator clicks Approve (or runs `/radiogate approve <id>`). From then on everyone can play that exact URL. The requesting player is told to start it again.

Operators (see `op_level`, and `ops_bypass`) who start a new station themselves get it approved on the spot, as long as the link passes the safety checks.

## Commands (op level 2 by default)

| Command | What it does |
|---|---|
| `/radiogate pending` | Stations waiting for a decision |
| `/radiogate list` | Approved stations |
| `/radiogate denied` | Denied stations |
| `/radiogate approve <id>` / `deny <id>` | Decide a waiting request |
| `/radiogate add <url>` | Approve a station without a request |
| `/radiogate revoke <url>` | Remove an approval; it stops playing for everyone |

## Config (`radiogate-common.toml`)

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch (only for testing) |
| `ops_bypass` | `true` | Operators' own new stations are approved on the spot |
| `op_level` | `2` | Permission level that counts as operator |
| `allowed_schemes` | `["http", "https"]` | Only http/https can ever be allowed |
| `blocked_hosts` | `[]` | Extra hosts to refuse (`radio.example.com` or `*.example.com`) |
| `max_pending_per_player` | `3` | Open requests per player |
| `resolve_hostnames` | `true` | Look names up on the server and refuse private answers |
| `reach_blocks` | `16` | Max distance from a radio block/speaker for a packet to change it |

## Known limits

- An approved `https://station.example/live.mp3` could later point somewhere else (DNS change or redirect); the operator's approval is the control.
- A plain downloadable .mp3 over http(s) looks like a live stream. Approve by looking at the station.

## Building

1. Install Java 21.
2. Put your own copy of `project_radio-1.5.jar` into `libs/` (compile-only, never bundled, never committed), or pass `-Pproject_radio_jar=path/to/project_radio-1.5.jar`.
3. Run `./gradlew build` (Windows: `gradlew.bat build`). The jar is in `build/libs/`.

## License

MIT, see [LICENSE](LICENSE). Project Radio is the property of its author and is not covered by this license.
