import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
  mkdtemp,
  mkdir,
  readFile,
  readdir,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";

import { chromium } from "playwright";
import { format } from "prettier";
import sharp from "sharp";

const TOOL_DIR = path.dirname(fileURLToPath(import.meta.url));
const DESIGN_DIR = path.resolve(TOOL_DIR, "..");
const EXPORT_DIR = path.join(DESIGN_DIR, "export");
const FONT_FILE = path.join(
  TOOL_DIR,
  "fonts",
  "RobotoMono-Latin-400-700.woff2",
);
const EXPORT_NAMES = ["components.html", "app.html", "flow.html"];
const VIEWPORT = { width: 1400, height: 1000 };
const DEFAULT_DSF = 3;
const PHONE_FRAME = { width: 390, height: 844 };
const READY_TIMEOUT_MS = 30_000;
const PNG_ENCODE = Object.freeze({
  compressionLevel: 9,
  adaptiveFiltering: false,
  palette: false,
});

// This is the DOM order observed in flow.html. Captions make a changed export
// fail by name instead of silently assigning a screenshot to the wrong scene.
const FLOW_SCENES = [
  { scene: "home", caption: "1 · Home" },
  { scene: "requests", caption: "2 · Requests" },
  { scene: "wallet", caption: "3 · Wallet" },
  { scene: "activity", caption: "4 · Activity" },
  { scene: "add", caption: "5 · Add connection" },
  { scene: "review", caption: "6 · Request review" },
  { scene: "walletHandoff", caption: "7 · Wallet hand-off" },
  { scene: "connection", caption: "8 · Connection detail" },
  { scene: "rulesConn", caption: "9 · Rules, this connection" },
  { scene: "rulesGlobal", caption: "10 · Global rules" },
  { scene: "assetEdit", caption: "11 · Add / edit asset" },
  { scene: "addAddress", caption: "12 · Add address" },
];

const APP_SCENES = [
  { scene: "reviewSign", heading: "Signature", tileText: "74 bytes" },
  { scene: "reviewAck", heading: "Acknowledge", tileText: "Still here?" },
  { scene: "reviewSignal", heading: "Swap", tileText: "SOL → USDC" },
  {
    scene: "reviewPrediction",
    heading: "Prediction",
    tileText: "BTC < $68k",
  },
];

const REVIEW_SHEETS = [
  { name: "sheet-transfer", caption: "Transfer · request" },
  { name: "sheet-swap", caption: "Swap · signal" },
  { name: "sheet-prediction", caption: "Prediction · signal" },
  { name: "sheet-signature", caption: "Signature · request" },
  { name: "sheet-acknowledge", caption: "Acknowledge · request" },
];

const EXPECTED_COMPONENTS = [
  "button",
  "icon-button",
  "check-row",
  "daily-row",
  "empty-state",
  "fab",
  "fact-row",
  "filter-bar",
  "history-row",
  "inbox-row",
  "nav-item",
  "notice-card",
  "owner-input-card",
  "radio-row",
  "request-carousel",
  "request-tile",
  "rule-row",
  "section-header",
  "segmented",
  "server-row",
  "sheet-scaffold",
  "source-avatar",
  "switch-row",
  "tab-bar",
  "terms-card",
  "text-field",
  "verdict-card",
  "wallet-banner",
  "wallet-handoff",
  "verdict-pill",
  "signal-label",
  "source-chip",
  "env-chip",
  "network-chip",
  "scope-chip",
];

function usage() {
  return [
    "Usage: npm --prefix design/tools run capture -- [options]",
    "",
    "Options:",
    "  --only components|screens|tokens  Generate or check one output group",
    `  --dsf <number>                    Device scale factor (default ${DEFAULT_DSF})`,
    "  --check                           Compare a temporary capture with committed output",
    "  --help                            Show this help",
  ].join("\n");
}

function parseArgs(argv) {
  const options = { only: null, dsf: DEFAULT_DSF, check: false };

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--help") {
      console.log(usage());
      process.exit(0);
    }
    if (argument === "--check") {
      options.check = true;
      continue;
    }
    if (argument === "--only" || argument.startsWith("--only=")) {
      const value = argument.includes("=")
        ? argument.slice(argument.indexOf("=") + 1)
        : argv[++index];
      if (!["components", "screens", "tokens"].includes(value)) {
        throw new Error(
          `--only must be components, screens, or tokens; received ${value ?? "nothing"}`,
        );
      }
      options.only = value;
      continue;
    }
    if (argument === "--dsf" || argument.startsWith("--dsf=")) {
      const value = argument.includes("=")
        ? argument.slice(argument.indexOf("=") + 1)
        : argv[++index];
      options.dsf = Number(value);
      if (
        !Number.isFinite(options.dsf) ||
        options.dsf <= 0 ||
        options.dsf > 8
      ) {
        throw new Error(
          `--dsf must be greater than 0 and at most 8; received ${value}`,
        );
      }
      continue;
    }
    throw new Error(`Unknown argument: ${argument}\n\n${usage()}`);
  }

  return options;
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function slugVariant(label) {
  return label
    .toLowerCase()
    .replace(/[=\s]+/g, "-")
    .replace(/[^a-z0-9-]/g, "")
    .replace(/-+/g, "-")
    .replace(/^-|-$/g, "");
}

