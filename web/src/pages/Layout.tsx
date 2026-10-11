import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useReducer } from "react";
import { NavLink, Outlet, useNavigate } from "react-router";
import { errorMessage, meQuery, request } from "../api/client.ts";
import { notify, useNotice } from "../notice.ts";
import { initialPlayer, playerReducer, type QueueItem } from "../player.ts";
import { PlayContext, Player } from "./Player.tsx";

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

  if (me.isPending) return <p className="page">Chargement…</p>;
  if (me.isError) return <p className="page" role="alert">{errorMessage(me.error)}</p>;

  return (
    <PlayContext value={play}>
      <header className="top">
        <nav aria-label="Principale">
          <NavLink to="/" end>Bibliothèque</NavLink>
          <NavLink to="/playlists">Playlists</NavLink>
          <NavLink to="/settings/devices">Appareils</NavLink>
        </nav>
        <button type="button" onClick={() => logout.mutate()} disabled={logout.isPending}>
          Déconnexion
        </button>
      </header>
      <Notice />
      <main className="page">
        <Outlet />
      </main>
      <Player state={player} dispatch={dispatch} />
    </PlayContext>
  );
}

function Notice() {
  const message = useNotice();
  if (message === null) return null;
  return (
    <div className="notice" role="status">
      <span>{message}</span>
      <button type="button" aria-label="Fermer le message" onClick={() => notify(null)}>
        ×
      </button>
    </div>
  );
}
