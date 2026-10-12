// The Centrifugo image is configuration, not the gateway binary (SEE-187). This checks the two
// files that are that image and nothing else, so a gateway-only change does not have to rebuild
// the broker and a broker-only change is still visible.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const dockerfile = readFileSync(
  resolve(root, "services/gateway/Dockerfile.centrifugo"),
  "utf8",
);
const config = readFileSync(
  resolve(root, "services/gateway/centrifugo.yaml"),
  "utf8",
);

if (
  !dockerfile.includes(
    "COPY --chown=1000:1000 centrifugo.yaml /centrifugo/config.yaml",
  )
) {
  console.error(
    "services/gateway/Dockerfile.centrifugo must copy centrifugo.yaml into the image",
  );
  process.exit(1);
}
if (
  !config.includes("client:") ||
  !dockerfile.includes("centrifugo/centrifugo:")
) {
  console.error(
    "services/gateway/centrifugo.yaml or Dockerfile.centrifugo is not the pinned broker",
  );
  process.exit(1);
}
console.log("gateway-centrifugo: config and Dockerfile agree");
