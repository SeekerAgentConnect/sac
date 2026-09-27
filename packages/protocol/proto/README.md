# proto

The Protobuf contract between the servers, the gateway and the Android app. It's the single source of truth for all of them, and a Buf module declared in [`packages/protocol/buf.yaml`](../buf.yaml). The generation templates beside it (`packages/protocol/buf.gen*.yaml`) each write into the component that owns the output; see [`docs/development/monorepo-layout.md`](../../../docs/development/monorepo-layout.md#the-protocol-and-its-generated-code).

| Path | Contents |
| --- | --- |
| `seekervault/live/v1/live.proto` | Stage 1 `LiveCommandService`: the live diagnostic flow |
| `seekervault/request/v1/request.proto` | The durable request model, from Stage 2 on: `ActionRequest` and its actions, `RequestState`, `PreparedTransaction`, `PolicyEvaluation`, and `RequestError` |
| `seekervault/request/v1/service.proto` | The phone's durable API: `PairingService` and `RequestService` |
| `fixtures/` | Cross-runtime fixtures: `<package path>/<Message>/<case>.json`, plus the `.binpb` that `buf convert` writes from it |

After editing a `.proto` file or a fixture, run `pnpm generate` and commit the output. `pnpm check` runs `buf format` and `buf lint`, and `pnpm check:generated` fails if the committed output is stale.

The flow, rules, and errors are in [`docs/protocol.md`](../../../docs/protocol.md), and the generator versions in [`docs/development/toolchain.md`](../../../docs/development/toolchain.md).
