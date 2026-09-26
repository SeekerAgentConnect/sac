import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  ACCOUNT,
  INSTRUCTION,
  OFFICIAL_GUARDIAN,
  SKR_MINT,
  STAKING_PROGRAM_ID,
  associatedTokenAddress,
  eventAuthorityAddress,
  guardianPoolAddress,
  stakeConfigAddress,
  stakeVaultAddress,
  stakingAddresses,
  userStakeAddress,
} from "./program.ts";
import { PublicKey } from "@solana/web3.js";
import { USER_STAKE_ADDRESS, USER_STAKE_OWNER } from "../testing/accounts.ts";

/**
 * The values the program's own published IDL carries. They are written out here rather than
 * imported so that the derivations in `program.ts` are checked against the document, not against
 * themselves: if Anchor's namespacing ever changed, these would still be what the deployed program
 * answers to.
 */
const IDL_INSTRUCTION_DISCRIMINATORS = {
  stake: [206, 176, 202, 18, 200, 209, 179, 108],
  unstake: [90, 95, 107, 42, 205, 124, 50, 225],
  cancelUnstake: [64, 65, 53, 227, 125, 153, 3, 167],
  withdraw: [183, 18, 70, 156, 148, 109, 161, 34],
} as const;

const IDL_ACCOUNT_DISCRIMINATORS = {
  stakeConfig: [238, 151, 43, 3, 11, 151, 63, 176],
  userStake: [102, 53, 163, 107, 9, 138, 87, 153],
  guardianDelegationPool: [133, 238, 255, 214, 215, 11, 189, 23],
} as const;

/** The addresses the deployment actually uses, read off mainnet-beta before they were written. */
const MAINNET = {
  stakeConfig: "4HQy82s9CHTv1GsYKnANHMiHfhcqesYkK6sB3RDSYyqw",
  stakeVault: "8isViKbwhuhFhsv2t8vaFL74pKCqaFPQXo1KkeQwZbB8",
  guardianPool: "DPJ58trLsF9yPrBa2pk6UaRkvqW8hWUYjawe788WBuqr",
} as const;

describe("the staking program's identity", () => {
  it("names the deployed program and the SKR mint", () => {
    assert.equal(
      STAKING_PROGRAM_ID.toBase58(),
      "SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ",
    );
    assert.equal(
      SKR_MINT.toBase58(),
      "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3",
    );
  });

  it("derives every instruction discriminator the IDL publishes", () => {
    for (const [name, expected] of Object.entries(
      IDL_INSTRUCTION_DISCRIMINATORS,
    )) {
      assert.deepEqual(
        [...INSTRUCTION[name as keyof typeof INSTRUCTION]],
        expected,
        `${name} discriminator`,
      );
    }
  });

  it("derives every account discriminator the IDL publishes", () => {
    for (const [name, expected] of Object.entries(IDL_ACCOUNT_DISCRIMINATORS)) {
      assert.deepEqual(
        [...ACCOUNT[name as keyof typeof ACCOUNT]],
        expected,
        `${name} discriminator`,
      );
    }
  });
});

describe("the addresses, derived rather than pasted", () => {
  it("derives the config and the vault the deployment uses", () => {
    assert.equal(stakeConfigAddress().toBase58(), MAINNET.stakeConfig);
    assert.equal(stakeVaultAddress().toBase58(), MAINNET.stakeVault);
  });

  it("derives the official guardian's pool", () => {
    assert.equal(
      guardianPoolAddress(stakeConfigAddress(), OFFICIAL_GUARDIAN).toBase58(),
      MAINNET.guardianPool,
    );
  });

  it("derives a real staker's stake account", () => {
    // The one case that cannot be self-consistent: this address exists on chain, was created by the
    // program, and the derivation has to land on it exactly.
    const derived = userStakeAddress(
      stakeConfigAddress(),
      new PublicKey(USER_STAKE_OWNER),
      guardianPoolAddress(stakeConfigAddress(), OFFICIAL_GUARDIAN),
    );
    assert.equal(derived.toBase58(), USER_STAKE_ADDRESS);
  });

  it("derives the event authority every instruction names", () => {
    // Taken from a real `stake` on mainnet-beta, signature
    // 4aFwJrtYRM1bvWEPp2cdqhaaQTfY7LjsRUiZHm8SeWk2DtYAG2wMqveS4DtHrtodAsb7L6Z4QUT6a3cpdsCrsgUs,
    // where it is the eleventh account of the instruction.
    assert.equal(
      eventAuthorityAddress().toBase58(),
      "8rUTGg1XoyuvK9G64S7d37m3HtLZH24oPeMmXkpJH8ir",
    );
  });

  it("puts a stake account under its guardian, not under its owner alone", () => {
    const config = stakeConfigAddress();
    const owner = new PublicKey(USER_STAKE_OWNER);
    const other = guardianPoolAddress(config, SKR_MINT);
    assert.notEqual(
      userStakeAddress(config, owner, other).toBase58(),
      USER_STAKE_ADDRESS,
    );
  });

  it("collects one consistent set for a guardian", () => {
    const addresses = stakingAddresses();
    assert.equal(addresses.stakeConfig.toBase58(), MAINNET.stakeConfig);
    assert.equal(addresses.stakeVault.toBase58(), MAINNET.stakeVault);
    assert.equal(addresses.guardianPool.toBase58(), MAINNET.guardianPool);
    assert.equal(addresses.guardian.toBase58(), OFFICIAL_GUARDIAN.toBase58());
    assert.equal(addresses.mint.toBase58(), SKR_MINT.toBase58());
  });

  it("derives an owner's associated SKR account", () => {
    // Cross-checked against the SPL specification's own derivation: owner, token program, mint.
    const owner = new PublicKey(USER_STAKE_OWNER);
    const ata = associatedTokenAddress(owner, SKR_MINT);
    assert.notEqual(ata.toBase58(), owner.toBase58());
    assert.equal(
      ata.toBase58(),
      PublicKey.findProgramAddressSync(
        [
          owner.toBuffer(),
          new PublicKey(
            "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
          ).toBuffer(),
          SKR_MINT.toBuffer(),
        ],
        new PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"),
      )[0].toBase58(),
    );
  });
});
