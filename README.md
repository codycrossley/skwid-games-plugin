# Skwid Games

A RuneLite plugin for running Squid-Game-style elimination events inside Old School RuneScape.
A shared relay server ([skwid.runescape.gay](https://skwid.runescape.gay)) keeps all participants in sync in real time.

---

## Overview

Skwid Games lets one player act as the **Commander** — the game organiser — while others join as
**Guards** or **Contestants**. The Commander controls the game flow: assigning roles, marking
tiles with special properties, and triggering automatic eliminations. Guards can also eliminate
contestants manually. Everyone sees a live roster and coloured tile overlays directly in the
RuneLite client.

---

## Roles

| Role | Description |
|------|-------------|
| **Commander** | Creates the game and controls it. Holds the write key. Can assign roles, mark tiles, and end the game. |
| **Guard** | Enlisted by the Commander. Can manually eliminate contestants. |
| **Contestant** | Enlisted by the Commander. Has an assigned number. Can be eliminated. |

---

## Getting Started

### Creating a Game

1. Open the **Skwid Games** panel from the RuneLite sidebar.
2. Click **Create New Game**.
3. Share the displayed **Join Code** with your participants (click it to copy, or use the Copy Join Code button).

### Joining a Game

1. Open the panel and paste the Join Code into the text field.
2. Click **Join Game**.

The panel shows your current status (Ready / In Game / Ended) in the top-right pill.

---

## Assigning Roles

The Commander right-clicks any player in the game world and selects **Enlist…**. A dialog appears
with the following options:

- **Guard** — designates the player as a guard with elimination authority
- **Contestant** — assigns a unique sequential number to the player
- **Remove** — removes the player from the game roster

Contestants' names are replaced with their assigned number (`Player 001`, `Player 002`, etc.) in
right-click menus and in the chat window, helping the Commander keep track of participants.

---

## Roster Panel

Below the game controls, the **Roster** section lists every player in the current game:

| Column | Description |
|--------|-------------|
| **RSN** | Player's RuneScape name (dimmed if eliminated or removed) |
| **#** | Assigned contestant number (`—` for Guards and Commander) |
| **Role** | Commander / Guard / Contestant / Eliminated / Removed |

The roster is populated instantly when you join by fetching a snapshot from the relay, then stays
up to date via live event polling — no full event replay required.

---

## Tile Markers

The Commander can mark tiles on the ground to create zones with special behaviour. To mark a tile:

1. Hold **Shift** and **right-click** any tile in the game world.
2. Select **Mark tile** from the menu.
3. Fill in the dialog:
   - **Label** — optional text shown on the tile overlay
   - **Class** — the tile's behaviour type (see below)
   - **Visible to** — which roles can see the tile highlight (Commander, Guard, Contestant)

To remove a tile, Shift+right-click an already-marked tile and select **Unmark tile**.

### Tile Classes

| Class | Colour | Behaviour |
|-------|--------|-----------|
| **Standard** | Yellow | Purely cosmetic — highlights a zone with no automatic effect. |
| **Landmine** | Red | Automatically eliminates any Contestant who steps on it. The tile is initially hidden from Contestants and is revealed to everyone once it triggers. |
| **Stoplight** | Orange | Used in Red Light, Green Light mode (see below). |

All tile highlights are rendered as coloured polygons on the ground and are visible only to the
roles you selected when marking.

---

## Red Light, Green Light

The **Stoplight** section (visible to the Commander only) appears in the scrollable area of the
panel below the game controls.

- **Green Light** — the safe state. Contestants may move freely.
- **Red Light** — any Contestant currently standing on a **Stoplight** tile is automatically
  eliminated the moment the Commander activates Red Light.

The two buttons act as a toggle: only one can be active at a time. The active button's background
changes colour (dark red or dark green) to make the current state obvious at a glance. Elimination
runs immediately on the Commander's client — no waiting for the next poll cycle — so the response
is as fast as possible.

---

## Manual Elimination

Guards and the Commander can right-click any Contestant in the game world and select **Eliminate**.
The player must have been enlisted as a Contestant and must not already be eliminated. The
elimination is published to the relay so every participant's panel updates.

---

## Ending a Game

The Commander clicks **End Game** in the in-game controls and confirms the dialog. All participants'
panels update to show the game as ended. The relay retains the event log; the plugin simply stops
polling and clears its local state.

---

## Leaving a Game

Any participant can click **Leave Game** to detach from the current game. This does not end the
game — it only removes your local session and publishes a leave event to the relay.

---

## Architecture Notes

- All game state is stored on the relay as an append-only event log. Clients reduce the event
  stream locally.
- The plugin polls the relay every **500 ms** for new events.
- When joining a game, a **roster snapshot** is fetched first so the panel is populated
  immediately. The event poller then starts from the snapshot's sequence number, avoiding any
  event replay.
- Tile state is also bootstrapped from a snapshot endpoint on join.
- The relay is hosted at `https://skwid.runescape.gay`.