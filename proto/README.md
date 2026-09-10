# proto

The Protobuf contract between the sidecar and the Android app. It's the single source of truth for both, and a Buf module declared in the root `buf.yaml`.

This directory is empty until **SAW-002** defines the Stage 1 live-command protocol. Until then, `pnpm generate` and every `buf` command fail with `Module "proto" had no .proto files`. That's expected, not a broken setup.

The generator versions are already pinned in the root `buf.gen.yaml` and `package.json`. See `docs/development/toolchain.md`.