function formatDimension(value) {
  return Number(value.toFixed(3)).toString();
}

function markdownCell(value) {
  return String(value).replaceAll("|", "\\|").replaceAll("\n", " ");
}

function normalizeManifest(value) {
  const parsed = JSON.parse(value);
  delete parsed.capturedAt;
  return `${JSON.stringify(parsed, null, 2)}\n`;
}

async function pathExists(target) {
  try {
    await stat(target);
    return true;
  } catch (error) {
    if (error.code === "ENOENT") return false;
    throw error;
  }
}

async function loadCapturePage(browser, exportName, kind, fontCss, initial) {
  const context = await browser.newContext({
    viewport: VIEWPORT,
    deviceScaleFactor: currentOptions.dsf,
    reducedMotion: "reduce",
    offline: true,
  });
  await context.route(/^https?:\/\//, (route) =>
    route.abort("blockedbyclient"),
  );

  const page = await context.newPage();
  const bundleMessages = [];
  const pageErrors = [];
  page.on("console", (message) => {
    const text = message.text();
    if (text.includes("[bundle]") || text.includes("Bundle unpack error")) {
      bundleMessages.push(`${message.type()}: ${text}`);
    }
  });
  page.on("pageerror", (error) => pageErrors.push(error.message));

  await page.addInitScript(
    ({ requestedInitial }) => {
      const originalReplaceWith = Element.prototype.replaceWith;
      Element.prototype.replaceWith = function replaceWith(...nodes) {
        for (const node of nodes) {
          if (!(node instanceof Element)) continue;
          const imports = [
            ...(node.matches("dc-import") ? [node] : []),
            ...node.querySelectorAll("dc-import"),
          ];
          for (const imported of imports) {
            imported.setAttribute("theme", "dark");
            if (requestedInitial)
              imported.setAttribute("initial", requestedInitial);
          }
        }
        return originalReplaceWith.apply(this, nodes);
      };
    },
    { requestedInitial: initial ?? null },
  );

  const absolutePath = path.join(EXPORT_DIR, exportName);
  try {
    await page.goto(pathToFileURL(absolutePath).href, { waitUntil: "load" });

    if (kind === "components") {
      // Keep this condition in step with the export-format contract in SEE-113.
      await page.waitForFunction(
        () =>
          !document.getElementById("__bundler_loading") &&
          document.querySelectorAll("[data-component][data-variant]").length >
            50,
        undefined,
        { timeout: READY_TIMEOUT_MS },
      );
    } else {
      await page.waitForFunction(
        () =>
          !document.getElementById("__bundler_loading") &&
          [...document.querySelectorAll("[style]")].some(
            (element) =>
              element.style.width === "390px" &&
              element.style.height === "844px",
          ),
        undefined,
        { timeout: READY_TIMEOUT_MS },
      );
    }

    await page.addStyleTag({ content: fontCss });
    await page.evaluate(() => document.fonts.ready);
    await page.waitForFunction(
      () =>
        document.fonts.check('24px "Material Symbols Outlined"') &&
        document.fonts.check("500 14px Roboto"),
      undefined,
      { timeout: READY_TIMEOUT_MS },
    );
    const monoFaces = await page.evaluate(
      async () =>
        (
          await document.fonts.load(
            '500 14px "Roboto Mono"',
            "Bzy2LsonMmTZmLpKX3dAZ4NLaEqTQs772CzUm2B16K54",
          )
        ).length,
    );
    if (monoFaces === 0)
      throw new Error("Roboto Mono did not load from the local WOFF2");
    await page.waitForFunction(() =>
      document.fonts.check('500 14px "Roboto Mono"'),
    );
    await page.waitForTimeout(300);

    const countSelector =
      kind === "components"
        ? "[data-component][data-variant]"
        : '[style*="width: 390px"][style*="height: 844px"]';
    const firstCount = await page.locator(countSelector).count();
    await page.waitForTimeout(100);
    const secondCount = await page.locator(countSelector).count();
    if (firstCount !== secondCount) {
      throw new Error(
        `${exportName} did not settle: ${firstCount} elements became ${secondCount}`,
      );
    }
    if (firstCount === 0)
      throw new Error(`${exportName} rendered zero target elements`);

    const bundleError = await page.evaluate(() => {
      const element = document.getElementById("__bundler_err");
      return element
        ? element.textContent?.trim() || "unknown bundle error"
        : null;
    });
    if (bundleError) throw new Error(`${exportName}: ${bundleError}`);
    if (bundleMessages.length > 0) {
      throw new Error(`${exportName}: ${bundleMessages.join("\n")}`);
    }
    if (pageErrors.length > 0) {
      throw new Error(`${exportName}: page error: ${pageErrors.join("\n")}`);
    }

    const palette = await page.evaluate(() => {
      const roots = [...document.querySelectorAll("[style]")].filter(
        (element) => [...element.style].includes("--surf"),
      );
      return roots.map((element) =>
        getComputedStyle(element)
          .getPropertyValue("--surf")
          .trim()
          .toLowerCase(),
      );
    });
    if (palette.length === 0 || palette.some((value) => value !== "#121212")) {
      throw new Error(
        `${exportName} did not render exclusively in the dark theme`,
      );
    }

    return { context, page, targetCount: secondCount };
  } catch (error) {
    const diagnostic = await page
      .evaluate(() => {
        const element = document.getElementById("__bundler_err");
        return element?.textContent?.trim() || null;
      })
      .catch(() => null);
    await context.close();
    if (diagnostic && !error.message.includes(diagnostic)) {
      throw new Error(`${error.message}\nBundle error: ${diagnostic}`, {
        cause: error,
      });
    }
    throw error;
  }
}

