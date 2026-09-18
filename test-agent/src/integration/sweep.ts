/**
 * The privacy sweep (SEE-98): search everything the run wrote for the things that must never leave
 * a device.
 *
 * The claim in `docs/security.md` is that a publisher and the gateway learn a channel was read and
 * nothing else — not an address, not an amount someone chose, not a decision, not a signature.
 * That claim is about files and traffic, so this checks files and traffic: both publisher
 * databases, the gateway's database, every write-ahead log beside them, every line the three
 * processes printed, and the requests the subscribers actually sent.
 *
 * Databases are searched as bytes rather than through SQL. A column nobody meant to write to is
 * still a column, and a freed page still holds what was on it; searching the file finds both, and
 * needs no driver.
 *
 * A sweep that found nothing proves nothing until the search itself is known to work, so
 * `mustFind` is the positive control: the same needles, over something that certainly contains
 * them.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

/** Something that must not appear off the device, and what to call it in a failure. */
export interface Needle {
  readonly what: string;
  readonly value: string;
}

/** Text the run produced: a process's output, or the traffic a device sent. */
export interface Haystack {
  readonly what: string;
  readonly text: string;
}

export interface Finding {
  readonly needle: string;
  readonly where: string;
  /** Enough of the surroundings to see what happened, and never the whole file. */
  readonly sample: string;
}

/** Every file under `directory`, recursively: databases, write-ahead logs and whatever else. */
export function filesUnder(directory: string): string[] {
  const found: string[] = [];
  const walk = (at: string): void => {
    for (const entry of readdirSync(at, { withFileTypes: true })) {
      const path = join(at, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (entry.isFile() && statSync(path).size > 0) found.push(path);
    }
  };
  walk(directory);
  return found;
}

/**
 * Every place one of `needles` turns up. Empty is the answer the privacy claim needs, and every
 * non-empty entry says which needle, in which file, with what around it.
 */
export function sweep(
  needles: readonly Needle[],
  haystacks: readonly Haystack[],
  files: readonly string[],
): Finding[] {
  const found: Finding[] = [];
  const searched: Haystack[] = [
    ...haystacks,
    ...files.map((path) => ({
      what: path,
      // latin1 so a database's bytes become searchable text without a decoder rejecting any of
      // them: every byte maps to one character, and an ASCII needle still matches.
      text: readFileSync(path).toString("latin1"),
    })),
  ];
  for (const needle of needles) {
    for (const haystack of searched) {
      const at = haystack.text.indexOf(needle.value);
      if (at === -1) continue;
      found.push({
        needle: needle.what,
        where: haystack.what,
        sample: haystack.text.slice(Math.max(0, at - 60), at + 60),
      });
    }
  }
  return found;
}

/** The positive control: the search finds these needles where they are known to be. */
export function mustFind(
  needles: readonly Needle[],
  haystack: Haystack,
): string[] {
  return needles
    .filter((needle) => !haystack.text.includes(needle.value))
    .map((needle) => needle.what);
}

/** A sweep's findings as one message, for an assertion that has to explain itself. */
export function describe(findings: readonly Finding[]): string {
  return findings
    .map(
      (finding) =>
        `${finding.needle} appears in ${finding.where}: …${finding.sample}…`,
    )
    .join("\n");
}
