import { queryOptions } from "@tanstack/react-query";
import type { components } from "./schema.ts";

type Schemas = components["schemas"];
export type Track = Schemas["Track"];
export type Playlist = Schemas["Playlist"];
export type Device = Schemas["Device"];
export type DeviceCreated = Schemas["DeviceCreated"];
export type Me = Schemas["Me"];

export const LOGIN_PATH = "/auth/login";

export class ApiError extends Error {
  readonly status: number;
  readonly path: string;
  readonly detail: unknown;

  constructor(status: number, path: string, detail: unknown) {
    super(`HTTP ${status} on ${path}`);
    this.status = status;
    this.path = path;
    this.detail = detail;
  }

  get isRevConflict(): boolean {
    return this.status === 409 && this.detail === "rev_conflict";
  }
}

type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

/** `rev` goes to If-Match: the server refuses a web write without it (428) or with a stale one (409). */
export async function request<T>(
  method: Method,
  path: string,
  options: { body?: unknown; rev?: number } = {},
): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (options.rev !== undefined) headers["If-Match"] = String(options.rev);
  const response = await fetch(`/api${path}`, {
    method,
    headers,
    credentials: "same-origin",
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  if (!response.ok) {
    const json: unknown = await response.json().catch(() => null);
    const detail = json !== null && typeof json === "object" && "detail" in json ? json.detail : null;
    throw new ApiError(response.status, path, detail);
  }
  return (response.status === 204 ? undefined : await response.json()) as T;
}

/** A readable French message for any error a request can throw. */
export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return "Serveur injoignable, vérifie ta connexion";
  if (error.isRevConflict) return "Modifié ailleurs, données rechargées";
  switch (error.status) {
    case 401:
      return error.path === LOGIN_PATH ? "Mot de passe incorrect" : "Session expirée, reconnecte-toi";
    case 404:
      return "Introuvable : supprimé ailleurs ?";
    case 422:
      return "Données refusées par le serveur";
    case 429:
      return "Trop de tentatives, réessaie dans une minute";
    case 502:
      return "Stockage indisponible, réessaie plus tard";
    case 503:
      return "Serveur occupé, réessaie";
    default:
      return `Erreur du serveur (${error.status})`;
  }
}

export const meQuery = queryOptions({
  queryKey: ["me"],
  queryFn: () => request<Me>("GET", "/auth/me"),
});

export const tracksQuery = queryOptions({
  queryKey: ["tracks"],
  queryFn: () => request<Track[]>("GET", "/tracks"),
});

export const playlistsQuery = queryOptions({
  queryKey: ["playlists"],
  queryFn: () => request<Playlist[]>("GET", "/playlists"),
});

export const devicesQuery = queryOptions({
  queryKey: ["devices"],
  queryFn: () => request<Device[]>("GET", "/devices"),
});

/** PUT replaces the whole playlist: every field is sent, changed or not. */
export function savePlaylist(playlist: Playlist, change: { name?: string; items?: string[] }) {
  return request<Playlist>("PUT", `/playlists/${playlist.id}`, {
    rev: playlist.rev,
    body: {
      name: change.name ?? playlist.name,
      source_youtube_id: playlist.source_youtube_id,
      items: change.items ?? playlist.items,
    },
  });
}
