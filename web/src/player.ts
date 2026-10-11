/** Queue state of the persistent player. Pure: the <audio> element follows `current` and `playId`. */

export type QueueItem = { video_id: string; title: string; artist: string };
export type Repeat = "off" | "all" | "one";

export type PlayerState = {
  queue: QueueItem[];
  order: number[]; // play order, indices into queue: identity, or a permutation when shuffled
  pos: number; // position in order
  shuffle: boolean;
  repeat: Repeat;
  playId: number; // bumped on every (re)start: the audio element restarts when it changes
};

export type PlayerAction =
  | { type: "play"; queue: QueueItem[]; index: number; random: () => number }
  | { type: "next" }
  | { type: "previous"; elapsed: number }
  | { type: "ended" }
  | { type: "toggleShuffle"; random: () => number }
  | { type: "cycleRepeat" };

export const RESTART_AFTER_S = 3;
const NEXT_REPEAT: Record<Repeat, Repeat> = { off: "all", all: "one", one: "off" };

export const initialPlayer: PlayerState = {
  queue: [],
  order: [],
  pos: 0,
  shuffle: false,
  repeat: "off",
  playId: 0,
};

export function current(state: PlayerState): QueueItem | undefined {
  const index = state.order[state.pos];
  return index === undefined ? undefined : state.queue[index];
}

/** `first` leads, the other indices follow in random order (Fisher-Yates). */
function shuffled(length: number, first: number, random: () => number): number[] {
  const rest = [...Array(length).keys()].filter((i) => i !== first);
  for (let i = rest.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [rest[i], rest[j]] = [rest[j]!, rest[i]!];
  }
  return [first, ...rest];
}

const identity = (length: number) => [...Array(length).keys()];

function goTo(state: PlayerState, pos: number): PlayerState {
  return { ...state, pos, playId: state.playId + 1 };
}

const restart = (state: PlayerState) => goTo(state, state.pos);

export function playerReducer(state: PlayerState, action: PlayerAction): PlayerState {
  const last = state.order.length - 1;
  switch (action.type) {
    case "play": {
      const { queue, index } = action;
      if (index < 0 || index >= queue.length) return state;
      const order = state.shuffle ? shuffled(queue.length, index, action.random) : identity(queue.length);
      return { ...state, queue, order, pos: state.shuffle ? 0 : index, playId: state.playId + 1 };
    }
    case "next":
      if (state.pos < last) return goTo(state, state.pos + 1);
      return state.repeat === "all" && last >= 0 ? goTo(state, 0) : state;
    case "ended":
      return state.repeat === "one" ? restart(state) : playerReducer(state, { type: "next" });
    case "previous":
      if (last < 0) return state;
      if (action.elapsed > RESTART_AFTER_S) return restart(state);
      if (state.pos > 0) return goTo(state, state.pos - 1);
      return state.repeat === "all" ? goTo(state, last) : restart(state);
    case "toggleShuffle": {
      const index = state.order[state.pos];
      if (index === undefined) return { ...state, shuffle: !state.shuffle };
      return state.shuffle
        ? { ...state, shuffle: false, order: identity(state.queue.length), pos: index }
        : { ...state, shuffle: true, order: shuffled(state.queue.length, index, action.random), pos: 0 };
    }
    case "cycleRepeat":
      return { ...state, repeat: NEXT_REPEAT[state.repeat] };
  }
}
