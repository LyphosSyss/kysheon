# Briefs des flux

Chaque flux est confié à un seul agent, dans sa branche `stream/<lettre>-<nom>` et son propre worktree.
Lire d’abord [`AGENTS.md`](../../../AGENTS.md). Le contrat `Link` et les messages du protocole sont figés.

| Flux | Brief | Branche |
|---|---|---|
| A | [game-core](a-game-core.md) | `stream/a-game-core` |
| B | [protocole, synchro, session](b-protocol-sync.md) | `stream/b-protocol-sync` |
| C | [BLE Android](c-android-ble.md) | `stream/c-android-ble` |
| D | [BLE iOS](d-ios-ble.md) | `stream/d-ios-ble` |
| E | [UI Compose](e-ui.md) | `stream/e-ui` |
| F | [CI et documentation](f-ci-docs.md) | `stream/f-ci-docs` |

Livrable d’un flux : commits atomiques sur sa branche, `jvmTest` vert, compilation Android + iOS,
et un rapport (fait / vérifié / non vérifié / risques).
