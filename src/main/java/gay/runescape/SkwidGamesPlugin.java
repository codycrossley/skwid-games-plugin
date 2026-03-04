package gay.runescape;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.swing.*;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;

import java.util.HashSet;
import java.util.Set;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.plugins.*;
import net.runelite.client.util.Text;
import net.runelite.client.util.ImageUtil;


@Slf4j
@PluginDescriptor(name = "Skwid Games")
public class SkwidGamesPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private MenuManager menuManager;
    @Inject private ConfigManager configManager;
    @Inject private SkwidGamesConfig config;
    @Inject private net.runelite.client.ui.ClientToolbar clientToolbar;
    @Inject private SkwidGamesPanel panel;
    @Inject private net.runelite.client.ui.overlay.OverlayManager overlayManager;

    private net.runelite.client.ui.NavigationButton navButton;

    private AccountConfig accountConfig;
    private GameService gameService;
    private RelayClient relayClient;
    private EventPoller poller;
    private RosterReducer rosterReducer;
    private RoleOverlay roleOverlay;
    private TileMarkerReducer tileMarkerReducer;
    private SharedTileOverlay tileOverlay;
    /** RSNs (lowercase) for which the Commander has published an elimination this session. */
    private final Set<String> pendingEliminations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Tracks the last seen stoplight state to detect RED transitions for elimination. */
    private String lastSeenStoplightState = "GREEN";

    @Provides
    SkwidGamesConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(SkwidGamesConfig.class);
    }

    @Override
    protected void startUp()
    {
        accountConfig = new AccountConfig(configManager, client);

        relayClient = new RelayClient("https://skwid.runescape.gay");
        gameService = new GameService(client, accountConfig, relayClient);
        rosterReducer = new RosterReducer();
        tileMarkerReducer = new TileMarkerReducer();

        poller = new EventPoller(relayClient, new EventPoller.Listener()
        {
            @Override
            public void onEvent(RelayClient.EventOut e)
            {
                rosterReducer.apply(e);
                tileMarkerReducer.apply(e);

                // Release pending-elimination lock when the relay confirms the elimination
                if ("ELIMINATED".equals(e.type) && e.payload != null
                        && e.payload.has("player") && !e.payload.get("player").isJsonNull())
                {
                    pendingEliminations.remove(
                            e.payload.get("player").getAsString().toLowerCase(java.util.Locale.ROOT));
                }

                if ("GAME_ENDED".equals(e.type))
                {
                    resetLocalGameState();
                    gameService.clearActiveGameLocally();
                    SwingUtilities.invokeLater(() -> panel.showGameEnded());
                }
                else
                {
                    SwingUtilities.invokeLater(() -> panel.refreshState());
                }
            }

            @Override
            public void onError(Exception e)
            {
                log.debug("Poll error: {}", e.getMessage());
            }
        });

        menuManager.addPlayerMenuItem("Enlist…");
        roleOverlay = new RoleOverlay(client, config, gameService, rosterReducer);
        overlayManager.add(roleOverlay);
        tileOverlay = new SharedTileOverlay(client, config, gameService, tileMarkerReducer, rosterReducer);
        overlayManager.add(tileOverlay);

        // TODO: Design an icon
        java.awt.image.BufferedImage icon = ImageUtil.loadImageResource(SkwidGamesPlugin.class, "panel_icon.png");

        navButton = net.runelite.client.ui.NavigationButton.builder()
                .tooltip("Skwid Games")
                .icon(icon)
                .panel(panel)
                .priority(5)
                .build();

        clientToolbar.addNavigation(navButton);

        String active = gameService.getActiveGameId();
        if (!relayClient.isEnabled())
        {
            // Relay not configured — clear any stale saved game so the panel doesn't show a ghost IN_GAME view
            if (active != null && !active.isBlank())
            {
                gameService.clearActiveGameLocally();
            }
        }
        else if (active != null && !active.isBlank())
        {
            // Resume polling for a game that was active in the previous session
            poller.start(active);
        }

        panel.refreshState();
    }

    @Override
    protected void shutDown()
    {
        menuManager.removePlayerMenuItem("Enlist…");

        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }

        if (roleOverlay != null) overlayManager.remove(roleOverlay);
        if (tileOverlay != null) overlayManager.remove(tileOverlay);
        if (poller != null) poller.shutdown();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() != GameState.LOGGED_IN) return;

        // RS profile is now available. Check if there is a saved game to resume.
        String active = gameService.getActiveGameId();
        if (!relayClient.isEnabled())
        {
            // Relay still not configured — clear any stale state and show lobby
            if (active != null && !active.isBlank())
            {
                gameService.clearActiveGameLocally();
            }
        }
        else if (active != null && !active.isBlank() && !poller.isRunning())
        {
            // Poller hasn't started yet (e.g. RS profile was unavailable at startUp time).
            // Begin polling now so the roster and overlay stay in sync with the relay.
            poller.start(active);
        }

        SwingUtilities.invokeLater(panel::refreshState);
    }

    // -------------------------------------------------------------------------
    // Panel entry points
    // -------------------------------------------------------------------------

    public String getActiveGameId()
    {
        return gameService != null ? gameService.getActiveGameId() : null;
    }

    public void startGameFromPanel()
    {
        if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
        {
            JOptionPane.showMessageDialog(null, "You must be logged in to create a game.", "Skwid Games", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String existingGameId = gameService.getActiveGameId();
        if (existingGameId != null && !existingGameId.isBlank())
        {
            SwingUtilities.invokeLater(() ->
            {
                int res = JOptionPane.showConfirmDialog(
                        null,
                        "You already have an active game selected on this account:\n\n"
                                + existingGameId
                                + "\n\nStart a new game anyway?\n\n"
                                + "This will clear your local pointers to the old game.\n"
                                + "(It will NOT end the old game on the relay unless you explicitly end it.)",
                        "Skwid Games",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE
                );

                if (res == JOptionPane.YES_OPTION)
                {
                    startNewGameInternal(true);
                }
            });

            return;
        }

        startNewGameInternal(false);
    }

    private void startNewGameInternal(boolean force)
    {
        resetLocalGameState();
        if (force)
        {
            gameService.clearActiveGameLocally();
        }

        new Thread(() ->
        {
            try
            {
                String gameId = gameService.startNewGame();
                String joinCode = gameService.getJoinCode();

                if (poller != null && relayClient != null && relayClient.isEnabled()
                        && gameId != null && !gameId.isBlank())
                {
                    poller.start(gameId);
                    loadTilesAsync(gameId);
                }

                String msg = (joinCode != null && !joinCode.isBlank())
                        ? "Started new Skwid Game. You are the Commander. Join code: " + joinCode
                        : "Started new Skwid Game. You are the Commander.";
                chat(msg);
                SwingUtilities.invokeLater(panel::refreshState);
            }
            catch (Exception e)
            {
                log.warn("Failed to start game", e);
                chat("Failed to start game: " + e.getMessage());
            }
        }, "skwid-create-game").start();
    }

    public void joinGameFromPanel(String joinCode)
    {
        String currentGameId = gameService.getActiveGameId();
        String currentWriteKey = gameService.getWriteKey();

        // If they are already in a game, joining is effectively "switch game"
        if (currentGameId != null && !currentGameId.isBlank())
        {
            boolean isCommander = currentWriteKey != null && !currentWriteKey.isBlank();
            int res = JOptionPane.showConfirmDialog(
                    null,
                    isCommander
                            ? "You are currently the Commander of an active game.\n\nLeave it and join a different game?"
                            : "You are already in a game.\n\nLeave it and join a different game?",
                    "Skwid Games",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (res != JOptionPane.YES_OPTION) return;

            resetLocalGameState();
            gameService.clearActiveGameLocally();
        }

        new Thread(() ->
        {
            try
            {
                gameService.joinGame(joinCode);

                String gameId = gameService.getActiveGameId();
                if (poller != null && relayClient != null && relayClient.isEnabled())
                {
                    int startSeq = 0;
                    try
                    {
                        RelayClient.RosterSnapshotResponse snap = gameService.fetchRoster(gameId);
                        rosterReducer.loadSnapshot(toSnapshotPlayers(snap.players));
                        startSeq = snap.latestSeq;
                    }
                    catch (Exception ex)
                    {
                        log.debug("Roster snapshot unavailable, replaying from seq 0: {}", ex.getMessage());
                    }
                    poller.start(gameId, startSeq);
                    loadTilesAsync(gameId);
                }

                chat("You have joined a Skwid Game (join code: " + gameService.getJoinCode() + ").");
                SwingUtilities.invokeLater(panel::refreshState);
            }
            catch (Exception e)
            {
                chat("Failed to join game: " + e.getMessage());
                SwingUtilities.invokeLater(panel::refreshState);
            }
        }, "skwid-join-game").start();
    }

    public void endRoundFromPanel()
    {
        SwingUtilities.invokeLater(() ->
        {
            int res = JOptionPane.showConfirmDialog(
                    null,
                    "Are you sure you want to end the game?\n\nThis cannot be undone.",
                    "Skwid Games",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (res != JOptionPane.YES_OPTION) return;

            new Thread(() ->
            {
                try
                {
                    gameService.endGame();
                    resetLocalGameState();
                    chat("Skwid Game ended.");
                    SwingUtilities.invokeLater(panel::showGameEnded);
                }
                catch (Exception e)
                {
                    log.warn("Failed to end game", e);
                    chat("Failed to end game: " + e.getMessage());
                }
            }, "skwid-end-game").start();
        });
    }

    public void leaveGameFromPanel()
    {
        // Clear local state immediately so the panel updates without waiting for the network
        resetLocalGameState();
        gameService.clearActiveGameLocally();
        panel.refreshState();

        new Thread(() ->
        {
            try
            {
                gameService.leaveGameRemote();
                chat("Left game.");
            }
            catch (Exception e)
            {
                log.warn("Failed to notify relay of LEFT event", e);
            }
        }, "skwid-leave-game").start();
    }

    private void resetLocalGameState()
    {
        if (poller != null)
        {
            poller.stop();
        }
        if (rosterReducer != null)
        {
            rosterReducer.reset();
        }
        if (tileMarkerReducer != null)
        {
            tileMarkerReducer.reset();
        }
        pendingEliminations.clear();
        lastSeenStoplightState = "GREEN";
    }

    public String getJoinCode()
    {
        return gameService != null ? gameService.getJoinCode() : null;
    }

    public String getCommander()
    {
        return gameService != null ? gameService.getCommander() : null;
    }

    public java.util.List<RosterReducer.RosterEntry> getRosterSnapshot()
    {
        return rosterReducer.snapshot();
    }

    public boolean isLocalCommander()
    {
        return gameService.isLocalCommander();
    }

    public boolean isLocalGuard()
    {
        if (rosterReducer == null || client.getLocalPlayer() == null) return false;
        String localName = net.runelite.client.util.Text.toJagexName(client.getLocalPlayer().getName());
        return localName != null && rosterReducer.getRole(localName) == PlayerRole.GUARD;
    }

    public void eliminateFromPanel(String rsn)
    {
        boolean isCommander = gameService.isLocalCommander();
        new Thread(() ->
        {
            try
            {
                if (isCommander) gameService.eliminate(rsn);
                else             gameService.eliminateAsGuard(rsn);
                chat("Eliminated " + rsn + ".");
            }
            catch (Exception ex)
            {
                chat("Failed to eliminate " + rsn + ": " + ex.getMessage());
                log.warn("Failed to eliminate {}", rsn, ex);
            }
        }, "skwid-eliminate").start();
    }

    public void removeFromPanel(String rsn)
    {
        remove(rsn);
    }

    public void reviveFromPanel(String rsn)
    {
        boolean isCommander = gameService.isLocalCommander();
        new Thread(() ->
        {
            try
            {
                if (isCommander) gameService.revive(rsn);
                else             gameService.reviveAsGuard(rsn);
                chat("Revived " + rsn + ".");
            }
            catch (Exception ex)
            {
                chat("Failed to revive " + rsn + ": " + ex.getMessage());
                log.warn("Failed to revive {}", rsn, ex);
            }
        }, "skwid-revive").start();
    }

    public String getStoplightState()
    {
        return tileMarkerReducer != null ? tileMarkerReducer.getStoplightState() : "GREEN";
    }

    public void setStoplightFromPanel(String state)
    {
        new Thread(() ->
        {
            try
            {
                gameService.publishStoplightState(state);

                // Apply state locally immediately — don't wait for the EventPoller round-trip.
                // clientThread.invokeLater ensures player positions are read safely.
                tileMarkerReducer.setStoplightState(state);
                clientThread.invokeLater(() ->
                {
                    lastSeenStoplightState = state;
                    if ("RED".equals(state))
                    {
                        eliminatePlayersOnStoplightTiles();
                    }
                });
            }
            catch (Exception ex)
            {
                log.warn("Failed to publish stoplight state: {}", ex.getMessage());
                chat("Failed to set stoplight: " + ex.getMessage());
            }
        }, "skwid-stoplight-toggle").start();
    }

    private static java.util.List<RosterReducer.SnapshotPlayer> toSnapshotPlayers(
            java.util.List<RelayClient.RosterPlayerOut> players)
    {
        java.util.List<RosterReducer.SnapshotPlayer> out = new java.util.ArrayList<>();
        if (players == null) return out;
        for (RelayClient.RosterPlayerOut p : players)
        {
            if (p == null) continue;
            out.add(new RosterReducer.SnapshotPlayer(p.rsn, p.role, p.number, p.status, p.joined));
        }
        return out;
    }

    private void loadTilesAsync(String gameId)
    {
        new Thread(() ->
        {
            try
            {
                java.util.List<TileMarkerReducer.TileMarkerEntry> tiles =
                        gameService.fetchTiles(gameId);
                tileMarkerReducer.loadAll(tiles);
            }
            catch (Exception ex)
            {
                log.debug("Failed to fetch tiles: {}", ex.getMessage());
            }
        }, "skwid-fetch-tiles").start();
    }

    // -------------------------------------------------------------------------
    // Menu handling
    // -------------------------------------------------------------------------

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded e)
    {
        // Option is the verb ("Walk here", "Trade", etc.)
        if ("Enamour".equals(e.getOption()))
        {
            client.getMenuEntries()[client.getMenuEntries().length - 1].setOption("Eliminate");
        }

        replaceMenuTargetWithNumber();

        // Tile marking: inject when Commander shift+right-clicks the ground
        if ("Walk here".equals(e.getOption())
                && gameService != null
                && gameService.isLocalCommander()
                && client.isKeyPressed(KeyCode.KC_SHIFT))
        {
            Tile selectedTile = client.getSelectedSceneTile();
            if (selectedTile != null)
            {
                WorldPoint wp = selectedTile.getWorldLocation();
                boolean alreadyMarked = tileMarkerReducer.getMarker(wp) != null;
                String label = alreadyMarked ? "Unmark tile" : "Mark tile";
                client.createMenuEntry(-1)
                        .setOption(label)
                        .setTarget("")
                        .setType(net.runelite.api.MenuAction.RUNELITE)
                        .setDeprioritized(false);
            }
        }
    }

    /**
     * If the menu entry's target is a contestant with an assigned number,
     * replaces their RSN with "Player 001" (etc.) in the displayed menu.
     */
    private void replaceMenuTargetWithNumber()
    {
        if (gameService == null || rosterReducer == null) return;
        if (gameService.getActiveGameId() == null || gameService.getActiveGameId().isBlank()) return;

        // Grab the MenuEntry directly — consistent with how the Enamour rename works,
        // and avoids relying on any deprecated MenuEntryAdded convenience getters.
        MenuEntry entry = client.getMenuEntries()[client.getMenuEntries().length - 1];

        // Strip all markup to get the raw display text (e.g. "PlayerName(level-126)")
        String rawTarget = Text.removeTags(entry.getTarget());
        if (rawTarget == null || rawTarget.isBlank()) return;

        // Isolate the RSN by removing the level annotation
        String nameOnly = rawTarget.replaceFirst("\\s*\\(level\\s*-?\\s*\\d+\\)\\s*$", "").trim();
        String canonical = Text.toJagexName(nameOnly);
        if (canonical == null || canonical.isBlank()) return;

        Integer number = rosterReducer.getNumber(canonical);
        if (number == null) return;

        String label = "Player " + String.format("%03d", number);

        // Replace only the bare name in the original target, preserving any color
        // tags and the level annotation (e.g. <col=ffff00>PlayerName</col>(level-126)
        // becomes <col=ffff00>Player 001</col>(level-126)).
        entry.setTarget(entry.getTarget().replace(nameOnly, label));
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        final String opt = event.getMenuOption();
        if (opt == null)
        {
            return;
        }

        // New: eliminate path
        if ("Eliminate".equalsIgnoreCase(opt))
        {
            handleEliminate(event);
            return;
        }

        // Existing: enlist path
        if ("Enlist…".equalsIgnoreCase(opt) || "Enlist...".equalsIgnoreCase(opt))
        {
            handleEnlist(event);
            return;
        }

        // Tile marking
        if ("Mark tile".equalsIgnoreCase(opt))
        {
            handleMarkTile();
            return;
        }

        if ("Unmark tile".equalsIgnoreCase(opt))
        {
            handleUnmarkTile();
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (gameService == null || rosterReducer == null) return;
        if (gameService.getActiveGameId() == null || gameService.getActiveGameId().isBlank()) return;

        String rawName = Text.removeTags(event.getName());
        if (rawName == null || rawName.isBlank()) return;

        String canonical = Text.toJagexName(rawName);
        if (canonical == null || canonical.isBlank()) return;

        Integer number = rosterReducer.getNumber(canonical);
        if (number == null) return;

        event.getMessageNode().setName("(" + String.format("%03d", number) + ") " + canonical);
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (gameService == null || !gameService.isLocalCommander()) return;
        String gameId = gameService.getActiveGameId();
        if (gameId == null || gameId.isBlank()) return;

        // Stoplight: eliminate players on STOPLIGHT tiles at the moment state turns RED
        String currentStoplightState = tileMarkerReducer.getStoplightState();
        boolean stoplightJustTurnedRed = "RED".equals(currentStoplightState)
                && "GREEN".equals(lastSeenStoplightState);
        lastSeenStoplightState = currentStoplightState;

        if (stoplightJustTurnedRed)
        {
            eliminatePlayersOnStoplightTiles();
        }

        if (tileMarkerReducer.snapshotLandmines().isEmpty()) return;

        for (Player p : client.getPlayers())
        {
            if (p == null || p.getName() == null) continue;
            String rsn = Text.toJagexName(p.getName());
            if (rsn == null || rsn.isBlank()) continue;

            if (rosterReducer.getRole(rsn) != PlayerRole.CONTESTANT) continue;
            if (rosterReducer.getStatus(rsn) == PlayerStatus.ELIMINATED) continue;
            if (pendingEliminations.contains(rsn.toLowerCase(java.util.Locale.ROOT))) continue;

            TileMarkerReducer.TileMarkerEntry landmine =
                    tileMarkerReducer.getMarker(p.getWorldLocation());
            if (landmine == null || !"LANDMINE".equalsIgnoreCase(landmine.tileClass)) continue;

            pendingEliminations.add(rsn.toLowerCase(java.util.Locale.ROOT));
            final String victim = rsn;
            final TileMarkerReducer.TileMarkerEntry triggeredTile = landmine;
            new Thread(() ->
            {
                try
                {
                    gameService.eliminate(victim);
                }
                catch (Exception ex)
                {
                    log.warn("Landmine eliminate failed for {}", victim, ex);
                    pendingEliminations.remove(victim.toLowerCase(java.util.Locale.ROOT));
                }
                try
                {
                    // Reveal the tile to everyone now that it has been triggered
                    Set<String> everyone = new HashSet<>(java.util.Arrays.asList(
                            "COMMANDER", "GUARD", "CONTESTANT"));
                    gameService.markTile(triggeredTile.point, triggeredTile.label,
                            triggeredTile.tileClass, everyone);
                }
                catch (Exception ex)
                {
                    log.warn("Landmine reveal failed", ex);
                }
            }, "skwid-landmine").start();
        }
    }

    /** Must be called on the client thread. Eliminates all contestants currently on STOPLIGHT tiles. */
    private void eliminatePlayersOnStoplightTiles()
    {
        java.util.List<TileMarkerReducer.TileMarkerEntry> stoplights =
                tileMarkerReducer.snapshotStoplights();
        if (stoplights.isEmpty()) return;

        Set<WorldPoint> stoplightPoints = new HashSet<>();
        for (TileMarkerReducer.TileMarkerEntry sl : stoplights)
        {
            stoplightPoints.add(sl.point);
        }

        for (Player p : client.getPlayers())
        {
            if (p == null || p.getName() == null) continue;
            String rsn = Text.toJagexName(p.getName());
            if (rsn == null || rsn.isBlank()) continue;
            if (rosterReducer.getRole(rsn) != PlayerRole.CONTESTANT) continue;
            if (rosterReducer.getStatus(rsn) == PlayerStatus.ELIMINATED) continue;
            if (pendingEliminations.contains(rsn.toLowerCase(java.util.Locale.ROOT))) continue;

            if (stoplightPoints.contains(p.getWorldLocation()))
            {
                pendingEliminations.add(rsn.toLowerCase(java.util.Locale.ROOT));
                final String victim = rsn;
                new Thread(() ->
                {
                    try { gameService.eliminate(victim); }
                    catch (Exception ex)
                    {
                        log.warn("Stoplight eliminate failed for {}", victim, ex);
                        pendingEliminations.remove(victim.toLowerCase(java.util.Locale.ROOT));
                    }
                }, "skwid-stoplight").start();
            }
        }
    }

    private void handleEnlist(MenuOptionClicked event)
    {
        final String target = resolveMenuTarget(event.getMenuTarget());
        if (target == null || target.isBlank())
        {
            chat("Could not resolve player name.");
            return;
        }

        String active = gameService.getActiveGameId();
        if (active == null || active.isBlank())
        {
            chat("No active Skwid Game. Use the panel to start or join a game first.");
            return;
        }

        SwingUtilities.invokeLater(() ->
        {
            Object[] options = {"Guard", "Contestant", "Remove", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                    null,
                    "Set role for " + target + ":",
                    "Skwid Games",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    options,
                    options[3]
            );

            if (choice == 0)
            {
                enlist(target, PlayerRole.GUARD);
            }
            else if (choice == 1)
            {
                enlist(target, PlayerRole.CONTESTANT);
            }
            else if (choice == 2)
            {
                remove(target);
            }
        });
    }

    private void handleEliminate(MenuOptionClicked event)
    {
        String target = resolveMenuTarget(event.getMenuTarget());
        if (target == null || target.isBlank())
        {
            chat("Could not resolve player name.");
            return;
        }

        String active = gameService.getActiveGameId();
        if (active == null || active.isBlank())
        {
            chat("No active Skwid Game. Start or join a game first.");
            return;
        }

        // Only contestants can be eliminated
        PlayerRole role = rosterReducer.getRole(target);
        if (role != PlayerRole.CONTESTANT)
        {
            chat(target + " is not a contestant.");
            return;
        }

        // Optional: ignore if already eliminated
        if (rosterReducer.getStatus(target) == PlayerStatus.ELIMINATED)
        {
            return;
        }

        // Determine if the local player has permission to eliminate
        final boolean isCommander = gameService.isLocalCommander();
        final String localName = client.getLocalPlayer() != null ? Text.toJagexName(client.getLocalPlayer().getName()) : null;
        final boolean isGuard = localName != null && rosterReducer.getRole(localName) == PlayerRole.GUARD;

        if (!isCommander && !isGuard)
        {
            chat("Only the Commander or a Guard can eliminate players.");
            return;
        }

        new Thread(() ->
        {
            try
            {
                if (isCommander)
                {
                    gameService.eliminate(target);
                }
                else
                {
                    gameService.eliminateAsGuard(target);
                }
                chat("Eliminated " + target + ".");
            }
            catch (Exception ex)
            {
                chat("Failed to eliminate " + target + ": " + ex.getMessage());
                log.warn("Failed to eliminate {}", target, ex);
            }
        }, "skwid-eliminate").start();
    }

    private void enlist(String target, PlayerRole playerRole)
    {
        new Thread(() ->
        {
            try
            {
                gameService.enlist(target, playerRole);
                chat("Enlisted " + target + " as " + playerRole.name() + ".");
            }
            catch (Exception e)
            {
                chat("Failed to enlist: " + e.getMessage());
            }
        }, "skwid-enlist").start();
    }

    private void remove(String target)
    {
        new Thread(() ->
        {
            try
            {
                gameService.removePlayer(target);
                chat("Removed " + target + " from the game.");
            }
            catch (Exception e)
            {
                chat("Failed to remove: " + e.getMessage());
            }
        }, "skwid-remove").start();
    }

    private void handleMarkTile()
    {
        Tile tile = client.getSelectedSceneTile();
        if (tile == null) return;
        WorldPoint wp = tile.getWorldLocation();

        SwingUtilities.invokeLater(() ->
        {
            // --- Label ---
            javax.swing.JTextField labelField = new javax.swing.JTextField(16);

            // --- Tile class radio buttons ---
            String[] classes = {"STANDARD", "LANDMINE", "STOPLIGHT"};
            javax.swing.ButtonGroup classGroup = new javax.swing.ButtonGroup();
            javax.swing.JRadioButton[] classButtons = new javax.swing.JRadioButton[classes.length];
            javax.swing.JPanel classPanel = new javax.swing.JPanel();
            for (int i = 0; i < classes.length; i++)
            {
                classButtons[i] = new javax.swing.JRadioButton(classes[i]);
                classGroup.add(classButtons[i]);
                classPanel.add(classButtons[i]);
            }
            classButtons[0].setSelected(true); // STANDARD default

            // --- Visibility checkboxes ---
            javax.swing.JCheckBox cbCommander  = new javax.swing.JCheckBox("Commander",  true);
            javax.swing.JCheckBox cbGuard      = new javax.swing.JCheckBox("Guard",      true);
            javax.swing.JCheckBox cbContestant = new javax.swing.JCheckBox("Contestant", true);
            javax.swing.JPanel visPanel = new javax.swing.JPanel();
            visPanel.add(cbCommander);
            visPanel.add(cbGuard);
            visPanel.add(cbContestant);

            // --- Assemble panel ---
            javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.GridLayout(0, 1, 4, 4));
            panel.add(new javax.swing.JLabel("Label (optional):"));
            panel.add(labelField);
            panel.add(new javax.swing.JLabel("Class:"));
            panel.add(classPanel);
            panel.add(new javax.swing.JLabel("Visible to:"));
            panel.add(visPanel);

            int result = JOptionPane.showConfirmDialog(
                    null, panel, "Mark Tile",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

            if (result != JOptionPane.OK_OPTION) return;

            // Collect values
            String rawLabel = labelField.getText().trim();
            final String finalLabel = rawLabel.isEmpty() ? null : rawLabel;

            String selectedClass = "STANDARD";
            for (int i = 0; i < classes.length; i++)
            {
                if (classButtons[i].isSelected())
                {
                    selectedClass = classes[i];
                    break;
                }
            }
            final String finalClass = selectedClass;

            Set<String> visibleTo = new HashSet<>();
            if (cbCommander.isSelected())  visibleTo.add("COMMANDER");
            if (cbGuard.isSelected())      visibleTo.add("GUARD");
            if (cbContestant.isSelected()) visibleTo.add("CONTESTANT");
            // Empty set = visible to all (backward compat)
            final Set<String> finalVisibleTo = visibleTo;

            new Thread(() ->
            {
                try
                {
                    gameService.markTile(wp, finalLabel, finalClass, finalVisibleTo);
                }
                catch (Exception ex)
                {
                    chat("Failed to mark tile: " + ex.getMessage());
                    log.warn("Failed to mark tile", ex);
                }
            }, "skwid-mark-tile").start();
        });
    }

    private void handleUnmarkTile()
    {
        Tile tile = client.getSelectedSceneTile();
        if (tile == null) return;
        WorldPoint wp = tile.getWorldLocation();

        new Thread(() ->
        {
            try
            {
                gameService.unmarkTile(wp);
            }
            catch (Exception ex)
            {
                chat("Failed to unmark tile: " + ex.getMessage());
                log.warn("Failed to unmark tile", ex);
            }
        }, "skwid-unmark-tile").start();
    }

    private void chat(String msg)
    {
        clientThread.invoke(() ->
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null));
    }

    /**
     * Canonicalizes a raw menu target and, if it has been rewritten to "Player ###" form,
     * reverse-looks up the real RSN from the roster.
     */
    private String resolveMenuTarget(String menuTarget)
    {
        String target = canonicalizeMenuTarget(menuTarget);
        if (target == null || target.isBlank()) return target;

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)^player\\s+(\\d+)$")
                .matcher(target.trim());
        if (m.matches())
        {
            int number = Integer.parseInt(m.group(1));
            String resolved = rosterReducer.getRsnByNumber(number);
            if (resolved == null)
            {
                chat("Could not resolve player name from number " + number + ".");
                return null;
            }
            return resolved;
        }

        return target;
    }

    private static String canonicalizeMenuTarget(String menuTarget)
    {
        if (menuTarget == null) return null;

        String s = Text.removeTags(menuTarget);

        // handle both "(level 126)" and "(level-126)" and extra spaces
        s = s.replaceFirst("\\s*\\(level\\s*-?\\s*\\d+\\)\\s*$", "");

        return Text.toJagexName(s);
    }
}