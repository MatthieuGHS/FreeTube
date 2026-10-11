/**
 * Playlist edits on the full item list the server stores. Items the web cannot play (not in
 * GET /tracks) are hidden but kept: every PUT sends them back in place, like Android.
 * Each function returns the new items, or null when there is nothing to send.
 */

export type Row = { videoId: string; position: number }; // position: index in the stored items

export function visibleRows(items: readonly string[], playable: ReadonlySet<string>): Row[] {
  return items.flatMap((videoId, position) => (playable.has(videoId) ? [{ videoId, position }] : []));
}

/** Swaps the row with its visible neighbour, so hidden items never move. */
export function moveRow(items: readonly string[], rows: readonly Row[], index: number, by: -1 | 1): string[] | null {
  const row = rows[index];
  const other = rows[index + by];
  if (!row || !other) return null;
  const next = [...items];
  next[row.position] = other.videoId;
  next[other.position] = row.videoId;
  return next;
}

export function removeAt(items: readonly string[], position: number): string[] | null {
  if (position < 0 || position >= items.length) return null;
  return items.filter((_, i) => i !== position);
}

export function appendItem(items: readonly string[], videoId: string): string[] | null {
  return items.includes(videoId) ? null : [...items, videoId];
}
