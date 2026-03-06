package gay.runescape;

import com.google.gson.Gson;
import com.google.inject.Provides;
import javax.inject.Inject;
import okhttp3.OkHttpClient;
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
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.*;
import net.runelite.client.util.Text;
import net.runelite.client.util.ImageUtil;


@Slf4j
@PluginDescriptor(name = "Skwid Games")
public class SkwidGamesPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ConfigManager configManager;
    @Inject private SkwidGamesConfig config;
    @Inject private net.runelite.client.ui.ClientToolbar clientToolbar;
    @Inject private SkwidGamesPanel panel;
    @Inject private net.runelite.client.ui.overlay.OverlayManager overlayManager;
    @Inject private OkHttpClient okHttpClient;
    @Inject private Gson gson;

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
    private ExecutorService executor;
    /** Last confirmed tile configuration, used for quick-marking tiles. */
    private volatile TileConfig lastTileConfig = null;

    private static class TileConfig
    {
        final String label;
        final String tileClass;
        final Set<String> visibleTo;
        TileConfig(String label, String tileClass, Set<String> visibleTo)
        {
            this.label     = label;
            this.tileClass = tileClass;
            this.visibleTo = visibleTo;
        }
    }

    @Provides
    SkwidGamesConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(SkwidGamesConfig.class);
    }

    @Override
    protected void startUp()
    {
        log.info("Skwid Games starting up...");
        executor = Executors.newFixedThreadPool(2);
        accountConfig = new AccountConfig(configManager, client, gson);

        relayClient = new RelayClient("https://skwid.runescape.gay", okHttpClient, gson);
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
                log.warn("Poll error: {}", e.getMessage());
            }
        });

        roleOverlay = new RoleOverlay(client, config, gameService, rosterReducer);
        overlayManager.add(roleOverlay);
        tileOverlay = new SharedTileOverlay(client, config, gameService, tileMarkerReducer, rosterReducer);
        overlayManager.add(tileOverlay);

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
            log.info("Resuming game {} from previous session", active);
            poller.start(active);
        }

        panel.refreshState();
        log.info("Skwid Games started");
    }

    @Override
    protected void shutDown()
    {
        log.info("Skwid Games shutting down...");

        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }

        if (roleOverlay != null) overlayManager.remove(roleOverlay);
        if (tileOverlay != null) overlayManager.remove(tileOverlay);
        if (poller != null) poller.shutdown();
        if (executor != null) executor.shutdownNow();
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

        executor.execute(() ->
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
        });
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

        executor.execute(() ->
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
                        log.warn("Roster snapshot unavailable, replaying from seq 0: {}", ex.getMessage());
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
        });
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

            executor.execute(() ->
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
            });
        });
    }

    public void leaveGameFromPanel()
    {
        // Clear local state immediately so the panel updates without waiting for the network
        resetLocalGameState();
        gameService.clearActiveGameLocally();
        panel.refreshState();

        executor.execute(() ->
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
        });
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
        lastTileConfig = null;
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
        if (rosterReducer == null || client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null) return false;
        String localName = net.runelite.client.util.Text.toJagexName(client.getLocalPlayer().getName());
        return localName != null && rosterReducer.getRole(localName) == PlayerRole.GUARD;
    }

    public void eliminateFromPanel(String rsn)
    {
        boolean isCommander = gameService.isLocalCommander();
        executor.execute(() ->
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
        });
    }

    public void removeFromPanel(String rsn)
    {
        remove(rsn);
    }

    public void reviveFromPanel(String rsn)
    {
        boolean isCommander = gameService.isLocalCommander();
        executor.execute(() ->
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
        });
    }

    public String getStoplightState()
    {
        return tileMarkerReducer != null ? tileMarkerReducer.getStoplightState() : "GREEN";
    }

    public void setStoplightFromPanel(String state)
    {
        executor.execute(() ->
        {
            try
            {
                gameService.publishStoplightState(state);

                // Apply state locally immediately — don't wait for the EventPoller round-trip.
                // clientThread.invokeLater ensures player positions are read safely.
                tileMarkerReducer.setStoplightState(state);
                clientThread.invokeLater(() ->
                {
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
        });
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
        executor.execute(() ->
        {
            try
            {
                java.util.List<TileMarkerReducer.TileMarkerEntry> tiles =
                        gameService.fetchTiles(gameId);
                tileMarkerReducer.loadAll(tiles);
            }
            catch (Exception ex)
            {
                log.warn("Failed to fetch tiles: {}", ex.getMessage());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Menu handling
    // -------------------------------------------------------------------------

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded e)
    {
        // Inject "Enlist" on player right-click, only when the local player is an active commander.
        // "Follow" is a standard game option that always appears for other players, used as a
        // single-fire trigger so we add Enlist exactly once per context menu.
        if ("Follow".equals(e.getOption())
                && e.getMenuEntry().getActor() instanceof Player
                && gameService != null
                && gameService.isLocalCommander()
                && gameService.getActiveGameId() != null
                && !gameService.getActiveGameId().isBlank())
        {
            client.createMenuEntry(-1)
                    .setOption("Enlist")
                    .setTarget(e.getTarget())
                    .setType(net.runelite.api.MenuAction.RUNELITE_PLAYER)
                    .setIdentifier(e.getIdentifier());
        }

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

                // Deprioritize all existing entries so our option wins the left-click slot
                for (MenuEntry entry : client.getMenuEntries())
                {
                    entry.setDeprioritized(true);
                }

                if (alreadyMarked)
                {
                    // Quick configure tile below, Reset tile on top
                    if (lastTileConfig != null)
                    {
                        client.createMenuEntry(-1)
                                .setOption("Quick configure tile")
                                .setTarget("")
                                .setType(net.runelite.api.MenuAction.RUNELITE)
                                .setDeprioritized(false);
                    }
                    client.createMenuEntry(-1)
                            .setOption("Reset tile")
                            .setTarget("")
                            .setType(net.runelite.api.MenuAction.RUNELITE)
                            .setDeprioritized(false);
                }
                else
                {
                    // Configure tile below, Quick configure tile on top (if available)
                    client.createMenuEntry(-1)
                            .setOption("Configure tile")
                            .setTarget("")
                            .setType(net.runelite.api.MenuAction.RUNELITE)
                            .setDeprioritized(false);
                    if (lastTileConfig != null)
                    {
                        client.createMenuEntry(-1)
                                .setOption("Quick configure tile")
                                .setTarget("")
                                .setType(net.runelite.api.MenuAction.RUNELITE)
                                .setDeprioritized(false);
                    }
                }
            }
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        final String opt = event.getMenuOption();
        if (opt == null)
        {
            return;
        }

        // Love Crossbow: Enamour
        // Dragon Candle Dagger: Celebrate
        // Mystic Cards: Duel
        // Rubber Chicken: Whack
        if ("Enamour".equalsIgnoreCase(opt) || "Celebrate".equalsIgnoreCase(opt) || "Duel".equalsIgnoreCase(opt) || "Whack".equalsIgnoreCase(opt))
        {
            handleEliminate(event);
            return;
        }

        // Existing: enlist path
        if ("Enlist".equalsIgnoreCase(opt))
        {
            handleEnlist(event);
            return;
        }

        // Tile marking
        if ("Configure tile".equalsIgnoreCase(opt))
        {
            handleMarkTile();
            return;
        }

        if ("Quick configure tile".equalsIgnoreCase(opt))
        {
            Tile tile = client.getSelectedSceneTile();
            if (tile != null) handleQuickMarkTile(tile.getWorldLocation());
            return;
        }

        if ("Reset tile".equalsIgnoreCase(opt))
        {
            handleUnmarkTile();
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (gameService == null || !gameService.isLocalCommander()) return;
        String gameId = gameService.getActiveGameId();
        if (gameId == null || gameId.isBlank()) return;

        // Stoplight: eliminate players on STOPLIGHT tiles every tick while state is RED
        if ("RED".equals(tileMarkerReducer.getStoplightState()))
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
            executor.execute(() ->
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
                    // Reveal the detonated tile to everyone
                    Set<String> everyone = new HashSet<>(java.util.Arrays.asList(
                            "COMMANDER", "GUARD", "CONTESTANT"));
                    gameService.markTile(triggeredTile.point, triggeredTile.label,
                            "LANDMINE_DETONATED", everyone);
                }
                catch (Exception ex)
                {
                    log.warn("Landmine reveal failed", ex);
                }
            });
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
                executor.execute(() ->
                {
                    try { gameService.eliminate(victim); }
                    catch (Exception ex)
                    {
                        log.warn("Stoplight eliminate failed for {}", victim, ex);
                        pendingEliminations.remove(victim.toLowerCase(java.util.Locale.ROOT));
                    }
                });
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

        executor.execute(() ->
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
        });
    }

    private void enlist(String target, PlayerRole playerRole)
    {
        executor.execute(() ->
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
        });
    }

    private void remove(String target)
    {
        executor.execute(() ->
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
        });
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
                    null, panel, "Configure Tile",
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
            final Set<String> finalVisibleTo = visibleTo;

            // Save as the last-used config for quick-mark
            lastTileConfig = new TileConfig(finalLabel, finalClass, new HashSet<>(finalVisibleTo));

            executor.execute(() ->
            {
                try
                {
                    gameService.markTile(wp, finalLabel, finalClass, finalVisibleTo);
                }
                catch (Exception ex)
                {
                    chat("Failed to configure tile: " + ex.getMessage());
                    log.warn("Failed to configure tile", ex);
                }
            });
        });
    }

    private void handleQuickMarkTile(WorldPoint wp)
    {
        TileConfig cfg = lastTileConfig;
        if (cfg == null) return;
        executor.execute(() ->
        {
            try
            {
                gameService.markTile(wp, cfg.label, cfg.tileClass, cfg.visibleTo);
            }
            catch (Exception ex)
            {
                chat("Failed to quick configure tile: " + ex.getMessage());
                log.warn("Failed to quick configure tile", ex);
            }
        });
    }

    private void handleUnmarkTile()
    {
        Tile tile = client.getSelectedSceneTile();
        if (tile == null) return;
        WorldPoint wp = tile.getWorldLocation();

        executor.execute(() ->
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
        });
    }

    private void chat(String msg)
    {
        clientThread.invoke(() ->
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null));
    }

    private String resolveMenuTarget(String menuTarget)
    {
        return canonicalizeMenuTarget(menuTarget);
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