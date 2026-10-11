import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router";
import { errorMessage, LOGIN_PATH, request } from "../api/client.ts";

export function Login() {
  const [password, setPassword] = useState("");
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const login = useMutation({
    mutationFn: () => request<void>("POST", LOGIN_PATH, { body: { password } }),
    onSuccess: () => {
      queryClient.clear();
      void navigate("/", { replace: true });
    },
  });

  return (
    <main className="login">
      <h1>FreeTube</h1>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          login.mutate();
        }}
      >
        <label htmlFor="password">Mot de passe</label>
        <input
          id="password"
          type="password"
          autoComplete="current-password"
          required
          maxLength={1024}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
        <button type="submit" disabled={login.isPending}>
          Se connecter
        </button>
        {login.isError && <p role="alert">{errorMessage(login.error)}</p>}
      </form>
    </main>
  );
}
