package com.example;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.swing.*;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
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

    @Provides
    SkwidGamesConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(SkwidGamesConfig.class);
    }

    @Override
    protected void startUp()
    {
        accountConfig = new AccountConfig(configManager, client);

        relayClient = new RelayClient(config.relayBaseUrl());
        gameService = new GameService(client, accountConfig, relayClient);
        rosterReducer = new RosterReducer();

        poller = new EventPoller(relayClient, new EventPoller.Listener()
        {
            @Override
            public void onEvent(RelayClient.EventOut e)
            {
                rosterReducer.apply(e);
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
                    poller.start(gameId);
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

        event.getMessageNode().setName("Player " + String.format("%03d", number));
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