import { useSyncExternalStore } from "react";

/** One app-wide message line (write errors, 409 reloads): mutations fail outside any component. */
let message: string | null = null;
const listeners = new Set<() => void>();

export function notify(next: string | null): void {
  message = next;
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useNotice(): string | null {
  return useSyncExternalStore(subscribe, () => message);
}
