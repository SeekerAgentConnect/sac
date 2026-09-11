# proto

The Protobuf contract between the sidecar and the Android app. It's the single source of truth for both, and a Buf module declared in the root `buf.yaml`.

| Path | Contents |
| --- | --- |
| `seekervault/live/v1/live.proto` | Stage 1 `LiveCommandService`: the live diagnostic flow |
| `fixtures/` | Cross-runtime fixtures: `<package path>/<Message>/<case>.json`, plus the `.binpb` that `buf convert` writes from it |

After editing a `.proto` file or a fixture, run `pnpm generate` and commit the output. `pnpm check` runs `buf format` and `buf lint`, and `pnpm check:generated` fails if the committed output is stale.

The flow, rules, and errors are in [`docs/protocol.md`](../docs/protocol.md), and the generator versions in [`docs/development/toolchain.md`](../docs/development/toolchain.md).
