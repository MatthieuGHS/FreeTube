import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { errorMessage, playlistsQuery, request, tracksQuery, type Playlist, type Track } from "../api/client.ts";
import { ArrowLeftIcon, EllipsisIcon, ListPlusIcon, MusicIcon, PencilIcon, SearchIcon, Trash2Icon, XIcon } from "../icons.tsx";
import { notify } from "../notice.ts";
import { appendItem } from "../playlist.ts";
import { Empty } from "./Layout.tsx";
import { clock, Thumb, useCurrent, usePlay } from "./Player.tsx";
import { count, useSavePlaylist } from "./Playlists.tsx";

/** Case- and accent-insensitive, so "beyonce" finds "Beyoncé". */
const fold = (text: string) =>
  text
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLocaleLowerCase("fr");

export function Library() {
  const tracks = useQuery(tracksQuery);
  const playlists = useQuery(playlistsQuery);
  const [search, setSearch] = useState("");
  const play = usePlay();
  const currentId = useCurrent();

  if (tracks.isPending) return <p className="status">Chargement…</p>;
  if (tracks.isError) return <p className="error" role="alert">{errorMessage(tracks.error)}</p>;

  const needle = fold(search.trim());
  const shown = tracks.data.filter((track) => fold(`${track.title} ${track.artist}`).includes(needle));

  return (
    <>
      <div className="page-head">
        <h1>Bibliothèque</h1>
        <span className="muted">{count(tracks.data.length)}</span>
      </div>
      {playlists.isError && (
        <p className="error" role="alert">Playlists indisponibles : {errorMessage(playlists.error)}</p>
      )}
      <div className="search">
        <label htmlFor="search" className="sr-only">
          Rechercher (titre ou artiste)
        </label>
        <SearchIcon />
        <input
          id="search"
          className="input"
          type="search"
          placeholder="Rechercher un titre ou un artiste"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        {search !== "" && (
          <button type="button" className="icon-btn search-clear" aria-label="Effacer la recherche" onClick={() => setSearch("")}>
            <XIcon />
          </button>
        )}
      </div>
      {tracks.data.length === 0 && (
        <Empty icon={<MusicIcon />}>
          <p>Aucun morceau. Ajoute-en depuis l’app Android.</p>
        </Empty>
      )}
      {tracks.data.length > 0 && shown.length === 0 && (
        <Empty icon={<SearchIcon />}>
          <p>Aucun résultat pour « {search.trim()} ».</p>
          <button type="button" className="btn" onClick={() => setSearch("")}>
            Effacer la recherche
          </button>
        </Empty>
      )}
      <ul className="rows">
        {shown.map((track, index) => (
          <TrackRow
            key={track.video_id}
            track={track}
            current={track.video_id === currentId}
            playlists={playlists.data ?? []}
            onPlay={() => play(shown, index)}
          />
        ))}
      </ul>
    </>
  );
}

type RowProps = { track: Track; current: boolean; playlists: Playlist[]; onPlay: () => void };

function TrackRow({ track, current, playlists, onPlay }: RowProps) {
  const queryClient = useQueryClient();
  const save = useSavePlaylist();
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
  const addTo = (playlist: Playlist) => {
    const items = appendItem(playlist.items, track.video_id);
    if (items === null) notify(`Déjà dans « ${playlist.name} »`);
    else save.mutate({ playlist, change: { items } }, { onSuccess: () => notify(`Ajouté à « ${playlist.name} »`) });
  };

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
          <div className="field">
            <label htmlFor={`title-${track.video_id}`}>Titre</label>
            <input
              id={`title-${track.video_id}`}
              className="input"
              name="title"
              defaultValue={track.title}
              required
              maxLength={300}
              autoFocus
            />
          </div>
          <div className="field">
            <label htmlFor={`artist-${track.video_id}`}>Artiste</label>
            <input
              id={`artist-${track.video_id}`}
              className="input"
              name="artist"
              defaultValue={track.artist}
              required
              maxLength={300}
            />
          </div>
          <div className="form-actions">
            <button type="submit" className="btn btn-primary" disabled={rename.isPending}>
              Enregistrer
            </button>
            <button type="button" className="btn" onClick={() => setEditing(false)}>
              Annuler
            </button>
          </div>
        </form>
      </li>
    );
  }

  return (
    <li className="row" aria-current={current || undefined}>
      <button type="button" className="row-main" onClick={onPlay} aria-label={`Lire ${track.title}`}>
        <Thumb videoId={track.video_id} />
        <span className="meta">
          <span className="title" title={track.title}>{track.title}</span>
          <span className="artist">{track.artist}</span>
        </span>
      </button>
      <span className="duration">{clock(track.duration_s)}</span>
      <TrackMenu
        track={track}
        playlists={playlists}
        busy={save.isPending || remove.isPending}
        onAdd={addTo}
        onRename={() => setEditing(true)}
        onDelete={() => window.confirm(`Supprimer « ${track.title} » ?`) && remove.mutate()}
      />
    </li>
  );
}