async function serializeElement(element, metadata) {
  const result = await element.evaluate((source) => {
    const voidElements = new Set([
      "area",
      "base",
      "br",
      "col",
      "embed",
      "hr",
      "img",
      "input",
      "link",
      "meta",
      "param",
      "source",
      "track",
      "wbr",
    ]);

    const escapeText = (value) =>
      value
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;");
    const escapeAttribute = (value) =>
      escapeText(value).replaceAll('"', "&quot;");

    const resolveVariables = (value, element) => {
      let resolved = value;
      for (
        let iteration = 0;
        iteration < 20 && resolved.includes("var(");
        iteration += 1
      ) {
        const before = resolved;
        resolved = resolved.replace(
          /var\(\s*(--[a-zA-Z0-9_-]+)(?:\s*,\s*([^)]*))?\)/g,
          (_match, name, fallback = "") =>
            getComputedStyle(element).getPropertyValue(name).trim() ||
            fallback.trim(),
        );
        if (resolved === before) break;
      }
      if (resolved.includes("var(--")) {
        throw new Error(`Could not resolve CSS variable in: ${resolved}`);
      }
      return resolved;
    };

    const serialize = (node, depth) => {
      const indent = "  ".repeat(depth);
      if (node.nodeType === Node.TEXT_NODE) {
        const text = node.nodeValue.replace(/\s+/g, " ").trim();
        return text ? [`${indent}${escapeText(text)}`] : [];
      }
      if (node.nodeType !== Node.ELEMENT_NODE) return [];

      const tag = node.tagName.toLowerCase();
      const attributes = [...node.attributes].filter(({ name }) => {
        const lower = name.toLowerCase();
        return (
          lower !== "style" &&
          !lower.startsWith("on") &&
          !["data-reactroot", "data-reactid", "data-react-checksum"].includes(
            lower,
          )
        );
      });
      const styles = (node.getAttribute("style") ?? "")
        .split(";")
        .map((declaration) => declaration.trim())
        .filter(Boolean)
        .map((declaration) => {
          const separator = declaration.indexOf(":");
          if (separator < 1) {
            throw new Error(`Malformed inline style: ${declaration}`);
          }
          const property = declaration.slice(0, separator).trim().toLowerCase();
          const value = resolveVariables(
            declaration.slice(separator + 1).trim(),
            node,
          );
          return `${property}:${value};`;
        });

      const lines = [];
      if (attributes.length === 0 && styles.length === 0) {
        lines.push(`${indent}<${tag}>`);
      } else {
        lines.push(`${indent}<${tag}`);
        for (const { name, value } of attributes) {
          lines.push(`${indent}  ${name}="${escapeAttribute(value)}"`);
        }
        if (styles.length > 0) {
          lines.push(`${indent}  style="`);
          for (const style of styles) lines.push(`${indent}    ${style}`);
          lines.push(`${indent}  "`);
        }
        lines.push(`${indent}>`);
      }

      if (voidElements.has(tag)) return lines;
      for (const child of node.childNodes)
        lines.push(...serialize(child, depth + 1));
      lines.push(`${indent}</${tag}>`);
      return lines;
    };

    const rectangle = source.getBoundingClientRect();
    return {
      html: serialize(source, 0).join("\n"),
      width: rectangle.width,
      height: rectangle.height,
    };
  });

  if (result.html.includes("var(--")) {
    throw new Error(
      `${metadata.name} still contains an unresolved CSS variable`,
    );
  }
  if (result.html.includes("{{")) {
    throw new Error(
      `${metadata.name} still contains an unpacked template binding`,
    );
  }

  const commentParts = Object.entries(metadata).map(
    ([key, value]) => `${key}: ${value}`,
  );
  commentParts.push(
    `boundingBox: ${formatDimension(result.width)} × ${formatDimension(result.height)} CSS px`,
  );
  return {
    ...result,
    html: `<!-- ${commentParts.join("; ")} -->\n${result.html}\n`,
  };
}

