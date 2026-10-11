import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router";
import { errorMessage, LOGIN_PATH, request } from "../api/client.ts";
import { Brand } from "./Layout.tsx";

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
      <h1>
        <Brand />
      </h1>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          login.mutate();
        }}
      >
        <div className="field">
          <label htmlFor="password">Mot de passe</label>
          <input
            className="input"
            id="password"
            type="password"
            autoComplete="current-password"
            required
            maxLength={1024}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            aria-describedby={login.isError ? "login-error" : undefined}
          />
          {login.isError && (
            <p id="login-error" className="error" role="alert">
              {errorMessage(login.error)}
            </p>
          )}
        </div>
        <button type="submit" className="btn btn-primary" disabled={login.isPending}>
          Se connecter
        </button>
      </form>
    </main>
  );
}
