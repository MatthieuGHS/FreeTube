import { createContext, useContext, useEffect, useRef, useState, type ActionDispatch } from "react";
import {
  MusicIcon,
  PauseIcon,
  PlayIcon,
  Repeat1Icon,
  RepeatIcon,
  ShuffleIcon,
  SkipBackIcon,
  SkipForwardIcon,
} from "../icons.tsx";
import { notify } from "../notice.ts";
import { current, type PlayerAction, type PlayerState, type QueueItem } from "../player.ts";

export const PlayContext = createContext<(queue: QueueItem[], index: number) => void>(() => {});
export const usePlay = () => useContext(PlayContext);
/** video_id of the track in the player, to mark its row. */
export const CurrentContext = createContext<string | undefined>(undefined);
export const useCurrent = () => useContext(CurrentContext);

const streamUrl = (videoId: string) => `/api/tracks/${encodeURIComponent(videoId)}/stream`;
export const thumbUrl = (videoId: string) => `/api/tracks/${encodeURIComponent(videoId)}/thumb`;
const REPEAT_LABEL = { off: "Répéter : non", all: "Répéter : tout", one: "Répéter : un" } as const;

export function clock(seconds: number): string {
  const s = Math.floor(seconds);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

/** 16:9 artwork over a placeholder: a missing thumbnail leaves the music icon visible. */
export function Thumb({ videoId }: { videoId: string | undefined }) {
  return (
    <span className="thumb">
      <MusicIcon />
      {videoId !== undefined && (
        <img
          key={videoId}
          src={thumbUrl(videoId)}
          loading="lazy"
          alt=""
          width={64}
          height={36}
          onError={(event) => (event.currentTarget.hidden = true)}
        />
      )}
    </span>
  );
}

export function Player({ state, dispatch }: { state: PlayerState; dispatch: ActionDispatch<[PlayerAction]> }) {
  const audio = useRef<HTMLAudioElement>(null);
  const [paused, setPaused] = useState(true);
  const [time, setTime] = useState(0);
  const [duration, setDuration] = useState(0);
  // One reload per failure streak: true from an error until audio plays again.
  const recovering = useRef(false);
  const track = current(state);
  const videoId = track?.video_id;

  // (Re)start whenever the reducer bumps playId.
  useEffect(() => {
    const element = audio.current;
    if (!element || videoId === undefined) return;
    recovering.current = false;
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

  /**
   * The browser keeps the pre-signed S3 URL it was redirected to; it expires after 1 h, so resuming a long
   * pause (or seeking) gets a 403. Reloading /stream fetches a fresh 302, then playback resumes where it was.
   */
  const onError = () => {
    const element = audio.current;
    if (!element || videoId === undefined || recovering.current) {
      notify("Lecture impossible : morceau introuvable ou serveur injoignable");
      return;
    }
    recovering.current = true;
    const at = element.currentTime;
    element.src = streamUrl(videoId);
    element.addEventListener(
      "loadedmetadata",
      () => {
        element.currentTime = at;
        element.play().catch(() => notify("Lecture impossible"));
      },
      { once: true },
    );
  };

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
        onPlaying={() => (recovering.current = false)}
        onPause={() => setPaused(true)}
        onTimeUpdate={(event) => setTime(event.currentTarget.currentTime)}
        onDurationChange={(event) => setDuration(Number.isFinite(event.currentTarget.duration) ? event.currentTarget.duration : 0)}
        onEnded={() => dispatch({ type: "ended" })}
        onError={onError}
      />
      <progress className="line" aria-hidden="true" value={time} max={duration || 1} />
      <div className="np">
        <Thumb videoId={videoId} />
        <div className="now" aria-live="polite">
          {track ? (
            <>
              <span className="title" title={track.title}>{track.title}</span>
              <span className="artist">{track.artist}</span>
            </>
          ) : (
            <span className="muted">Rien en lecture</span>
          )}
        </div>
      </div>
      <div className="transport">
        <div className="controls">
          <button
            type="button"
            className="icon-btn"
            aria-label="Aléatoire"
            title="Aléatoire"
            aria-pressed={state.shuffle}
            onClick={() => dispatch({ type: "toggleShuffle", random: Math.random })}
          >
            <ShuffleIcon />
          </button>
          <button
            type="button"
            className="icon-btn"
            aria-label="Précédent"
            title="Précédent"
            disabled={!track}
            onClick={() => dispatch({ type: "previous", elapsed: audio.current?.currentTime ?? 0 })}
          >
            <SkipBackIcon />
          </button>
          <button type="button" className="play" aria-label={paused ? "Lecture" : "Pause"} disabled={!track} onClick={toggle}>
            {paused ? (
              <span className="filled">
                <PlayIcon />
              </span>
            ) : (
              <PauseIcon />
            )}
          </button>
          <button
            type="button"
            className="icon-btn"
            aria-label="Suivant"
            title="Suivant"
            disabled={!track}
            onClick={() => dispatch({ type: "next" })}
          >
            <SkipForwardIcon />
          </button>
          <button
            type="button"
            className="icon-btn"
            aria-label={REPEAT_LABEL[state.repeat]}
            title={REPEAT_LABEL[state.repeat]}
            aria-pressed={state.repeat !== "off"}
            onClick={() => dispatch({ type: "cycleRepeat" })}
          >
            {state.repeat === "one" ? <Repeat1Icon /> : <RepeatIcon />}
          </button>
        </div>
        <div className="seek">
          <span className="time">{clock(time)}</span>
          <div className="track">
            <progress aria-hidden="true" value={time} max={duration || 1} />
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
          </div>
          <span className="time">{clock(duration)}</span>
        </div>
      </div>
    </footer>
  );
}
