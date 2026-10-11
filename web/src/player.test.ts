import assert from "node:assert/strict";
import { test } from "node:test";
import { current, initialPlayer, playerReducer, type PlayerAction, type PlayerState } from "./player.ts";

const items = ["a", "b", "c", "d"].map((id) => ({ video_id: id, title: id, artist: "" }));
const zero = () => 0;

function run(state: PlayerState, ...actions: PlayerAction[]): PlayerState {
  return actions.reduce(playerReducer, state);
}

const playing = (index: number, patch: Partial<PlayerState> = {}) =>
  run({ ...initialPlayer, ...patch }, { type: "play", queue: items, index, random: zero });

test("play starts the queue at the chosen track", () => {
  const state = playing(2);
  assert.equal(current(state)?.video_id, "c");
  assert.equal(state.playId, 1);
});

test("next advances, then stops at the end without repeat", () => {
  const state = run(playing(2), { type: "next" });
  assert.equal(current(state)?.video_id, "d");
  const end = run(state, { type: "next" }, { type: "ended" });
  assert.equal(end, state, "nothing restarts after the last track");
});

test("repeat all wraps around in both directions", () => {
  const state = playing(3, { repeat: "all" });
  assert.equal(current(run(state, { type: "next" }))?.video_id, "a");
  assert.equal(current(run(playing(0, { repeat: "all" }), { type: "previous", elapsed: 0 }))?.video_id, "d");
});

test("repeat one restarts the same track on ended, but next still moves on", () => {
  const state = playing(1, { repeat: "one" });
  const ended = run(state, { type: "ended" });
  assert.equal(current(ended)?.video_id, "b");
  assert.equal(ended.playId, state.playId + 1);
  assert.equal(current(run(state, { type: "next" }))?.video_id, "c");
});

test("previous restarts after 3 s, goes back before", () => {
  const state = playing(2);
  const restarted = run(state, { type: "previous", elapsed: 3.5 });
  assert.equal(current(restarted)?.video_id, "c");
  assert.equal(restarted.playId, state.playId + 1);
  assert.equal(current(run(state, { type: "previous", elapsed: 1 }))?.video_id, "b");
  assert.equal(current(run(playing(0), { type: "previous", elapsed: 1 }))?.video_id, "a");
});

test("shuffle keeps the current track and plays every track once", () => {
  const state = run(playing(2), { type: "toggleShuffle", random: zero });
  assert.equal(current(state)?.video_id, "c");
  assert.equal(state.playId, 1, "toggling shuffle does not restart the track");
  assert.deepEqual([...state.order].sort(), [0, 1, 2, 3]);
  const seen = [current(state)?.video_id];
  let next = state;
  for (let i = 0; i < 3; i++) {
    next = run(next, { type: "next" });
    seen.push(current(next)?.video_id);
  }
  assert.deepEqual(seen.sort(), ["a", "b", "c", "d"]);
  const unshuffled = run(next, { type: "toggleShuffle", random: zero });
  assert.equal(current(unshuffled)?.video_id, current(next)?.video_id);
  assert.deepEqual(unshuffled.order, [0, 1, 2, 3]);
});

test("play with shuffle on leads with the chosen track", () => {
  const state = playing(1, { shuffle: true });
  assert.equal(current(state)?.video_id, "b");
  assert.equal(state.order.length, 4);
});

test("repeat cycles off, all, one", () => {
  const states = [1, 2, 3].map((n) => run(initialPlayer, ...Array<PlayerAction>(n).fill({ type: "cycleRepeat" })));
  assert.deepEqual(states.map((s) => s.repeat), ["all", "one", "off"]);
});

test("an empty queue ignores navigation", () => {
  for (const action of [{ type: "next" }, { type: "ended" }, { type: "previous", elapsed: 9 }] as PlayerAction[]) {
    assert.equal(run(initialPlayer, action), initialPlayer);
  }
});
