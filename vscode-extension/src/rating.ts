import * as vscode from "vscode";

// A one-time ask for a Marketplace rating, made only once the extension has
// earned it: after a week of use and a good number of finished turns. "Later"
// asks again in a month; rating, the coffee link or "Don't ask again" end it.

const REVIEW_URL = "https://marketplace.visualstudio.com/items?itemName=chelayel.ai-relay&ssr=false#review-details";
export const COFFEE_URL = "https://buymeacoffee.com/chelayel";

const DAY = 24 * 60 * 60 * 1000;
const MIN_DAYS = 7;
const MIN_TURNS = 20;
const LATER_DAYS = 30;

const FIRST_SEEN = "airelay.rating.firstSeen";
const TURNS = "airelay.rating.turns";
const NEXT_ASK = "airelay.rating.nextAsk";
const DONE = "airelay.rating.done";

let asking = false;

/** Counts a finished turn, and asks for a rating when the time has come. */
export function noteTurn(context: vscode.ExtensionContext, now: number = Date.now()) {
  const state = context.globalState;
  if (state.get<boolean>(DONE)) return;
  const firstSeen = state.get<number>(FIRST_SEEN) ?? now;
  const turns = (state.get<number>(TURNS) ?? 0) + 1;
  void state.update(FIRST_SEEN, firstSeen);
  void state.update(TURNS, turns);
  if (asking || turns < MIN_TURNS || now - firstSeen < MIN_DAYS * DAY || now < (state.get<number>(NEXT_ASK) ?? 0)) return;
  asking = true;
  void ask(state, now).finally(() => (asking = false));
}

async function ask(state: vscode.Memento, now: number) {
  const rate = "Rate AI Relay";
  const coffee = "Buy me a coffee";
  const later = "Later";
  const never = "Don't ask again";
  const choice = await vscode.window.showInformationMessage(
    "Enjoying AI Relay? A rating on the Marketplace helps others find it. It takes a minute.",
    rate, coffee, later, never,
  );
  if (choice === rate) void vscode.env.openExternal(vscode.Uri.parse(REVIEW_URL));
  if (choice === coffee) void vscode.env.openExternal(vscode.Uri.parse(COFFEE_URL));
  // Dismissing the notification counts as "Later": it was seen, not answered.
  if (choice === later || choice === undefined) await state.update(NEXT_ASK, now + LATER_DAYS * DAY);
  else await state.update(DONE, true);
}
