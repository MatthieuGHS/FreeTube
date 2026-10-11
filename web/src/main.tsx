import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, RouterProvider } from "react-router";
import { ApiError, errorMessage, LOGIN_PATH } from "./api/client.ts";
import { notify } from "./notice.ts";
import { Layout } from "./pages/Layout.tsx";
import { Library } from "./pages/Library.tsx";
import { Login } from "./pages/Login.tsx";
import { PlaylistDetail, Playlists } from "./pages/Playlists.tsx";
import "./styles.css";

const router = createBrowserRouter([
  { path: "/login", element: <Login /> },
  {
    element: <Layout />,
    children: [
      { index: true, element: <Library /> },
      { path: "playlists", element: <Playlists /> },
      { path: "playlists/:id", element: <PlaylistDetail /> },
    ],
  },
]);

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401 && error.path !== LOGIN_PATH;
}

function toLogin(): void {
  queryClient.clear();
  void router.navigate("/login", { replace: true });
}

const queryClient: QueryClient = new QueryClient({
  // Query errors are shown by the page that reads them; only an expired session is global.
  queryCache: new QueryCache({ onError: (error) => isUnauthorized(error) && toLogin() }),
  mutationCache: new MutationCache({
    onError: (error) => {
      if (isUnauthorized(error)) return toLogin();
      if (error instanceof ApiError && error.path === LOGIN_PATH) return; // the login form shows it
      if (error instanceof ApiError && (error.isRevConflict || error.status === 404)) {
        void queryClient.invalidateQueries();
      }
      notify(errorMessage(error));
    },
  }),
  defaultOptions: {
    queries: {
      // A 4xx will not fix itself: retry only network failures and server errors.
      retry: (count, error) => count < 2 && !(error instanceof ApiError && error.status < 500),
    },
  },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
