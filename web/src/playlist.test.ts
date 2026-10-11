import assert from "node:assert/strict";
import { test } from "node:test";
import { appendItem, moveRow, removeAt, visibleRows } from "./playlist.ts";

// "x" and "y" are not playable on the web (not uploaded yet): hidden, never dropped.
const items = ["a", "x", "b", "y", "c"];
const playable = new Set(["a", "b", "c"]);
const rows = visibleRows(items, playable);

test("hidden items are not rows, rows keep their stored position", () => {
  assert.deepEqual(rows, [
    { videoId: "a", position: 0 },
    { videoId: "b", position: 2 },
    { videoId: "c", position: 4 },
  ]);
});

test("a move swaps with the visible neighbour and leaves hidden items in place", () => {
  assert.deepEqual(moveRow(items, rows, 0, 1), ["b", "x", "a", "y", "c"]);
  assert.deepEqual(moveRow(items, rows, 2, -1), ["a", "x", "c", "y", "b"]);
});

test("a move past either end is a no-op", () => {
  assert.equal(moveRow(items, rows, 0, -1), null);
  assert.equal(moveRow(items, rows, 2, 1), null);
});

test("remove drops the stored position only, even with duplicates elsewhere", () => {
  assert.deepEqual(removeAt(items, 2), ["a", "x", "y", "c"]);
  assert.deepEqual(removeAt(["a", "b", "a"], 2), ["a", "b"]);
  assert.equal(removeAt(items, 9), null);
});

test("add appends once, keeping hidden items", () => {
  assert.deepEqual(appendItem(items, "d"), [...items, "d"]);
  assert.equal(appendItem(items, "b"), null);
  assert.equal(appendItem(items, "x"), null, "a hidden item is still in the playlist");
});

test("edits never mutate the input", () => {
  const before = [...items];
  moveRow(items, rows, 0, 1);
  removeAt(items, 0);
  appendItem(items, "d");
  assert.deepEqual(items, before);
});
