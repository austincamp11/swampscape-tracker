# SwampScape Tracker

SwampScape Tracker is an opt-in RuneLite companion for the SwampScape Old School RuneScape clan. It sends a member's selected activity to the clan's private tracking service so Discord updates and daily, weekly, monthly, yearly, and all-time leaderboards can be generated.

## Tracked activity

- Logged-in game time in approximately one-minute increments
- Recognized boss kills and raid completions
- PvP kills
- Deaths, the most recently observed attacking NPC or player, and estimated GE value lost after respawn
- Full loot drops with a combined estimated value of at least 500,000 gp by default
- Optional high-resolution death and loot-flex screenshots

Ordinary NPC kills are not uploaded. Screenshots are disabled by default and can include the visible game chat box when enabled.

## Privacy and network use

Nothing is uploaded until **Share clan activity** is enabled. The plugin can send the following to the server URL configured by the member:

- RuneScape display name
- Selected activity totals and timestamps
- Item IDs, names, quantities, and estimated GE values for qualifying loot
- Recent attacker names and estimated loss for deaths
- A death screenshot only when **Share death screenshots** is separately enabled
- A qualifying loot screenshot only when **Share loot screenshots** is separately enabled
- A revocable connection token in the HTTPS authorization header

The receiving server will also see the connecting IP address. The plugin does not read or transmit passwords, Jagex account credentials, bank contents, Discord credentials, or RuneScape login credentials. Uploads run asynchronously and are accepted only for RuneScape accounts linked to the token's Discord member.

## Connect

1. In the SwampScape Discord server, link the character with `/rsn link`.
2. Run `/tracker connect` and copy the private connection token.
3. Install **SwampScape Tracker** from RuneLite's Plugin Hub.
4. Open the plugin settings and paste the token. The official SwampScape HTTPS server is already configured.
5. Enable **Share clan activity** after reviewing the third-party server warning.
6. Enable only the tracking categories you want to share. Screenshot sharing remains optional.

Qualifying loot is uploaded immediately. Enable **Share loot screenshots** if you want the loot-flex Discord post to include the next rendered game frame.

Running `/tracker connect` again replaces the previous token. `/tracker revoke` disables it without deleting historical clan statistics.

## Development

The project follows RuneLite's external-plugin layout and targets Java 11 bytecode. Build and test it with:

```powershell
./gradlew clean test
```

Run a development client with:

```powershell
./gradlew run
```

The development client requires RuneLite's official Jagex Account development-login procedure. Never commit or share RuneLite credential files.

## License

SwampScape Tracker is available under the BSD 2-Clause License.
