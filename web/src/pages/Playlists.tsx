import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { errorMessage, playlistsQuery, request, savePlaylist, tracksQuery, type Playlist } from "../api/client.ts";
import {
  ArrowDownIcon,
  ArrowLeftIcon,
  ArrowUpIcon,
  ChevronRightIcon,
  ListMusicIcon,
  PencilIcon,
  PlayIcon,
  PlusIcon,
  Trash2Icon,
  XIcon,
} from "../icons.tsx";
import { moveRow, removeAt, visibleRows } from "../playlist.ts";
import { Empty } from "./Layout.tsx";
import { clock, Thumb, useCurrent, usePlay } from "./Player.tsx";

const detailPath = (id: string) => `/playlists/${encodeURIComponent(id)}`;
export const count = (n: number) => `${n} morceau${n > 1 ? "x" : ""}`;

/** PUT, then put the returned playlist (new rev) in the cache at once: a quick second edit must not send the old rev. */
export function useSavePlaylist() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ playlist, change }: { playlist: Playlist; change: { name?: string; items?: string[] } }) =>
      savePlaylist(playlist, change),
    onSuccess: (saved) =>
      queryClient.setQueryData(playlistsQuery.queryKey, (list) => list?.map((p) => (p.id === saved.id ? saved : p))),
  });
}

export function Playlists() {
  const playlists = useQuery(playlistsQuery);
  const tracks = useQuery(tracksQuery);
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const create = useMutation({
    mutationFn: () =>
      request<Playlist>("POST", "/playlists", { body: { id: crypto.randomUUID(), name, items: [] } }),
    onSuccess: () => {
      setName("");
      return queryClient.invalidateQueries({ queryKey: playlistsQuery.queryKey });
    },
  });

  if (playlists.isPending) return <p className="status">Chargement…</p>;
  if (playlists.isError) return <p className="error" role="alert">{errorMessage(playlists.error)}</p>;
  const playable = new Set(tracks.data?.map((t) => t.video_id));

  return (
    <>
      <div className="page-head">
        <h1>Playlists</h1>
      </div>
      <form
        className="inline-form"
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate();
        }}
      >
        <div className="field">
          <label htmlFor="new-playlist">Nouvelle playlist</label>
          <input
            id="new-playlist"
            className="input"
            placeholder="Nom de la playlist"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            maxLength={200}
          />
        </div>
        <button type="submit" className="btn btn-primary" disabled={create.isPending}>
          <PlusIcon />
          Créer
        </button>
      </form>
      {playlists.data.length === 0 && (
        <Empty icon={<ListMusicIcon />}>
          <p>Aucune playlist. Crée la première ci-dessus.</p>
        </Empty>
      )}
      <ul className="rows">
        {playlists.data.map((p) => {
          const rows = visibleRows(p.items, playable);
          return (
            <li key={p.id} className="row">
              <Link to={detailPath(p.id)} className="row-main">
                {rows.length > 0 ? (
                  <Thumb videoId={rows[0]!.videoId} />
                ) : (
                  <span className="thumb">
                    <ListMusicIcon />
                  </span>
                )}
                <span className="meta">
                  <span className="title" title={p.name}>{p.name}</span>
                  <span className="artist">{count(rows.length)}</span>
                </span>
                <span className="chevron">
                  <ChevronRightIcon />
                </span>
              </Link>
            </li>
          );
        })}
      </ul>
    </>
  );
}