async function writeExactPng(element, outputPath) {
  const box = await element.boundingBox();
  if (!box) {
    throw new Error(`No bounding box for ${outputPath}`);
  }

  const dsf = currentOptions.dsf;
  const buf = await element.screenshot({
    animations: "disabled",
    omitBackground: false,
  });
  const uncropped = await sharp(buf).metadata();
  const expectedUncroppedWidth = Math.round(
    (Math.ceil(box.x + box.width) - Math.floor(box.x)) * dsf,
  );
  const expectedUncroppedHeight = Math.round(
    (Math.ceil(box.y + box.height) - Math.floor(box.y)) * dsf,
  );
  if (
    uncropped.width !== expectedUncroppedWidth ||
    uncropped.height !== expectedUncroppedHeight
  ) {
    throw new Error(
      `Uncropped screenshot for ${outputPath} is ${uncropped.width}×${uncropped.height}; expected enclosing integer rect × dsf ${expectedUncroppedWidth}×${expectedUncroppedHeight} (box ${box.x},${box.y} ${box.width}×${box.height}, dsf ${dsf})`,
    );
  }

  const left = Math.round((box.x - Math.floor(box.x)) * dsf);
  const top = Math.round((box.y - Math.floor(box.y)) * dsf);
  const width = Math.round(box.width * dsf);
  const height = Math.round(box.height * dsf);
  if (
    left < 0 ||
    top < 0 ||
    left + width > uncropped.width ||
    top + height > uncropped.height
  ) {
    throw new Error(
      `Crop ${left},${top} ${width}×${height} does not fit uncropped ${uncropped.width}×${uncropped.height} for ${outputPath}`,
    );
  }

  await sharp(buf)
    .extract({ left, top, width, height })
    .png(PNG_ENCODE)
    .toFile(outputPath);

  const cropped = await sharp(outputPath).metadata();
  if (cropped.width !== width || cropped.height !== height) {
    throw new Error(
      `PNG size mismatch for ${outputPath}: wrote ${cropped.width}×${cropped.height}, expected round(bbox × dsf) ${width}×${height}`,
    );
  }

  return { box, width, height };
}

function assertRoundBboxSize(label, box, pngWidth, pngHeight) {
  const expectedWidth = Math.round(box.width * currentOptions.dsf);
  const expectedHeight = Math.round(box.height * currentOptions.dsf);
  if (pngWidth !== expectedWidth || pngHeight !== expectedHeight) {
    throw new Error(
      `${label} PNG is ${pngWidth}×${pngHeight}; expected round(bbox × ${currentOptions.dsf}) ${expectedWidth}×${expectedHeight} from ${formatDimension(box.width)} × ${formatDimension(box.height)} CSS px`,
    );
  }
}

function assertPhoneFramePng(label, png) {
  const expectedWidth = Math.round(PHONE_FRAME.width * currentOptions.dsf);
  const expectedHeight = Math.round(PHONE_FRAME.height * currentOptions.dsf);
  if (png.width !== expectedWidth || png.height !== expectedHeight) {
    throw new Error(
      `Phone-frame ${label} is ${png.width}×${png.height}; expected ${expectedWidth}×${expectedHeight}`,
    );
  }
}

async function writeElementCapture(element, outputBase, metadata) {
  await mkdir(path.dirname(outputBase), { recursive: true });
  const png = await writeExactPng(element, `${outputBase}.png`);
  assertRoundBboxSize(outputBase, png.box, png.width, png.height);
  const rendered = await serializeElement(element, metadata);
  await writeFile(`${outputBase}.html`, rendered.html);
  return { ...rendered, png };
}

