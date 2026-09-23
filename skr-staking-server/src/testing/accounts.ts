/**
 * Real accounts, copied byte for byte off mainnet-beta on 2026-09-24.
 *
 * These are here so the decoders are tested against what the program actually writes rather than
 * against what this package believes it writes. A layout invented to match a decoder proves nothing;
 * one of these bytes disagreeing with the decoder is the whole point.
 *
 * They are fixtures, not a live read: no test in this package reaches a network.
 */

/** `4HQy82s9CHTv1GsYKnANHMiHfhcqesYkK6sB3RDSYyqw` — the singleton StakeConfig. */
export const STAKE_CONFIG_ACCOUNT = Buffer.from(
  "7pcrAwuXP7D/99/RmBWIoUKnBCJppz3tD3Kiqm19wGlNrfxqo58KnyMGfFo+Bf5BRxKnour+Qr52" +
    "ELzZDL9XFid1g3PLitDYpHK7t3HxKVTi9/Qhl74s+E4dlydE1728RBtKSZ5/fy2TQEIPAAAAAAAA" +
    "owIAAAAAACxgYQItfA8AAAAAAAAAAACqCh9EAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
    "AAAAAAAAAAAAAAAAAAB+rZuKEswRAA==",
  "base64",
);

/** `DPJ58trLsF9yPrBa2pk6UaRkvqW8hWUYjawe788WBuqr` — the official guardian's pool. */
export const GUARDIAN_POOL_ACCOUNT = Buffer.from(
  "he7/1tcLvRcwx3Q4Vi1F71t5KS9hzw81FDWdjOCzNuWXZq0IibG83gZ8WJTOnorbS50M0/6yYuuZ" +
    "boUVKKTmfwK8/8Gr9hNs99/RmBWIoUKnBCJppz3tD3Kiqm19wGlNrfxqo58KnyMsYGECLXwPAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAKoKH0QAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD/" +
    "AQAAAAAAAAAAAAAAAAAAAAA=",
  "base64",
);

/** `8isViKbwhuhFhsv2t8vaFL74pKCqaFPQXo1KkeQwZbB8` — the vault, an SPL token account. */
export const STAKE_VAULT_ACCOUNT = Buffer.from(
  "BnxaPgX+QUcSp6Lq/kK+dhC82Qy/VxYndYNzy4rQ2KQwx3Q4Vi1F71t5KS9hzw81FDWdjOCzNuWX" +
    "Zq0IibG83n6tm4oSzBEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAA" +
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  "base64",
);

/**
 * `12BbTo3vUzvVzYMEtdkoMaSj9BHda4KsBP6VtbRH1rq2` — a real staker who holds shares *and* has a
 * pending unstake, which is the state most worth testing and the one a made-up fixture would miss.
 */
export const USER_STAKE_ACCOUNT = Buffer.from(
  "ZjWjawmKV5n/MMd0OFYtRe9beSkvYc8PNRQ1nYzgszbll2atCImxvN5qoJN4G09+m6amaibxuGjG" +
    "vEBGNszZ0TxMavqCBnUdbbgCUtGNtSuONRWJvQL7jRyzJlcF9a4LANKbp5jhwl3hckEhAAAAAAAA" +
    "AAAAAAAAAExRuj0AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAjUVTUQAAAADDHpVqAAAAAA==",
  "base64",
);

/** That staker's wallet, as the account itself records it. */
export const USER_STAKE_OWNER = "8BEEMWvZorsLCFedYhTrc8k6giigzrhWYuR4eTG1CZDW";

/** That staker's stake account address, which the PDA derivation has to reproduce. */
export const USER_STAKE_ADDRESS =
  "12BbTo3vUzvVzYMEtdkoMaSj9BHda4KsBP6VtbRH1rq2";

/**
 * The share price the config carried when these were copied. Tests that need a price use this one,
 * so the arithmetic is exercised against a real, awkward number rather than a round one.
 */
export const SHARE_PRICE_AT_CAPTURE = 1_142_885_034n;

/** The cooldown the config carried when these were copied: 48 hours, in seconds. */
export const COOLDOWN_AT_CAPTURE = 172_800n;
