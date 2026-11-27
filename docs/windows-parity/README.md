# Windows ↔ Android parity notes

This folder is a coordination note for the parallel Android and Windows work. It lives only in `crocaun-android`; the Windows repository is not changed by anything in this document. These are observations and proposals for review, not instructions to change Windows behavior. The existing Windows client remains the functional and protocol reference while Android catches up. Keep platform-specific UI/storage differences local to Android unless both maintainers agree otherwise.

## Current compatibility map

| Area | Current Android behavior | Coordination note |
|---|---|---|
| Croc file transfer | Uses the shared Go Croc core with the Crocaun race patch and the Windows-compatible password/relay mapping. | Preserve the existing wire behavior; investigate a parity bug against Windows before changing it. |
| Croc peer discovery | UDP `29008`, `CROCAU1?` / `CROCAU1!`. | No protocol change proposed; device-level Android↔Windows verification is still outstanding for the current discovery changes. |
| LAN chat | TCP `29014`; room/message framing follows the Windows LAN-chat protocol. | Keep Windows as the protocol reference. Mark any deliberate Android-only deviations here before implementing them. |
| LAN-chat discovery | UDP `29015`, `CROCAUCHAT1?` / `CROCAUCHAT1!`; Android includes a responder. | Match the existing packet format; real-device discovery and connection tests are still required. |
| Internet/Nostr chat | Not implemented in Android. | Do not invent a parallel protocol. First document the Windows identity, event, history, and attachment behavior, then port it. |
| Proxy | No functional Android proxy settings/routing yet. | Do not add a cosmetic toggle. Map Windows proxy semantics to Android-supported network paths before implementation. |

## Open parity decisions / review requests

1. **Sending directory trees:** Android currently stages selected folders recursively but Croc sends the contained files as a flat list, so source subfolder structure is not preserved. Recommendation: do not change Windows for Android's current limitation; first confirm the Windows behavior and make Android adapt to it, or agree on an explicit shared archive/relative-path representation before changing either side.
2. **Discovery and LAN chat:** current packet formats and ports are recorded above from the inspected Windows-compatible implementation. No packet or port change is requested. Please flag any Windows-side format changes early so the Android client can follow the same protocol.
3. **Unverified Android features:** SAF providers, manual filesystem paths, receive auto-save, ZIP extraction, and Android chat discovery have passed local/CI checks but still need real Android↔Windows/device testing. Treat them as experimental until that test is done; do not infer Windows incompatibility from missing device validation.
4. **Internet chat and proxy:** both remain Android ToDo items. Windows-side behavior should be documented as the source of truth before Android implementation begins; this note does not request Windows work or commit either platform to an implementation plan.

## Lightweight sync convention

When a cross-platform discrepancy appears, record: (a) Windows behavior and source/version, (b) Android behavior, (c) wire/data compatibility impact, (d) the smallest Android-side fix, and (e) whether a Windows change is actually required. Mark a decision **Agreed** only after the Windows maintainer reviews it. Keep this page concise and update it when an agreement or verified behavior changes.