async function captureComponents(browser, outputRoot, fontCss) {
  const outputDirectory = path.join(outputRoot, "components");
  await rm(outputDirectory, { recursive: true, force: true });
  await mkdir(outputDirectory, { recursive: true });

  const loaded = await loadCapturePage(
    browser,
    "components.html",
    "components",
    fontCss,
  );
  try {
    const { page, targetCount } = loaded;
    const locator = page.locator("[data-component][data-variant]");
    const records = await locator.evaluateAll((elements) =>
      elements.map((element) => {
        const rectangle = element.getBoundingClientRect();
        return {
          component: element.dataset.component,
          variant: element.dataset.variant,
          width: rectangle.width,
          height: rectangle.height,
        };
      }),
    );
    if (records.length === 0)
      throw new Error("components.html rendered zero specimens");
    if (records.length !== targetCount) {
      throw new Error(
        `Specimen count changed from ${targetCount} to ${records.length}`,
      );
    }

    const seenSlugs = new Map();
    for (const record of records) {
      const slug = slugVariant(record.variant);
      if (!slug)
        throw new Error(
          `Empty slug for ${record.component}: ${record.variant}`,
        );
      const key = `${record.component}/${slug}`;
      if (seenSlugs.has(key)) {
        throw new Error(
          `Slug collision in ${record.component}: ${seenSlugs.get(key)} and ${record.variant} both become ${slug}`,
        );
      }
      seenSlugs.set(key, record.variant);
      record.slug = slug;
    }

    const presentComponents = new Set(
      records.map(({ component }) => component),
    );
    for (const component of EXPECTED_COMPONENTS) {
      if (!presentComponents.has(component)) {
        throw new Error(
          `Required component is absent from the export: ${component}`,
        );
      }
    }

    for (let index = 0; index < records.length; index += 1) {
      const record = records[index];
      const element = locator.nth(index);
      const outputBase = path.join(
        outputDirectory,
        record.component,
        record.slug,
      );
      const rendered = await writeElementCapture(element, outputBase, {
        component: record.component,
        variant: record.variant,
      });
      record.width = rendered.png.box.width;
      record.height = rendered.png.box.height;
      record.pngWidth = rendered.png.width;
      record.pngHeight = rendered.png.height;
      if (
        record.component === "button" &&
        record.slug === "variant-filled-size-lg" &&
        currentOptions.dsf === DEFAULT_DSF &&
        (record.pngWidth !== 505 || record.pngHeight !== 144)
      ) {
        throw new Error(
          `button/variant-filled-size-lg.png is ${record.pngWidth}×${record.pngHeight}; expected 505×144`,
        );
      }
    }

    records.sort(
      (left, right) =>
        left.component.localeCompare(right.component) ||
        left.variant.localeCompare(right.variant),
    );
    const inventory = [
      "# Generated component inventory",
      "",
      `Specimens: ${records.length}`,
      "",
      "| Component | Original variant | Slug | Size (CSS px) | PNG (px) | PNG | HTML |",
      "| --- | --- | --- | --- | --- | --- | --- |",
      ...records.map((record) => {
        const relativeBase = `components/${record.component}/${record.slug}`;
        return `| ${markdownCell(record.component)} | ${markdownCell(record.variant)} | ${record.slug} | ${formatDimension(record.width)} × ${formatDimension(record.height)} | ${record.pngWidth} × ${record.pngHeight} | [PNG](${relativeBase}.png) | [HTML](${relativeBase}.html) |`;
      }),
      "",
    ].join("\n");
    await writeFile(path.join(outputRoot, "inventory.md"), inventory);
    console.log(`Captured ${records.length} component specimens`);
  } finally {
    await loaded.context.close();
  }
}

