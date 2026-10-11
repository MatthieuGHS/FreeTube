import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { errorMessage, request, tracksQuery, type Track } from "../api/client.ts";
import { thumbUrl, usePlay } from "./Player.tsx";

/** Case- and accent-insensitive, so "beyonce" finds "Beyoncé". */
const fold = (text: string) =>
  text
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLocaleLowerCase("fr");

export function Library() {
  const tracks = useQuery(tracksQuery);
  const [search, setSearch] = useState("");
  const play = usePlay();

  if (tracks.isPending) return <p>Chargement…</p>;
  if (tracks.isError) return <p role="alert">{errorMessage(tracks.error)}</p>;

  const needle = fold(search.trim());
  const shown = tracks.data.filter((track) => fold(`${track.title} ${track.artist}`).includes(needle));

  return (
    <>
      <h1>Bibliothèque</h1>
      <label htmlFor="search">Rechercher (titre ou artiste)</label>
      <input id="search" type="search" value={search} onChange={(event) => setSearch(event.target.value)} />
      {shown.length === 0 && <p>{tracks.data.length === 0 ? "Aucun morceau." : "Aucun résultat."}</p>}
      <ul className="rows">
        {shown.map((track, index) => (
          <TrackRow key={track.video_id} track={track} onPlay={() => play(shown, index)} />
        ))}
      </ul>
    </>
  );
}

function TrackRow({ track, onPlay }: { track: Track; onPlay: () => void }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const invalidate = () => queryClient.invalidateQueries({ queryKey: tracksQuery.queryKey });
  const rename = useMutation({
    mutationFn: (fields: { title: string; artist: string }) =>
      request<Track>("PATCH", `/tracks/${track.video_id}`, { rev: track.rev, body: fields }),
    onSuccess: () => {
      setEditing(false);
      return invalidate();
    },
  });
  const remove = useMutation({
    mutationFn: () => request<void>("DELETE", `/tracks/${track.video_id}`, { rev: track.rev }),
    onSuccess: invalidate,
  });

  if (editing) {
    return (
      <li className="row">
        <form
          className="edit"
          onSubmit={(event) => {
            event.preventDefault();
            const form = new FormData(event.currentTarget);
            rename.mutate({ title: String(form.get("title")), artist: String(form.get("artist")) });
          }}
        >
          <label htmlFor={`title-${track.video_id}`}>Titre</label>
          <input id={`title-${track.video_id}`} name="title" defaultValue={track.title} required maxLength={300} />
          <label htmlFor={`artist-${track.video_id}`}>Artiste</label>
          <input id={`artist-${track.video_id}`} name="artist" defaultValue={track.artist} required maxLength={300} />
          <button type="submit" disabled={rename.isPending}>Enregistrer</button>
          <button type="button" onClick={() => setEditing(false)}>Annuler</button>
        </form>
      </li>
    );
  }

  return (
    <li className="row">
      <img src={thumbUrl(track.video_id)} loading="lazy" alt="" width={64} height={36} />
      <div className="meta">
        <span className="title">{track.title}</span>
        <span className="artist">{track.artist}</span>
      </div>
      <div className="actions">
        <button type="button" onClick={onPlay} aria-label={`Lire ${track.title}`}>▶</button>
        <button type="button" onClick={() => setEditing(true)} aria-label={`Renommer ${track.title}`}>Renommer</button>
        <button
          type="button"
          disabled={remove.isPending}
          aria-label={`Supprimer ${track.title}`}
          onClick={() => window.confirm(`Supprimer « ${track.title} » ?`) && remove.mutate()}
        >
          Supprimer
        </button>
      </div>
    </li>
  );
}
