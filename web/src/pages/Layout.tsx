import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useReducer, type ReactNode } from "react";
import { NavLink, Outlet, useNavigate } from "react-router";
import { errorMessage, meQuery, request } from "../api/client.ts";
import { LogOutIcon, PlayIcon, XIcon } from "../icons.tsx";
import { notify, useNotice } from "../notice.ts";
import { current, initialPlayer, playerReducer, type QueueItem } from "../player.ts";
import { CurrentContext, PlayContext, Player } from "./Player.tsx";

/** Auth gate, navigation and the player. The player sits outside <Outlet/>: navigating never stops it. */
export function Layout() {
  const me = useQuery(meQuery);
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [player, dispatch] = useReducer(playerReducer, initialPlayer);
  const play = useCallback(
    (queue: QueueItem[], index: number) => dispatch({ type: "play", queue, index, random: Math.random }),
    [],
  );
  const logout = useMutation({
    mutationFn: () => request<void>("POST", "/auth/logout"),
    onSuccess: () => {
      queryClient.clear();
      void navigate("/login", { replace: true });
    },
  });

  if (me.isPending) return <p className="page status">Chargement…</p>;
  if (me.isError) return <p className="page error" role="alert">{errorMessage(me.error)}</p>;

  return (
    <PlayContext value={play}>
      <CurrentContext value={current(player)?.video_id}>
        <header className="top">
          <div className="top-inner">
            <Brand />
            <nav className="nav" aria-label="Principale">
              <NavLink to="/" end>Bibliothèque</NavLink>
              <NavLink to="/playlists">Playlists</NavLink>
              <NavLink to="/settings/devices">Appareils</NavLink>
            </nav>
            <button
              type="button"
              className="icon-btn logout"
              aria-label="Déconnexion"
              title="Déconnexion"
              onClick={() => logout.mutate()}
              disabled={logout.isPending}
            >
              <LogOutIcon />
            </button>
          </div>
        </header>
        <Notice />
        <main className="page">
          <Outlet />
        </main>
        <Player state={player} dispatch={dispatch} />
      </CurrentContext>
    </PlayContext>
  );
}

export function Brand() {
  return (
    <span className="brand">
      <span className="brand-mark">
        <PlayIcon />
      </span>
      FreeTube
    </span>
  );
}

/** One muted icon, one sentence, at most one action. */
export function Empty({ icon, children }: { icon: ReactNode; children: ReactNode }) {
  return <div className="empty">{icon}{children}</div>;
}

function Notice() {
  const message = useNotice();
  if (message === null) return null;
  return (
    <div className="notice" role="status">
      <span>{message}</span>
      <button type="button" className="icon-btn" aria-label="Fermer le message" onClick={() => notify(null)}>
        <XIcon />
      </button>
    </div>
  );
}
