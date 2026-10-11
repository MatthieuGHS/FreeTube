import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { devicesQuery, errorMessage, request, type Device, type DeviceCreated } from "../api/client.ts";
import { notify } from "../notice.ts";

const date = (iso: string | null) => (iso === null ? "jamais" : new Date(iso).toLocaleString("fr-FR"));

export function Devices() {
  const devices = useQuery(devicesQuery);
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  // The token lives here only: never in the query cache, storage or the URL. Leaving the page drops it.
  const [created, setCreated] = useState<{ name: string; token: string } | null>(null);
  const invalidate = () => queryClient.invalidateQueries({ queryKey: devicesQuery.queryKey });
  const create = useMutation({
    mutationFn: () => request<DeviceCreated>("POST", "/devices", { body: { name } }),
    gcTime: 0,
  });
  const revoke = useMutation({
    mutationFn: (device: Device) => request<void>("DELETE", `/devices/${device.id}`),
    onSuccess: invalidate,
  });

  const copy = (token: string) =>
    navigator.clipboard.writeText(token).then(
      () => notify("Token copié"),
      () => notify("Copie impossible : sélectionne le token et copie-le à la main"),
    );

  return (
    <>
      <h1>Appareils</h1>
      <form
        className="inline"
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate(undefined, {
            onSuccess: (device) => {
              setCreated({ name: device.name, token: device.token });
              setName("");
              create.reset(); // drops the response, token included, from the mutation cache
              void invalidate();
            },
          });
        }}
      >
        <label htmlFor="device-name">Nom du nouvel appareil</label>
        <input id="device-name" value={name} onChange={(e) => setName(e.target.value)} required maxLength={200} />
        <button type="submit" disabled={create.isPending}>Créer un token</button>
      </form>

      {created && (
        <section className="token" aria-labelledby="token-title">
          <h2 id="token-title">Token de « {created.name} »</h2>
          <p role="alert">
            Il ne sera plus jamais affiché. Copie-le maintenant dans l’app FreeTube (Réglages → Token d’appareil).
          </p>
          <label htmlFor="token-value">Token</label>
          <input id="token-value" readOnly value={created.token} onFocus={(e) => e.currentTarget.select()} />
          <div className="actions">
            <button type="button" onClick={() => void copy(created.token)}>Copier</button>
            <button type="button" onClick={() => setCreated(null)}>J’ai copié le token</button>
          </div>
        </section>
      )}

      {devices.isPending && <p>Chargement…</p>}
      {devices.isError && <p role="alert">{errorMessage(devices.error)}</p>}
      {devices.data?.length === 0 && <p>Aucun appareil.</p>}
      <ul className="rows">
        {devices.data?.map((device) => (
          <li key={device.id} className="row">
            <div className="meta">
              <span className="title">{device.name}</span>
              <span className="muted">
                Créé le {date(device.created_at)} · dernière utilisation : {date(device.last_used_at)}
              </span>
              {device.revoked_at && <span className="muted">Révoqué le {date(device.revoked_at)}</span>}
            </div>
            {!device.revoked_at && (
              <button
                type="button"
                aria-label={`Révoquer ${device.name}`}
                disabled={revoke.isPending}
                onClick={() =>
                  window.confirm(`Révoquer « ${device.name} » ? L’appareil ne pourra plus se synchroniser.`) &&
                  revoke.mutate(device)
                }
              >
                Révoquer
              </button>
            )}
          </li>
        ))}
      </ul>
    </>
  );
}
