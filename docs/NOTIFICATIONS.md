# Sound & Notifications

Open **Notifications** in the sidebar (Alt+N), or **Edit > Sound > Sound & Notifications...**. The Key Pops Notifications button opens its dungeon choices here too. Settings save locally in `realmShark.properties`. Existing volume, chat/bag switches and selected dungeons carry over.

- **Messages:** incoming whispers/DMs, party chat, guild chat, and trade requests. Your own messages do not trigger these sounds. Whisper alerts require the recipient to match your captured player name; sent whispers and messages with unknown direction stay silent, including keyword sounds. Player-name metadata and letter case do not affect this check.
- **Bags:** white, orange, red, gold, egg and blue bags, including boosted variants. Hiding a bag in Loot does not mute its alert.
- **Key pops:** select dungeons using literal search and Select/Unselect shown. Hidden selections remain unchanged. Optionally include missing dungeon completes for the current character. Both observed openings and existing portal callouts use this profile; callouts do not inflate key-pop statistics.
- **Realm events:** Cube God and Legion General presets, plus Add realm event for other announcements. Each event has its own enable switch, volume and sound. Enable the events you want; new event rules start disabled. Edit a phrase and click Save phrase to refine what triggers it.
- **Other alerts:** independently adjust chat keyword sounds and the existing item/entity/enchantment-match sound. Rule-editor buttons preserve the existing detailed matching workflows.

Item and enchantment rules run locally before optional loot sharing. **Opt-out Loot Sharing** does not disable these alerts. Multiple matching rules on the same item produce one custom alert; separate matching items can each alert. Empty inventory slots and invalid enchant data do not trigger enchant matches, and an invalid slot does not suppress valid matches in later slots.

Long filenames and error/status messages wrap to their full measured text height. Short windows scroll the page while retaining usable settings space. Background status changes preserve the current scroll position instead of pulling the page toward the footer; manual keyboard navigation still reveals its destination.

Each alert offers a built-in sound, a local WAV file (10 MB and 30 seconds maximum), Default to restore its original tone, and Test. Custom files must remain at the chosen path. Test bypasses that alert's enable switch; it still honors master mute and both volume controls. Effective volume is master volume multiplied by alert volume. For example, 50% master and 50% alert produces 25% amplitude. Zero volume is silent. Audio loads on a bounded background worker, and the footer reports missing/unsupported files or unavailable output instead of interrupting capture. On Windows, playback resolves the current default multimedia output through WASAPI for each alert. Longer sounds follow output changes while playing; the footer names the actual selected device. Shared-mode conversion uses the existing device format without changing Windows settings. If the selected output cannot open, the footer reports the failure instead of choosing another device. Other platforms use Java Sound.

## Realm announcement matching

Realm event rules listen to received text packets while the map is `Realm of the Mad God`. Only public system/empty-sender, `#Oryx`, `#Oryx the Mad God`, and `#System` messages are considered. Player messages, whispers, guild and party chat do not trigger these rules. Matching is literal and case insensitive, normalizes whitespace, and requires phrase boundaries. Messages containing common defeat terms (`slain`, `defeated`, `killed`, `destroyed`, `vanquished`, `dead`, `fallen`) are skipped. The same rule can alert at most once per 30 seconds; map transitions reset that cooldown.

The presets use the names `cube god` and `legion general`, found in the local game assets. They are announcement-mention rules, not independently verified spawn detection. Their exact current spawn announcements were not available in those assets or captured during development. Narrow the phrase to the actual spawn wording from your Chat view if another Oryx announcement mentions that event. Localized clients, alternate senders, uncaptured traffic and different wording may not match. The last-match label reports only the rule name and time; this module does not save received chat text.

## Validation

Use the project-local JDK 17, Gradle 7.6.4 and offline cache:

```
gradle.bat -I scripts/notifications-validation.gradle test shadowJar --offline --no-daemon --console=plain
```

Tests cover bundled WAV decoding, silent and combined volume levels, settings persistence, invalid custom files, master mute, announcement source/map filtering, defeat messages, cooldown/reset behavior, custom rule persistence, hidden dungeon selections and actual Swing renders at desktop and compact widths. Live game announcements and audibility on the user's audio device need an in-game/audio check with Test. Launch a fresh build through `Launch-RealmShark.cmd` to use the new module.

## Windows output-switch repair (2026-09-17)

The previous player reopened `AudioSystem.getClip()` for each notification, but that still selected Java Sound's legacy DirectSound "Primary Sound Driver". Live silent tests inside the running RealmShark process showed an active session on G560 while all three Windows default roles pointed to AMR, including after switching G560 -> AMR. No Java Sound override was configured and no old clip remained open. A fresh JVM reproduced the same mismatch. This establishes a legacy-backend routing failure; the exact driver/DirectSound fallback responsible for its intermittency is not established.

The replacement explicitly obtains the default multimedia endpoint and opens a shared WASAPI stream on that endpoint ID. Windows converts the same 44.1 kHz / 16-bit PCM to the selected device's current mix format. A native smoke test successfully played a silent buffer on AMR at its unchanged 384 kHz / 32-bit setting, confirmed independently through Windows audio-session enumeration. A second continuous silent test followed live AMR -> G560 -> AMR default changes without restarting; independent session enumeration then showed its G560 session inactive and its AMR session active. All 151 tests passed. No output defaults, endpoint formats, session volumes or mute settings were changed by the repair.

A single daemon owns all COM interfaces. Input queue and simultaneous streams are bounded, stopped/completed streams release their interfaces, and the default is polled only while sounds are active (100 ms). On a device switch, playback resumes from the first observed unplayed frame; an unplugged device can replay up to one buffer if its final position cannot be read. No default endpoint IDs are persisted. Startup, removal and rendering errors appear in the Notifications footer.

Regression tests use fake endpoints to exercise switches in both directions, mid-sound migration, removal before the next poll, failed opens without fallback, cancellation, completed playback, buffer alignment and queue/stream bounds. Run the complete build with `gradle.bat -I scripts/audio-routing-validation.gradle test shadowJar --offline --no-daemon --console=plain`.

API references: [Windows default endpoint selection](https://learn.microsoft.com/en-us/windows/win32/api/mmdeviceapi/nf-mmdeviceapi-immdeviceenumerator-getdefaultaudioendpoint), [WASAPI shared-mode initialization](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclient-initialize), and [automatic PCM conversion flags](https://learn.microsoft.com/en-us/windows/win32/coreaudio/audclnt-streamflags-xxx-constants).