const focusFirstItem = (root: HTMLElement | null) =>
  root?.querySelector<HTMLButtonElement>("[role=menuitem]:not(:disabled)")?.focus();

type MenuProps = {
  track: Track;
  playlists: Playlist[];
  busy: boolean;
  onAdd: (playlist: Playlist) => void;
  onRename: () => void;
  onDelete: () => void;
};

/** The row's "more" menu: a native popover (Escape and outside click close it), arrow keys move between items. */
function TrackMenu({ track, playlists, busy, onAdd, onRename, onDelete }: MenuProps) {
  const id = useId();
  const menu = useRef<HTMLDivElement>(null);
  const [view, setView] = useState<"actions" | "playlists">("actions");
  const run = (action: () => void) => () => {
    menu.current?.hidePopover();
    action();
  };

  useEffect(() => {
    if (menu.current?.matches(":popover-open")) focusFirstItem(menu.current);
  }, [view]);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const step = { ArrowDown: 1, ArrowUp: -1 }[event.key];
    if (step === undefined) return;
    event.preventDefault();
    const items = [...event.currentTarget.querySelectorAll<HTMLButtonElement>("[role=menuitem]:not(:disabled)")];
    const at = items.indexOf(document.activeElement as HTMLButtonElement);
    items[(at + step + items.length) % items.length]?.focus();
  };

  return (
    <>
      <button
        type="button"
        className="icon-btn"
        popoverTarget={id}
        aria-haspopup="menu"
        aria-label={`Actions pour ${track.title}`}
      >
        <EllipsisIcon />
      </button>
      <div
        id={id}
        ref={menu}
        popover="auto"
        role="menu"
        aria-label={`Actions pour ${track.title}`}
        className="menu"
        onKeyDown={onKeyDown}
        onToggle={(event) => (event.newState === "open" ? focusFirstItem(menu.current) : setView("actions"))}
      >
        {view === "actions" ? (
          <>
            {playlists.length > 0 && (
              <button type="button" role="menuitem" className="menu-item" onClick={() => setView("playlists")}>
                <ListPlusIcon />
                <span>Ajouter à une playlist</span>
              </button>
            )}
            <button type="button" role="menuitem" className="menu-item" onClick={run(onRename)}>
              <PencilIcon />
              <span>Renommer</span>
            </button>
            <div className="menu-sep" role="separator" />
            <button type="button" role="menuitem" className="menu-item danger" disabled={busy} onClick={run(onDelete)}>
              <Trash2Icon />
              <span>Supprimer</span>
            </button>
          </>
        ) : (
          <>
            <button
              type="button"
              role="menuitem"
              className="menu-item"
              aria-label="Retour aux actions"
              onClick={() => setView("actions")}
            >
              <ArrowLeftIcon />
              <span>Ajouter à une playlist</span>
            </button>
            <div className="menu-sep" role="separator" />
            {playlists.map((playlist) => (
              <button
                key={playlist.id}
                type="button"
                role="menuitem"
                className="menu-item"
                disabled={busy}
                onClick={run(() => onAdd(playlist))}
              >
                <span>{playlist.name}</span>
              </button>
            ))}
          </>
        )}
      </div>
    </>
  );
}