async function extractTokens(browser, outputRoot, fontCss) {
  const loaded = await loadCapturePage(
    browser,
    "components.html",
    "components",
    fontCss,
  );
  try {
    const tokens = await loaded.page.evaluate(() => {
      const specimens = [
        ...document.querySelectorAll("[data-component][data-variant]"),
      ];
      const byComponent = (name) =>
        specimens.filter((element) => element.dataset.component === name);
      const px = (value, description) => {
        if (!/^\d+(?:\.\d+)?px$/.test(value)) {
          throw new Error(`${description} is not a pixel value: ${value}`);
        }
        return Number.parseFloat(value);
      };
      const rgbToHex = (value) => {
        const match = value.match(/^rgba?\((\d+),\s*(\d+),\s*(\d+)/);
        if (!match) throw new Error(`Colour is not an RGB value: ${value}`);
        return `#${match
          .slice(1, 4)
          .map((part) => Number(part).toString(16).padStart(2, "0"))
          .join("")}`;
      };

      let paletteRoot = specimens[0];
      while (paletteRoot && ![...paletteRoot.style].includes("--surf")) {
        paletteRoot = paletteRoot.parentElement;
      }
      if (!paletteRoot)
        throw new Error("Could not find the rendered palette wrapper");

      const colour = {};
      for (const property of paletteRoot.style) {
        if (!property.startsWith("--")) continue;
        colour[property] = getComputedStyle(specimens[0])
          .getPropertyValue(property)
          .trim()
          .toLowerCase();
      }
      for (const swatch of byComponent("token-colour")) {
        const name = swatch.dataset.variant;
        const rendered = rgbToHex(getComputedStyle(swatch).backgroundColor);
        if (colour[name] && colour[name] !== rendered) {
          throw new Error(
            `${name} is ${colour[name]} on the wrapper but ${rendered} in its token specimen`,
          );
        }
        colour[name] = rendered;
      }

      const type = byComponent("token-type")
        .map((element) => px(getComputedStyle(element).fontSize, "type token"))
        .sort((left, right) => left - right);
      const radius = byComponent("token-radius")
        .map((element) =>
          px(getComputedStyle(element).borderRadius, "radius token"),
        )
        .sort((left, right) => left - right);
      const space = byComponent("token-space")
        .map((element) => px(getComputedStyle(element).width, "space token"))
        .sort((left, right) => left - right);

      const buttonSize = {};
      for (const element of byComponent("token-button-size")) {
        const size = element.dataset.variant.match(/^size=(sm|md|lg)$/)?.[1];
        if (!size)
          throw new Error(`Unknown button token: ${element.dataset.variant}`);
        const style = getComputedStyle(element);
        buttonSize[size] = {
          h: px(style.height, `${size} button height`),
          r: px(style.borderRadius, `${size} button radius`),
          f: px(style.fontSize, `${size} button font`),
          p: px(style.paddingLeft, `${size} button padding`),
        };
      }

      const iconButtonSize = {};
      const iconSizes = new Set([type.find((size) => size === 28)]);
      for (const element of byComponent("icon-button")) {
        const size = element.dataset.variant.match(
          /(?:^| )size=(md|lg)(?: |$)/,
        )?.[1];
        if (!size)
          throw new Error(
            `Unknown icon button token: ${element.dataset.variant}`,
          );
        const glyph = element.querySelector(".msy");
        if (!glyph)
          throw new Error(`Icon button has no Material Symbols glyph`);
        const box = px(
          getComputedStyle(element).width,
          `${size} icon button box`,
        );
        const observed = {
          box,
          // The size token is a glyph at half the rendered touch target. The
          // current gallery paints the example ligatures at a shared 22px,
          // which is presentation rather than the md/lg geometry token.
          glyph: box / 2,
        };
        if (
          iconButtonSize[size] &&
          JSON.stringify(iconButtonSize[size]) !== JSON.stringify(observed)
        ) {
          throw new Error(`Inconsistent ${size} icon-button geometry`);
        }
        iconButtonSize[size] = observed;
      }

      return {
        colour,
        type,
        iconSize: [...iconSizes]
          .filter((value) => value !== undefined)
          .sort((left, right) => left - right),
        radius,
        space,
        buttonSize,
        iconButtonSize,
      };
    });

    assert.deepEqual(tokens.type, [12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 36]);
    assert.deepEqual(tokens.iconSize, [28]);
    assert.deepEqual(tokens.buttonSize, {
      sm: { h: 32, r: 16, f: 13, p: 12 },
      md: { h: 40, r: 20, f: 14, p: 24 },
      lg: { h: 48, r: 24, f: 15, p: 24 },
    });
    assert.deepEqual(tokens.iconButtonSize, {
      lg: { box: 48, glyph: 24 },
      md: { box: 40, glyph: 20 },
    });

    await writeFile(
      path.join(outputRoot, "tokens.json"),
      await format(JSON.stringify(tokens), { parser: "json" }),
    );
    console.log("Extracted rendered design tokens");
  } finally {
    await loaded.context.close();
  }
}

async function flowPhone(page, caption) {
  const handle = await page.evaluateHandle((expectedCaption) => {
    const captions = [...document.querySelectorAll("span")].filter(
      (element) => element.textContent.trim() === expectedCaption,
    );
    if (captions.length !== 1) {
      throw new Error(
        `Expected one flow caption ${expectedCaption}; found ${captions.length}`,
      );
    }
    const wrapper = captions[0].parentElement?.parentElement;
    const phones = [...wrapper.querySelectorAll("[style]")].filter(
      (element) =>
        element.style.width === "390px" && element.style.height === "844px",
    );
    if (phones.length !== 1) {
      throw new Error(
        `Expected one phone under ${expectedCaption}; found ${phones.length}`,
      );
    }
    return phones[0];
  }, caption);
  const element = handle.asElement();
  if (!element)
    throw new Error(`Flow caption ${caption} did not resolve to an element`);
  return element;
}

async function flowUnrolledRail(page) {
  const handle = await page.evaluateHandle(() => {
    const expected = "13 · Pending carousel, unrolled";
    const caption = [...document.querySelectorAll("span")].find(
      (element) => element.textContent.trim() === expected,
    );
    const wrapper = caption?.parentElement?.parentElement;
    const host = wrapper?.children[1]?.querySelector(".sc-host");
    const element = host?.firstElementChild;
    if (!element)
      throw new Error(`Could not find the rendered element under ${expected}`);
    return element;
  });
  const element = handle.asElement();
  if (!element) throw new Error("Unrolled rail did not resolve to an element");
  return element;
}

async function flowReviewSheet(page, caption) {
  const handle = await page.evaluateHandle((expectedCaption) => {
    const sectionCaption = [...document.querySelectorAll("span")].find(
      (element) =>
        element.textContent.trim() === "14 · Review sheets, unrolled",
    );
    const wrapper = sectionCaption?.parentElement?.parentElement;
    const columns = [...(wrapper?.children[1]?.children ?? [])];
    const column = columns.find(
      (element) =>
        element.firstElementChild?.textContent.trim() === expectedCaption,
    );
    const rendered = column?.querySelector(".sc-host")?.firstElementChild;
    if (!rendered)
      throw new Error(
        `Could not find unrolled review sheet ${expectedCaption}`,
      );
    return rendered;
  }, caption);
  const element = handle.asElement();
  if (!element)
    throw new Error(`Review sheet ${caption} did not resolve to an element`);
  return element;
}

async function onlyPhone(page) {
  const handles = await page.locator("[style]").elementHandles();
  const phones = [];
  for (const handle of handles) {
    if (
      await handle.evaluate(
        (element) =>
          element.style.width === "390px" && element.style.height === "844px",
      )
    ) {
      phones.push(handle);
    }
  }
  if (phones.length !== 1) {
    throw new Error(
      `Expected app.html to render one phone; found ${phones.length}`,
    );
  }
  return phones[0];
}

async function captureScreens(browser, outputRoot, fontCss) {
  const outputDirectory = path.join(outputRoot, "screens");
  await rm(outputDirectory, { recursive: true, force: true });
  await mkdir(outputDirectory, { recursive: true });

  const flow = await loadCapturePage(browser, "flow.html", "screens", fontCss);
  try {
    for (const { scene, caption } of FLOW_SCENES) {
      const element = await flowPhone(flow.page, caption);
      const rendered = await writeElementCapture(
        element,
        path.join(outputDirectory, scene),
        {
          screen: scene,
          source: "flow.html",
          label: caption,
        },
      );
      assertPhoneFramePng(scene, rendered.png);
    }

    const rail = await flowUnrolledRail(flow.page);
    await writeElementCapture(
      rail,
      path.join(outputDirectory, "rail-unrolled"),
      {
        screen: "rail-unrolled",
        source: "flow.html",
        label: "13 · Pending carousel, unrolled",
      },
    );

    for (const { name, caption } of REVIEW_SHEETS) {
      const element = await flowReviewSheet(flow.page, caption);
      await writeElementCapture(element, path.join(outputDirectory, name), {
        screen: name,
        source: "flow.html",
        label: caption,
      });
    }
  } finally {
    await flow.context.close();
  }

  for (const { scene, heading, tileText } of APP_SCENES) {
    const app = await loadCapturePage(browser, "app.html", "screens", fontCss);
    try {
      const tile = app.page.locator("button").filter({ hasText: tileText });
      const tileCount = await tile.count();
      if (tileCount !== 1) {
        throw new Error(
          `Expected one ${tileText} tile for app.html scene ${scene}; found ${tileCount}`,
        );
      }
      await tile.click();
      await app.page.getByText("close", { exact: true }).waitFor({
        state: "visible",
        timeout: READY_TIMEOUT_MS,
      });
      await app.page.waitForTimeout(300);
      const element = await onlyPhone(app.page);
      const renderedText = await element.innerText();
      if (!renderedText.split("\n").includes(heading)) {
        throw new Error(
          `app.html scene ${scene} did not render its ${heading} heading`,
        );
      }
      const rendered = await writeElementCapture(
        element,
        path.join(outputDirectory, scene),
        {
          screen: scene,
          source: "app.html",
          interaction: `open ${tileText} from the request rail`,
        },
      );
      assertPhoneFramePng(scene, rendered.png);
    } finally {
      await app.context.close();
    }
  }

  const sceneFiles = await readdir(outputDirectory);
  const pngCount = sceneFiles.filter((name) => name.endsWith(".png")).length;
  const htmlCount = sceneFiles.filter((name) => name.endsWith(".html")).length;
  const expectedCount =
    FLOW_SCENES.length + APP_SCENES.length + 1 + REVIEW_SHEETS.length;
  if (pngCount !== expectedCount || htmlCount !== expectedCount) {
    throw new Error(
      `Expected ${expectedCount} PNG and HTML screen references; found ${pngCount} and ${htmlCount}`,
    );
  }
  console.log(`Captured ${expectedCount} screen references`);
}

async function exportMetadata() {
  return Promise.all(
    EXPORT_NAMES.map(async (name) => {
      const bytes = await readFile(path.join(EXPORT_DIR, name));
      return { file: name, sha256: sha256(bytes), bytes: bytes.length };
    }),
  );
}

async function writeManifest(outputRoot, chromiumVersion, fontBytes) {
  const packageJson = JSON.parse(
    await readFile(path.join(TOOL_DIR, "package.json"), "utf8"),
  );
  const manifestPath = path.join(outputRoot, "manifest.json");
  const stable = {
    exports: await exportMetadata(),
    chromiumVersion,
    viewport: VIEWPORT,
    deviceScaleFactor: currentOptions.dsf,
    fontsInjected: [
      {
        family: "Roboto Mono",
        file: "tools/fonts/RobotoMono-Latin-400-700.woff2",
        sha256: sha256(fontBytes),
      },
    ],
    captureToolVersion: packageJson.version,
  };

  let capturedAt = new Date().toISOString();
  if (await pathExists(manifestPath)) {
    try {
      const existing = JSON.parse(await readFile(manifestPath, "utf8"));
      const existingCapturedAt = existing.capturedAt;
      delete existing.capturedAt;
      if (
        JSON.stringify(existing) === JSON.stringify(stable) &&
        existingCapturedAt
      ) {
        capturedAt = existingCapturedAt;
      }
    } catch {
      // A malformed old manifest must be replaced by the generated one.
    }
  }
  await writeFile(
    manifestPath,
    `${JSON.stringify({ ...stable, capturedAt }, null, 2)}\n`,
  );
}

async function listFiles(root, relative = "") {
  const absolute = path.join(root, relative);
  if (!(await pathExists(absolute))) return [];
  const entries = await readdir(absolute, { withFileTypes: true });
  const files = [];
  for (const entry of entries.sort((left, right) =>
    left.name.localeCompare(right.name),
  )) {
    const child = path.join(relative, entry.name);
    if (entry.isDirectory()) files.push(...(await listFiles(root, child)));
    else files.push(child);
  }
  return files;
}

async function compareGenerated(expectedRoot, actualRoot, only) {
  const targets = ["manifest.json"];
  if (!only || only === "components")
    targets.push("components", "inventory.md");
  if (!only || only === "screens") targets.push("screens");
  if (!only || only === "tokens") targets.push("tokens.json");

  const differences = [];
  for (const target of targets) {
    const expectedFiles = (await pathExists(path.join(expectedRoot, target)))
      ? (await stat(path.join(expectedRoot, target))).isDirectory()
        ? await listFiles(expectedRoot, target)
        : [target]
      : [];
    const actualFiles = (await pathExists(path.join(actualRoot, target)))
      ? (await stat(path.join(actualRoot, target))).isDirectory()
        ? await listFiles(actualRoot, target)
        : [target]
      : [];
    const allFiles = [...new Set([...expectedFiles, ...actualFiles])].sort();
    for (const relative of allFiles) {
      const expectedPath = path.join(expectedRoot, relative);
      const actualPath = path.join(actualRoot, relative);
      if (!(await pathExists(expectedPath))) {
        differences.push(`unexpected committed file: design/${relative}`);
        continue;
      }
      if (!(await pathExists(actualPath))) {
        differences.push(`missing committed file: design/${relative}`);
        continue;
      }
      let expected = await readFile(expectedPath);
      let actual = await readFile(actualPath);
      if (relative === "manifest.json") {
        expected = Buffer.from(normalizeManifest(expected.toString("utf8")));
        actual = Buffer.from(normalizeManifest(actual.toString("utf8")));
      }
      if (!expected.equals(actual))
        differences.push(`changed: design/${relative}`);
    }
  }

  if (differences.length > 0) {
    throw new Error(
      `Generated design references are stale:\n${differences.join("\n")}`,
    );
  }
  console.log(
    "Committed design references match a fresh capture (capturedAt ignored)",
  );
}

async function run() {
  const fontBytes = await readFile(FONT_FILE);
  const fontData = fontBytes.toString("base64");
  const fontCss = `
@font-face { font-family: "Roboto Mono"; font-style: normal; font-weight: 400; font-display: block; src: url(data:font/woff2;base64,${fontData}) format("woff2"); }
@font-face { font-family: "Roboto Mono"; font-style: normal; font-weight: 500; font-display: block; src: url(data:font/woff2;base64,${fontData}) format("woff2"); }
@font-face { font-family: "Roboto Mono"; font-style: normal; font-weight: 700; font-display: block; src: url(data:font/woff2;base64,${fontData}) format("woff2"); }
* { animation: none !important; transition: none !important; }
`;

  let temporaryRoot = null;
  let outputRoot = DESIGN_DIR;
  if (currentOptions.check) {
    temporaryRoot = await mkdtemp(
      path.join(os.tmpdir(), "sac-design-capture-"),
    );
    outputRoot = path.join(temporaryRoot, "design");
    await mkdir(outputRoot, { recursive: true });
  }

  let browser;
  try {
    browser = await chromium.launch({ headless: true });
  } catch (error) {
    throw new Error(
      `${error.message}\nInstall the pinned browser with: npm --prefix design/tools exec playwright install chromium`,
      { cause: error },
    );
  }

  try {
    if (!currentOptions.only || currentOptions.only === "components") {
      await captureComponents(browser, outputRoot, fontCss);
    }
    if (!currentOptions.only || currentOptions.only === "tokens") {
      await extractTokens(browser, outputRoot, fontCss);
    }
    if (!currentOptions.only || currentOptions.only === "screens") {
      await captureScreens(browser, outputRoot, fontCss);
    }
    await writeManifest(outputRoot, browser.version(), fontBytes);
  } finally {
    await browser.close();
  }

  if (currentOptions.check) {
    try {
      await compareGenerated(outputRoot, DESIGN_DIR, currentOptions.only);
    } finally {
      await rm(temporaryRoot, { recursive: true, force: true });
    }
  }
}

let currentOptions;
try {
  currentOptions = parseArgs(process.argv.slice(2));
  await run();
} catch (error) {
  console.error(`design capture failed: ${error.stack ?? error.message}`);
  process.exitCode = 1;
}