export function PlaylistDetail() {
  const { id } = useParams();
  const playlists = useQuery(playlistsQuery);
  const tracks = useQuery(tracksQuery);
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const play = usePlay();
  const currentId = useCurrent();
  const save = useSavePlaylist();
  const [renaming, setRenaming] = useState(false);
  const remove = useMutation({
    mutationFn: (playlist: Playlist) => request<void>("DELETE", detailPath(playlist.id), { rev: playlist.rev }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: playlistsQuery.queryKey });
      void navigate("/playlists", { replace: true });
    },
  });

  if (playlists.isPending || tracks.isPending) return <p className="status">Chargement…</p>;
  if (playlists.isError) return <p className="error" role="alert">{errorMessage(playlists.error)}</p>;
  if (tracks.isError) return <p className="error" role="alert">{errorMessage(tracks.error)}</p>;
  const playlist = playlists.data.find((p) => p.id === id);
  if (!playlist) {
    return (
      <Empty icon={<ListMusicIcon />}>
        <p>Playlist introuvable.</p>
        <Link to="/playlists" className="btn">
          Retour aux playlists
        </Link>
      </Empty>
    );
  }

  const byId = new Map(tracks.data.map((t) => [t.video_id, t]));
  const rows = visibleRows(playlist.items, new Set(byId.keys()));
  const queue = rows.map((row) => byId.get(row.videoId)!);
  const busy = save.isPending || remove.isPending;
  const edit = (items: string[] | null) => items && save.mutate({ playlist, change: { items } });

  return (
    <>
      <Link to="/playlists" className="back">
        <ArrowLeftIcon />
        Playlists
      </Link>
      <div className="playlist-head">
        {renaming ? (
          <form
            className="edit"
            onSubmit={(event) => {
              event.preventDefault();
              const name = String(new FormData(event.currentTarget).get("name"));
              save.mutate({ playlist, change: { name } }, { onSuccess: () => setRenaming(false) });
            }}
          >
            <div className="field">
              <label htmlFor="playlist-name">Nom de la playlist</label>
              <input
                id="playlist-name"
                className="input"
                name="name"
                defaultValue={playlist.name}
                required
                maxLength={200}
                autoFocus
              />
            </div>
            <div className="form-actions">
              <button type="submit" className="btn btn-primary" disabled={busy}>
                Enregistrer
              </button>
              <button type="button" className="btn" onClick={() => setRenaming(false)}>
                Annuler
              </button>
            </div>
          </form>
        ) : (
          <div>
            <h1>{playlist.name}</h1>
            <p className="muted count">{count(rows.length)}</p>
          </div>
        )}
        <div className="toolbar">
          <button type="button" className="btn btn-primary" disabled={queue.length === 0} onClick={() => play(queue, 0)}>
            <span className="filled">
              <PlayIcon />
            </span>
            Lire
          </button>
          <button
            type="button"
            className="icon-btn"
            aria-label="Renommer la playlist"
            title="Renommer la playlist"
            onClick={() => setRenaming(true)}
          >
            <PencilIcon />
          </button>
          <button
            type="button"
            className="icon-btn"
            aria-label="Supprimer la playlist"
            title="Supprimer la playlist"
            disabled={busy}
            onClick={() => window.confirm(`Supprimer la playlist « ${playlist.name} » ?`) && remove.mutate(playlist)}
          >
            <Trash2Icon />
          </button>
        </div>
      </div>
      {rows.length === 0 && (
        <Empty icon={<ListMusicIcon />}>
          <p>Aucun morceau lisible dans cette playlist.</p>
          <Link to="/" className="btn">
            Ajouter depuis la bibliothèque
          </Link>
        </Empty>
      )}
      <ol className="rows">
        {rows.map((row, index) => {
          const track = queue[index]!;
          return (
            <li key={row.position} className="row detail-row" aria-current={track.video_id === currentId || undefined}>
              <button type="button" className="row-main" aria-label={`Lire ${track.title}`} onClick={() => play(queue, index)}>
                <Thumb videoId={track.video_id} />
                <span className="meta">
                  <span className="title" title={track.title}>{track.title}</span>
                  <span className="artist">{track.artist}</span>
                </span>
              </button>
              <span className="duration">{clock(track.duration_s)}</span>
              <div className="row-actions">
                <button
                  type="button"
                  className="icon-btn"
                  aria-label={`Monter ${track.title}`}
                  title="Monter"
                  disabled={busy || index === 0}
                  onClick={() => edit(moveRow(playlist.items, rows, index, -1))}
                >
                  <ArrowUpIcon />
                </button>
                <button
                  type="button"
                  className="icon-btn"
                  aria-label={`Descendre ${track.title}`}
                  title="Descendre"
                  disabled={busy || index === rows.length - 1}
                  onClick={() => edit(moveRow(playlist.items, rows, index, 1))}
                >
                  <ArrowDownIcon />
                </button>
                <button
                  type="button"
                  className="icon-btn"
                  aria-label={`Retirer ${track.title} de la playlist`}
                  title="Retirer de la playlist"
                  disabled={busy}
                  onClick={() => edit(removeAt(playlist.items, row.position))}
                >
                  <XIcon />
                </button>
              </div>
            </li>
          );
        })}
      </ol>
    </>
  );
}
