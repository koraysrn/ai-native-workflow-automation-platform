import type { DocumentVersionDto } from "../lib/api";
import type { DocumentScanStatusEventDto } from "../lib/documentScanStatus";

type Listener = (event: Event) => void;

/** Minimal EventSource double that lets tests open, fail and emit named events synchronously. */
export class FakeEventSource {
  static instances: FakeEventSource[] = [];

  readonly url: string;
  readonly options: EventSourceInit | undefined;
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  closed = false;
  private readonly listeners = new Map<string, Set<Listener>>();

  constructor(url: string | URL, options?: EventSourceInit) {
    this.url = String(url);
    this.options = options;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: EventListenerOrEventListenerObject | null): void {
    if (!listener) {
      return;
    }
    const listeners = this.listeners.get(type) ?? new Set<Listener>();
    listeners.add(
      typeof listener === "function" ? (listener as Listener) : (event) => listener.handleEvent(event),
    );
    this.listeners.set(type, listeners);
  }

  removeEventListener(type: string, listener: EventListenerOrEventListenerObject | null): void {
    if (!listener) {
      return;
    }
    this.listeners.get(type)?.delete(listener as Listener);
  }

  close(): void {
    this.closed = true;
  }

  open(): void {
    this.onopen?.(new Event("open"));
  }

  fail(): void {
    this.onerror?.(new Event("error"));
  }

  emit(data: unknown): void {
    this.emitNamed("scan-status", data);
  }

  emitNamed(type: string, data: unknown): void {
    const event = {
      data: typeof data === "string" ? data : JSON.stringify(data),
    } as MessageEvent;
    this.listeners.get(type)?.forEach((listener) => listener(event));
  }

  static reset(): void {
    FakeEventSource.instances = [];
  }
}

function restoreEventSource(
  target: Record<string, unknown>,
  globalTarget: Record<string, unknown>,
  originalWindow: unknown,
  originalGlobal: unknown,
): void {
  if (originalWindow === undefined) {
    delete target.EventSource;
  } else {
    target.EventSource = originalWindow;
  }
  if (originalGlobal === undefined) {
    delete globalTarget.EventSource;
  } else {
    globalTarget.EventSource = originalGlobal;
  }
  FakeEventSource.reset();
}

/** Installs the fake on `window`/`globalThis` and returns a restore function. */
export function installFakeEventSource(): () => void {
  const target = window as unknown as Record<string, unknown>;
  const globalTarget = globalThis as unknown as Record<string, unknown>;
  const originalWindow = target.EventSource;
  const originalGlobal = globalTarget.EventSource;
  target.EventSource = FakeEventSource;
  globalTarget.EventSource = FakeEventSource;
  FakeEventSource.reset();
  return () => restoreEventSource(target, globalTarget, originalWindow, originalGlobal);
}

/** Removes EventSource entirely to exercise the polling fallback. */
export function removeEventSource(): () => void {
  const target = window as unknown as Record<string, unknown>;
  const globalTarget = globalThis as unknown as Record<string, unknown>;
  const originalWindow = target.EventSource;
  const originalGlobal = globalTarget.EventSource;
  delete target.EventSource;
  delete globalTarget.EventSource;
  FakeEventSource.reset();
  return () => restoreEventSource(target, globalTarget, originalWindow, originalGlobal);
}

export function documentDto(
  overrides: Partial<DocumentVersionDto> = {},
): DocumentVersionDto {
  return {
    id: "version-1",
    fileId: "file-1",
    version: 1,
    size: 2048,
    format: "pdf",
    status: "PENDING",
    createdAt: "2026-01-01T00:00:00Z",
    name: "report.pdf",
    scanStatus: "QUARANTINED",
    scanError: null,
    downloadable: false,
    ...overrides,
  };
}

export function scanEvent(
  overrides: Partial<DocumentScanStatusEventDto> = {},
): DocumentScanStatusEventDto {
  return {
    tenantId: "tenant-a",
    fileId: "file-1",
    name: "report.pdf",
    status: "SCANNING",
    detail: "",
    occurredAt: "2026-01-01T00:00:01Z",
    ...overrides,
  };
}
