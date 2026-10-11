import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { devicesQuery, errorMessage, request, type Device, type DeviceCreated } from "../api/client.ts";
import { CheckIcon, CopyIcon, MonitorSmartphoneIcon } from "../icons.tsx";
import { notify } from "../notice.ts";
import { Empty } from "./Layout.tsx";

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
      <div className="page-head">
        <h1>Appareils</h1>
      </div>
      <p className="lead">Chaque téléphone se synchronise avec son propre token. Révoque celui d’un appareil perdu.</p>
      <form
        className="inline-form"
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
        <div className="field">
          <label htmlFor="device-name">Nom du nouvel appareil</label>
          <input
            id="device-name"
            className="input"
            placeholder="Galaxy A06"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            maxLength={200}
          />
        </div>
        <button type="submit" className="btn btn-primary" disabled={create.isPending}>
          Créer un token
        </button>
      </form>

      {created && (
        <section className="callout" aria-labelledby="token-title">
          <h2 id="token-title">Token de « {created.name} »</h2>
          <p role="alert">
            Il ne sera plus jamais affiché. Copie-le maintenant dans l’app FreeTube (Réglages → Token d’appareil).
          </p>
          <div className="field">
            <label htmlFor="token-value">Token</label>
            <input
              id="token-value"
              className="input"
              readOnly
              value={created.token}
              onFocus={(e) => e.currentTarget.select()}
            />
          </div>
          <div className="form-actions">
            <button type="button" className="btn btn-primary" onClick={() => void copy(created.token)}>
              <CopyIcon />
              Copier
            </button>
            <button type="button" className="btn" onClick={() => setCreated(null)}>
              <CheckIcon />
              J’ai copié le token
            </button>
          </div>
        </section>
      )}

      {devices.isPending && <p className="status">Chargement…</p>}
      {devices.isError && <p className="error" role="alert">{errorMessage(devices.error)}</p>}
      {devices.data?.length === 0 && (
        <Empty icon={<MonitorSmartphoneIcon />}>
          <p>Aucun appareil. Crée un token pour connecter l’app Android.</p>
        </Empty>
      )}
      <ul className="rows">
        {devices.data?.map((device) => (
          <li key={device.id} className="row">
            <span className="tile">
              <MonitorSmartphoneIcon />
            </span>
            <div className="meta">
              <span className="title">{device.name}</span>
              <span className="artist">
                Créé le {date(device.created_at)}, dernière utilisation : {date(device.last_used_at)}
              </span>
              {device.revoked_at && <span className="artist">Révoqué le {date(device.revoked_at)}</span>}
            </div>
            {!device.revoked_at && (
              <button
                type="button"
                className="btn btn-danger revoke"
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
