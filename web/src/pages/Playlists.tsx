import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { errorMessage, playlistsQuery, request, savePlaylist, tracksQuery, type Playlist } from "../api/client.ts";
import { moveRow, removeAt, visibleRows } from "../playlist.ts";
import { thumbUrl, usePlay } from "./Player.tsx";

const detailPath = (id: string) => `/playlists/${encodeURIComponent(id)}`;

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

  if (playlists.isPending) return <p>Chargement…</p>;
  if (playlists.isError) return <p role="alert">{errorMessage(playlists.error)}</p>;
  const playable = new Set(tracks.data?.map((t) => t.video_id));

  return (
    <>
      <h1>Playlists</h1>
      <form
        className="inline"
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate();
        }}
      >
        <label htmlFor="new-playlist">Nouvelle playlist</label>
        <input id="new-playlist" value={name} onChange={(e) => setName(e.target.value)} required maxLength={200} />
        <button type="submit" disabled={create.isPending}>Créer</button>
      </form>
      {playlists.data.length === 0 && <p>Aucune playlist.</p>}
      <ul className="rows">
        {playlists.data.map((p) => (
          <li key={p.id} className="row">
            <Link to={detailPath(p.id)} className="meta">
              <span className="title">{p.name}</span>
              <span className="artist">{visibleRows(p.items, playable).length} morceaux</span>
            </Link>
          </li>
        ))}
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
  const save = useSavePlaylist();
  const [renaming, setRenaming] = useState(false);
  const remove = useMutation({
    mutationFn: (playlist: Playlist) => request<void>("DELETE", detailPath(playlist.id), { rev: playlist.rev }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: playlistsQuery.queryKey });
      void navigate("/playlists", { replace: true });
    },
  });

  if (playlists.isPending || tracks.isPending) return <p>Chargement…</p>;
  if (playlists.isError) return <p role="alert">{errorMessage(playlists.error)}</p>;
  if (tracks.isError) return <p role="alert">{errorMessage(tracks.error)}</p>;
  const playlist = playlists.data.find((p) => p.id === id);
  if (!playlist) {
    return (
      <p>
        Playlist introuvable. <Link to="/playlists">Retour aux playlists</Link>
      </p>
    );
  }

  const byId = new Map(tracks.data.map((t) => [t.video_id, t]));
  const rows = visibleRows(playlist.items, new Set(byId.keys()));
  const queue = rows.map((row) => byId.get(row.videoId)!);
  const busy = save.isPending || remove.isPending;
  const edit = (items: string[] | null) => items && save.mutate({ playlist, change: { items } });

  return (
    <>
      {renaming ? (
        <form
          className="edit"
          onSubmit={(event) => {
            event.preventDefault();
            const name = String(new FormData(event.currentTarget).get("name"));
            save.mutate({ playlist, change: { name } }, { onSuccess: () => setRenaming(false) });
          }}
        >
          <label htmlFor="playlist-name">Nom de la playlist</label>
          <input id="playlist-name" name="name" defaultValue={playlist.name} required maxLength={200} />
          <button type="submit" disabled={busy}>Enregistrer</button>
          <button type="button" onClick={() => setRenaming(false)}>Annuler</button>
        </form>
      ) : (
        <h1>{playlist.name}</h1>
      )}
      <div className="actions">
        <button type="button" disabled={queue.length === 0} onClick={() => play(queue, 0)}>
          Tout lire
        </button>
        <button type="button" onClick={() => setRenaming(true)}>Renommer</button>
        <button
          type="button"
          disabled={busy}
          onClick={() => window.confirm(`Supprimer la playlist « ${playlist.name} » ?`) && remove.mutate(playlist)}
        >
          Supprimer la playlist
        </button>
      </div>
      {rows.length === 0 && <p>Aucun morceau lisible dans cette playlist.</p>}
      <ol className="rows">
        {rows.map((row, index) => {
          const track = queue[index]!;
          return (
            <li key={row.position} className="row">
              <img src={thumbUrl(track.video_id)} loading="lazy" alt="" width={64} height={36} />
              <div className="meta">
                <span className="title">{track.title}</span>
                <span className="artist">{track.artist}</span>
              </div>
              <div className="actions">
                <button type="button" aria-label={`Lire ${track.title}`} onClick={() => play(queue, index)}>▶</button>
                <button
                  type="button"
                  aria-label={`Monter ${track.title}`}
                  disabled={busy || index === 0}
                  onClick={() => edit(moveRow(playlist.items, rows, index, -1))}
                >
                  ↑
                </button>
                <button
                  type="button"
                  aria-label={`Descendre ${track.title}`}
                  disabled={busy || index === rows.length - 1}
                  onClick={() => edit(moveRow(playlist.items, rows, index, 1))}
                >
                  ↓
                </button>
                <button
                  type="button"
                  aria-label={`Retirer ${track.title} de la playlist`}
                  disabled={busy}
                  onClick={() => edit(removeAt(playlist.items, row.position))}
                >
                  Retirer
                </button>
              </div>
            </li>
          );
        })}
      </ol>
    </>
  );
}
