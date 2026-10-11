import { createContext, useContext, useEffect, useRef, useState, type ActionDispatch } from "react";
import { notify } from "../notice.ts";
import { current, type PlayerAction, type PlayerState, type QueueItem } from "../player.ts";

export const PlayContext = createContext<(queue: QueueItem[], index: number) => void>(() => {});
export const usePlay = () => useContext(PlayContext);

const streamUrl = (videoId: string) => `/api/tracks/${encodeURIComponent(videoId)}/stream`;
export const thumbUrl = (videoId: string) => `/api/tracks/${encodeURIComponent(videoId)}/thumb`;
const REPEAT_LABEL = { off: "Répéter : non", all: "Répéter : tout", one: "Répéter : un" } as const;

function clock(seconds: number): string {
  const s = Math.floor(seconds);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

export function Player({ state, dispatch }: { state: PlayerState; dispatch: ActionDispatch<[PlayerAction]> }) {
  const audio = useRef<HTMLAudioElement>(null);
  const [paused, setPaused] = useState(true);
  const [time, setTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const track = current(state);
  const videoId = track?.video_id;

  // (Re)start whenever the reducer bumps playId.
  useEffect(() => {
    const element = audio.current;
    if (!element || videoId === undefined) return;
    const src = streamUrl(videoId);
    if (element.getAttribute("src") === src) element.currentTime = 0;
    else element.src = src;
    element.play().catch((error: unknown) => {
      // NotAllowed: autoplay refused, the user presses play. Abort: a newer src replaced this one.
      if (error instanceof DOMException && ["NotAllowedError", "AbortError"].includes(error.name)) return;
      notify("Lecture impossible");
    });
  }, [state.playId, videoId]);

  useEffect(() => {
    if (!("mediaSession" in navigator) || !track) return;
    navigator.mediaSession.metadata = new MediaMetadata({
      title: track.title,
      artist: track.artist,
      artwork: [{ src: thumbUrl(track.video_id) }],
    });
  }, [track]);

  useEffect(() => {
    if (!("mediaSession" in navigator)) return;
    const handlers: [MediaSessionAction, MediaSessionActionHandler][] = [
      ["play", () => void audio.current?.play()],
      ["pause", () => audio.current?.pause()],
      ["previoustrack", () => dispatch({ type: "previous", elapsed: audio.current?.currentTime ?? 0 })],
      ["nexttrack", () => dispatch({ type: "next" })],
      [
        "seekto",
        (details) => {
          if (audio.current && details.seekTime !== undefined) audio.current.currentTime = details.seekTime;
        },
      ],
    ];
    for (const [action, handler] of handlers) navigator.mediaSession.setActionHandler(action, handler);
    return () => handlers.forEach(([action]) => navigator.mediaSession.setActionHandler(action, null));
  }, [dispatch]);

  const toggle = () => {
    const element = audio.current;
    if (!element || !track) return;
    if (element.paused) void element.play().catch(() => notify("Lecture impossible"));
    else element.pause();
  };

  return (
    <footer className="player" aria-label="Lecteur">
      <audio
        ref={audio}
        preload="none"
        onPlay={() => setPaused(false)}
        onPause={() => setPaused(true)}
        onTimeUpdate={(event) => setTime(event.currentTarget.currentTime)}
        onDurationChange={(event) => setDuration(Number.isFinite(event.currentTarget.duration) ? event.currentTarget.duration : 0)}
        onEnded={() => dispatch({ type: "ended" })}
        onError={() => notify("Lecture impossible : morceau introuvable ou serveur injoignable")}
      />
      <p className="now" aria-live="polite">
        {track ? `${track.title} · ${track.artist}` : "Rien en lecture"}
      </p>
      <div className="seek">
        <span>{clock(time)}</span>
        <input
          type="range"
          aria-label="Position dans le morceau"
          min={0}
          max={duration}
          step={1}
          value={Math.min(time, duration)}
          disabled={!track}
          onChange={(event) => {
            if (audio.current) audio.current.currentTime = Number(event.target.value);
          }}
        />
        <span>{clock(duration)}</span>
      </div>
      <div className="controls">
        <button
          type="button"
          aria-label="Aléatoire"
          aria-pressed={state.shuffle}
          onClick={() => dispatch({ type: "toggleShuffle", random: Math.random })}
        >
          Aléa
        </button>
        <button
          type="button"
          aria-label="Précédent"
          disabled={!track}
          onClick={() => dispatch({ type: "previous", elapsed: audio.current?.currentTime ?? 0 })}
        >
          «
        </button>
        <button type="button" aria-label={paused ? "Lecture" : "Pause"} disabled={!track} onClick={toggle}>
          {paused ? "▶" : "❚❚"}
        </button>
        <button type="button" aria-label="Suivant" disabled={!track} onClick={() => dispatch({ type: "next" })}>
          »
        </button>
        <button type="button" onClick={() => dispatch({ type: "cycleRepeat" })}>
          {REPEAT_LABEL[state.repeat]}
        </button>
      </div>
    </footer>
  );
}
