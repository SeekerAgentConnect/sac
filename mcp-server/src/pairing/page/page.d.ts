export function trustedOriginFromDocument(document: {
  getElementById(id: string): { textContent: string | null } | null;
}): string;

export function bootPairingPage(env: {
  readonly document: {
    getElementById(id: string): PageElement | null;
  };
  readonly location: { hash: string; search: string };
  readonly trustedOrigin: string;
  readonly loadQr?: () => Promise<{
    renderSVG: (data: string, options?: object) => string;
  }>;
  readonly navigator?: {
    clipboard?: { writeText(text: string): Promise<void> };
  };
  readonly DOMParser?: new () => {
    parseFromString(
      text: string,
      type: string,
    ): {
      documentElement: {
        tagName: string;
        setAttribute(name: string, value: string): void;
      };
    };
  };
  readonly onReady?: (uri: string) => void;
}): Promise<{
  readonly state: "empty" | "invalid" | "ready" | "expired";
  readonly pairingUri?: string;
  readonly warning?: string;
}>;

interface PageElement {
  textContent: string;
  hidden: boolean;
  value?: string;
  readOnly?: boolean;
  children?: unknown[];
  setAttribute(name: string, value: string): void;
  getAttribute(name: string): string | null;
  replaceChildren(): void;
  appendChild(child: unknown): unknown;
  addEventListener(type: string, listener: () => void): void;
  focus?: () => void;
  select?: () => void;
}
