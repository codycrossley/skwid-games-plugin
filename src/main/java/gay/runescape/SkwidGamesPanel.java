package gay.runescape;

import com.google.common.base.Strings;
import com.google.inject.Inject;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

@Singleton
public class SkwidGamesPanel extends PluginPanel
{
    private enum ViewState
    {
        LOBBY,
        IN_GAME
    }

    private static final int BORDER = 8;
    private static final int GAP = 6;

    private final SkwidGamesPlugin plugin;

    // Tab navigation
    private final JLabel navHomeLabel = new JLabel("Home");
    private final JLabel navHelpLabel = new JLabel("Help");
    private final java.awt.CardLayout tabCardLayout = new java.awt.CardLayout();
    private final JPanel tabCardPanel = new JPanel(tabCardLayout);

    // Top header + status
    private final JLabel titleLabel = new JLabel("Skwid Games");
    private final JLabel statusPill = new JLabel("Disconnected");

    // Join code (displayed inside the in-game card)
    private final JLabel joinCodeTopLabel = new JLabel("Join Code");
    private final JLabel joinCodeValueLabel = new JLabel("—");

    // State cards (Lobby / In Game)
    private final java.awt.CardLayout cardLayout = new java.awt.CardLayout();
    private final JPanel cardPanel = new JPanel(cardLayout);

    // Lobby controls
    private final JButton createBtn = new JButton("Create New Game");
    private final JTextField joinField = new JTextField();
    private final JButton joinBtn = new JButton("Join Game");

    // In-game controls
    private final JButton copyJoinCodeBtn = new JButton("Copy Join Code");
    private final JButton leaveBtn = new JButton("Leave Game");
    private final JButton endGameBtn = new JButton("End Game");

    // Stoplight section (commander-only, in scrollable content area)
    private final JToggleButton redLightBtn = new JToggleButton("Red Light");
    private final JToggleButton greenLightBtn = new JToggleButton("Green Light");
    private final ButtonGroup stoplightGroup = new ButtonGroup();
    private final JPanel stoplightSection = new JPanel(new BorderLayout());

    // Roster UI components
    private final JPanel rosterContainer = new JPanel(new BorderLayout());
    // Panel that will hold header + rows (inside its own scroll pane)
    private final JPanel rosterTablePanel = new JPanel();
    /** Last rendered roster key — used to skip rebuilds when nothing changed. */
    private String lastRosterKey = null;

    @Inject
    SkwidGamesPanel(final SkwidGamesPlugin plugin)
    {
        super(false);
        this.plugin = plugin;

        setLayout(new BorderLayout());

        // ----- Top Panel (header + controls) -----
        final JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBorder(new EmptyBorder(BORDER, BORDER, BORDER, BORDER));
        topPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Header row
        final JPanel headerRow = new JPanel(new BorderLayout());
        headerRow.setBackground(ColorScheme.DARK_GRAY_COLOR);

        titleLabel.setFont(FontManager.getRunescapeBoldFont());
        titleLabel.setForeground(ColorScheme.BRAND_ORANGE);
        headerRow.add(titleLabel, BorderLayout.WEST);

        statusPill.setOpaque(true);
        statusPill.setBorder(new EmptyBorder(3, 8, 3, 8));
        statusPill.setHorizontalAlignment(SwingConstants.CENTER);
        statusPill.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        statusPill.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        headerRow.add(statusPill, BorderLayout.EAST);

        topPanel.add(headerRow);
        topPanel.add(Box.createVerticalStrut(8));

        // Cards
        cardPanel.setOpaque(false);
        cardPanel.add(buildLobbyCard(), ViewState.LOBBY.name());
        cardPanel.add(buildInGameCard(), ViewState.IN_GAME.name());
        topPanel.add(cardPanel);

        final JPanel homePanel = new JPanel(new BorderLayout());
        homePanel.add(topPanel, BorderLayout.NORTH);

        // ----- Scrollable content area (anchored north) -----
        final JPanel contentBase = new JPanel();
        contentBase.setBorder(new EmptyBorder(BORDER, BORDER, BORDER, BORDER));
        contentBase.setLayout(new DynamicGridLayout(0, 1, 0, GAP));
        contentBase.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Build scrollable content sections
        buildStoplightSection();
        contentBase.add(stoplightSection);

        buildRosterContainer();
        contentBase.add(rosterContainer);

        final JPanel northAnchor = new JPanel(new BorderLayout());
        northAnchor.setBackground(ColorScheme.DARK_GRAY_COLOR);
        northAnchor.add(contentBase, BorderLayout.NORTH);

        final JScrollPane scrollPane = new JScrollPane(northAnchor);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        homePanel.add(scrollPane, BorderLayout.CENTER);

        // Tabbed navigation
        final JPanel navBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        navBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        navBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
                new EmptyBorder(6, BORDER, 6, BORDER)));
        styleNavLabel(navHomeLabel, true);
        styleNavLabel(navHelpLabel, false);
        navBar.add(navHomeLabel);
        navBar.add(Box.createHorizontalStrut(16));
        navBar.add(navHelpLabel);
        navHomeLabel.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent e) { switchTab("HOME"); }
        });
        navHelpLabel.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent e) { switchTab("HELP"); }
        });

        tabCardPanel.add(homePanel, "HOME");
        tabCardPanel.add(buildHelpPanel(), "HELP");

        final JPanel outer = new JPanel(new BorderLayout());
        outer.add(navBar, BorderLayout.NORTH);
        outer.add(tabCardPanel, BorderLayout.CENTER);
        add(outer, BorderLayout.CENTER);

        // Wire actions
        createBtn.addActionListener(e -> plugin.startGameFromPanel());

        joinBtn.addActionListener(e ->
        {
            final String code = joinField.getText().trim().toUpperCase();
            if (!Strings.isNullOrEmpty(code))
            {
                plugin.joinGameFromPanel(code);
            }
        });

        copyJoinCodeBtn.addActionListener(e -> copyToClipboard(joinCodeValueLabel.getText()));
        leaveBtn.addActionListener(e -> plugin.leaveGameFromPanel());
        endGameBtn.addActionListener(e -> plugin.endRoundFromPanel());
        redLightBtn.addActionListener(e -> plugin.setStoplightFromPanel("RED"));
        greenLightBtn.addActionListener(e -> plugin.setStoplightFromPanel("GREEN"));

        // Uniform button styling
        for (JButton btn : new JButton[]{createBtn, joinBtn, copyJoinCodeBtn, leaveBtn, endGameBtn})
        {
            styleBtn(btn);
        }

        // Per-button foreground overrides (applied after styleBtn so they win)
        leaveBtn.setForeground(new Color(214, 9, 65));
        endGameBtn.setForeground(new Color(214, 9, 65));

        // Toggle buttons get their own styling with active-state backgrounds
        styleToggleBtn(redLightBtn,   new Color(214, 9, 65),  new Color(180, 30, 30),  Color.WHITE);
        styleToggleBtn(greenLightBtn, new Color(0, 200, 0),   new Color(30, 160, 30),  Color.WHITE);

        // IMPORTANT: do NOT call refreshState() here (plugin may not be initialized yet)
        // plugin should call panel.refreshState() in startUp() once services are ready.
        showState(ViewState.LOBBY);
        setStatusDisconnected();
    }

    private JPanel buildLobbyCard()
    {
        final JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new DynamicGridLayout(0, 1, 0, GAP));

        final JLabel orLabel = new JLabel("— or join with code —", SwingConstants.CENTER);
        orLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        orLabel.setFont(FontManager.getRunescapeSmallFont());

        joinField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        joinField.setForeground(Color.WHITE);
        joinField.setCaretColor(Color.WHITE);
        joinField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
                new EmptyBorder(4, 6, 4, 6)));

        p.add(createBtn);
        p.add(orLabel);
        p.add(joinField);
        p.add(joinBtn);

        return p;
    }

    private JPanel buildInGameCard()
    {
        final JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new DynamicGridLayout(0, 1, 0, GAP));

        // Join code display (at top, above actions)
        joinCodeTopLabel.setFont(FontManager.getRunescapeSmallFont());
        joinCodeTopLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        joinCodeTopLabel.setHorizontalAlignment(SwingConstants.CENTER);

        joinCodeValueLabel.setFont(FontManager.getRunescapeBoldFont());
        joinCodeValueLabel.setForeground(ColorScheme.BRAND_ORANGE);
        joinCodeValueLabel.setHorizontalAlignment(SwingConstants.CENTER);
        joinCodeValueLabel.setOpaque(true);
        joinCodeValueLabel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        joinCodeValueLabel.setBorder(new EmptyBorder(6, 8, 6, 8));

        final JMenuItem copyOpt = new JMenuItem("Copy Join Code");
        copyOpt.addActionListener(e -> copyToClipboard(joinCodeValueLabel.getText()));
        final JPopupMenu copyPopup = new JPopupMenu();
        copyPopup.setBorder(new EmptyBorder(5, 5, 5, 5));
        copyPopup.add(copyOpt);
        joinCodeValueLabel.setComponentPopupMenu(copyPopup);

        p.add(joinCodeTopLabel);
        p.add(joinCodeValueLabel);
        p.add(new JSeparator(SwingConstants.HORIZONTAL));

        // Actions
        p.add(copyJoinCodeBtn);
        p.add(leaveBtn);

        // Commander-only actions (visibility toggled in refreshState)
        p.add(endGameBtn);

        return p;
    }

    private void buildStoplightSection()
    {
        TitledBorder border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR), "Stoplight");
        border.setTitleFont(FontManager.getRunescapeBoldFont());
        border.setTitleColor(ColorScheme.BRAND_ORANGE);

        stoplightSection.setOpaque(false);
        stoplightSection.setBorder(border);

        stoplightGroup.add(redLightBtn);
        stoplightGroup.add(greenLightBtn);

        JPanel btnRow = new JPanel(new GridLayout(1, 2, GAP, 0));
        btnRow.setOpaque(false);
        btnRow.setBorder(new EmptyBorder(4, 0, 4, 0));
        btnRow.add(redLightBtn);
        btnRow.add(greenLightBtn);

        stoplightSection.add(btnRow, BorderLayout.CENTER);
        stoplightSection.setVisible(false);
    }

    private void buildRosterContainer()
    {
        TitledBorder border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR), "Roster");
        border.setTitleFont(FontManager.getRunescapeBoldFont());
        border.setTitleColor(ColorScheme.BRAND_ORANGE);

        rosterContainer.setOpaque(false);
        rosterContainer.setBorder(border);

        // DynamicGridLayout: single column, each row gets its natural preferred height
        // and fills the full container width — no inner scroll pane needed.
        rosterTablePanel.setOpaque(false);
        rosterTablePanel.setLayout(new DynamicGridLayout(0, 1, 0, 0));

        rosterContainer.add(rosterTablePanel, BorderLayout.CENTER);
    }

    private static void styleBtn(JButton btn)
    {
        btn.setBackground(ColorScheme.DARK_GRAY_COLOR);
        btn.setForeground(Color.WHITE);
        btn.setOpaque(true);
        btn.setFocusPainted(false);
        btn.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1),
                        BorderFactory.createEmptyBorder(5, 10, 5, 10)
                )
        );
        btn.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseEntered(MouseEvent e)
            {
                btn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                btn.setBackground(ColorScheme.DARK_GRAY_COLOR);
            }
        });
    }

    private static void styleToggleBtn(JToggleButton btn, Color inactiveFg, Color selectedBg, Color selectedFg)
    {
        btn.setUI(new javax.swing.plaf.basic.BasicToggleButtonUI());
        btn.setForeground(inactiveFg);
        btn.setOpaque(true);
        btn.setFocusPainted(false);
        btn.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1),
                        BorderFactory.createEmptyBorder(5, 10, 5, 10)
                )
        );
        btn.addChangeListener(e ->
        {
            btn.setBackground(btn.isSelected() ? selectedBg : ColorScheme.DARK_GRAY_COLOR);
            btn.setForeground(btn.isSelected() ? selectedFg : inactiveFg);
        });
        btn.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseEntered(MouseEvent e)
            {
                if (!btn.isSelected()) btn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                if (!btn.isSelected()) btn.setBackground(ColorScheme.DARK_GRAY_COLOR);
            }
        });
        btn.setBackground(ColorScheme.DARK_GRAY_COLOR);
    }

    private void copyToClipboard(final String s)
    {
        if (Strings.isNullOrEmpty(s) || "—".equals(s))
        {
            return;
        }
        final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(s), null);
    }

    private void showState(final ViewState state)
    {
        cardLayout.show(cardPanel, state.name());
        revalidate();
        repaint();
    }

    private void setJoinCode(final String joinCode)
    {
        joinCodeValueLabel.setText(Strings.isNullOrEmpty(joinCode) ? "—" : joinCode);
    }

    // ----- Roster helpers -----

    private String buildRosterKey()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(plugin.getActiveGameId()).append('|');
        sb.append(plugin.getCommander()).append('|');
        sb.append(plugin.isLocalCommander()).append('|');
        sb.append(plugin.isLocalGuard()).append('|');
        java.util.List<RosterReducer.RosterEntry> entries = plugin.getRosterSnapshot();
        if (entries != null)
        {
            for (RosterReducer.RosterEntry e : entries)
            {
                sb.append(e.rsn).append(':').append(e.role).append(':')
                  .append(e.status).append(':').append(e.number).append(':')
                  .append(e.joined).append(';');
            }
        }
        return sb.toString();
    }

    private void refreshRoster()
    {
        String key = buildRosterKey();
        if (key.equals(lastRosterKey)) return;
        lastRosterKey = key;

        rosterTablePanel.removeAll();

        // Header row
        JPanel header = new JPanel(new GridBagLayout());
        header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        header.setOpaque(true);
        header.setBorder(new EmptyBorder(4, 6, 4, 6));

        GridBagConstraints hc = new GridBagConstraints();
        hc.gridy = 0;
        hc.anchor = GridBagConstraints.WEST;
        hc.fill = GridBagConstraints.HORIZONTAL;

        hc.gridx = 0; hc.weightx = 0.55;
        header.add(makeHeaderLabel("RSN"), hc);

        hc.gridx = 1; hc.weightx = 0.15;
        header.add(makeHeaderLabel("#"), hc);

        hc.gridx = 2; hc.weightx = 0.30;
        header.add(makeHeaderLabel("Role"), hc);

        rosterTablePanel.add(header);
        rosterTablePanel.add(new JSeparator(SwingConstants.HORIZONTAL));

        final String activeGameId = plugin.getActiveGameId();
        if (Strings.isNullOrEmpty(activeGameId))
        {
            rosterTablePanel.add(makeEmptyLabel("No active game."));
            rosterTablePanel.revalidate();
            rosterTablePanel.repaint();
            return;
        }

        final String commanderRsn = plugin.getCommander();
        boolean isCommander = plugin.isLocalCommander();
        boolean isGuard = plugin.isLocalGuard();
        int rowIndex = 0;

        // Commander always at top
        if (!Strings.isNullOrEmpty(commanderRsn))
        {
            rosterTablePanel.add(buildCommanderRow(commanderRsn, rowIndex++));
        }

        // Filter commander out of regular entries so they never appear as "Joined"
        java.util.List<RosterReducer.RosterEntry> entries = plugin.getRosterSnapshot();
        java.util.List<RosterReducer.RosterEntry> filtered = new java.util.ArrayList<>();
        if (entries != null)
        {
            for (RosterReducer.RosterEntry e : entries)
            {
                if (commanderRsn != null && commanderRsn.equalsIgnoreCase(e.rsn)) continue;
                filtered.add(e);
            }
        }

        if (filtered.isEmpty() && Strings.isNullOrEmpty(commanderRsn))
        {
            rosterTablePanel.add(makeEmptyLabel("No players yet."));
        }
        else
        {
            for (RosterReducer.RosterEntry entry : filtered)
            {
                rosterTablePanel.add(buildRosterRow(entry, isCommander, isGuard, rowIndex++));
            }
        }

        rosterTablePanel.revalidate();
        rosterTablePanel.repaint();
    }

    private JLabel makeEmptyLabel(String text)
    {
        JLabel lbl = new JLabel(text, SwingConstants.CENTER);
        lbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        lbl.setFont(FontManager.getRunescapeSmallFont());
        lbl.setBorder(new EmptyBorder(8, 0, 8, 0));
        return lbl;
    }

    private JLabel makeHeaderLabel(String text)
    {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        lbl.setFont(FontManager.getRunescapeSmallFont());
        return lbl;
    }

    private JPanel buildCommanderRow(String commanderRsn, int rowIndex)
    {
        JPanel row = new JPanel(new GridBagLayout());
        row.setBackground(rowIndex % 2 == 0 ? ColorScheme.DARK_GRAY_COLOR : ColorScheme.DARKER_GRAY_COLOR);
        row.setOpaque(true);
        row.setBorder(new EmptyBorder(4, 6, 4, 6));

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        JLabel rsnLabel = new JLabel(commanderRsn);
        rsnLabel.setForeground(Color.WHITE);
        c.gridx = 0; c.weightx = 0.55;
        row.add(rsnLabel, c);

        JLabel numberLabel = new JLabel("—");
        numberLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        numberLabel.setFont(FontManager.getRunescapeSmallFont());
        c.gridx = 1; c.weightx = 0.15;
        row.add(numberLabel, c);

        JLabel roleLabel = new JLabel("Commander");
        roleLabel.setForeground(ColorScheme.BRAND_ORANGE);
        roleLabel.setFont(FontManager.getRunescapeSmallFont());
        c.gridx = 2; c.weightx = 0.30;
        row.add(roleLabel, c);

        return row;
    }

    private JPanel buildRosterRow(RosterReducer.RosterEntry e, boolean isCommander, boolean isGuard, int rowIndex)
    {
        JPanel row = new JPanel(new GridBagLayout());
        row.setBackground(rowIndex % 2 == 0 ? ColorScheme.DARK_GRAY_COLOR : ColorScheme.DARKER_GRAY_COLOR);
        row.setOpaque(true);
        row.setBorder(new EmptyBorder(4, 6, 4, 6));

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        JLabel rsnLabel = new JLabel(e.rsn);
        rsnLabel.setForeground(getRsnColor(e));
        c.gridx = 0; c.weightx = 0.55;
        row.add(rsnLabel, c);

        String numText = e.number != null ? String.format("%03d", e.number) : "—";
        JLabel numberLabel = new JLabel(numText);
        numberLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        numberLabel.setFont(FontManager.getRunescapeSmallFont());
        c.gridx = 1; c.weightx = 0.15;
        row.add(numberLabel, c);

        JLabel statusLabel = new JLabel(describeStatus(e));
        statusLabel.setForeground(getStatusColor(e));
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        c.gridx = 2; c.weightx = 0.30;
        row.add(statusLabel, c);

        // Right-click popup for commander / guard actions on contestants
        boolean aliveContestant = e.role == PlayerRole.CONTESTANT
                && e.status != PlayerStatus.ELIMINATED;
        boolean eliminatedContestant = e.role == PlayerRole.CONTESTANT
                && e.status == PlayerStatus.ELIMINATED;
        boolean canEliminate = aliveContestant && (isCommander || isGuard);
        boolean canRevive    = eliminatedContestant && (isCommander || isGuard);
        boolean canRemove    = isCommander && e.role != PlayerRole.REMOVED;

        if (canEliminate || canRevive || canRemove)
        {
            JPopupMenu popup = new JPopupMenu();
            if (canEliminate)
            {
                JMenuItem eliminateItem = new JMenuItem("Eliminate");
                eliminateItem.addActionListener(ev -> plugin.eliminateFromPanel(e.rsn));
                popup.add(eliminateItem);
            }
            if (canRevive)
            {
                JMenuItem reviveItem = new JMenuItem("Revive");
                reviveItem.addActionListener(ev -> plugin.reviveFromPanel(e.rsn));
                popup.add(reviveItem);
            }
            if (canRemove)
            {
                JMenuItem removeItem = new JMenuItem("Remove");
                removeItem.addActionListener(ev -> plugin.removeFromPanel(e.rsn));
                popup.add(removeItem);
            }
            row.setComponentPopupMenu(popup);
            for (java.awt.Component child : row.getComponents())
            {
                if (child instanceof JLabel)
                {
                    ((JLabel) child).setComponentPopupMenu(popup);
                }
            }
            row.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        }

        return row;
    }

    private Color getStatusColor(RosterReducer.RosterEntry e)
    {
        if (e.role == PlayerRole.REMOVED)    return ColorScheme.MEDIUM_GRAY_COLOR;
        if (e.role == PlayerRole.GUARD)      return ColorScheme.BRAND_ORANGE;
        if (e.role == PlayerRole.CONTESTANT)
        {
            return e.status == PlayerStatus.ELIMINATED
                    ? new Color(214, 9, 65)
                    : ColorScheme.PROGRESS_COMPLETE_COLOR;
        }
        return ColorScheme.LIGHT_GRAY_COLOR;
    }

    private Color getRsnColor(RosterReducer.RosterEntry e)
    {
        if (e.role == PlayerRole.REMOVED)         return ColorScheme.MEDIUM_GRAY_COLOR;
        if (e.status == PlayerStatus.ELIMINATED)  return new Color(160, 100, 100);
        return Color.WHITE;
    }

    private String describeStatus(RosterReducer.RosterEntry e)
    {
        if (e.role == PlayerRole.REMOVED)
        {
            return "Removed";
        }

        if (e.role == PlayerRole.GUARD)
        {
            return "Guard";
        }

        if (e.role == PlayerRole.CONTESTANT)
        {
            if (e.status == PlayerStatus.ELIMINATED)
            {
                return "Eliminated";
            }
            return "Contestant";
        }

        if (e.joined)
        {
            return "Joined";
        }

        return "Unknown";
    }

    // ----- Public API called by plugin -----

    public void refreshState()
    {
        final String activeGameId = plugin.getActiveGameId(); // "in game" signal
        final String joinCode = plugin.getJoinCode();         // what we display/copy

        if (Strings.isNullOrEmpty(activeGameId))
        {
            setStatusIdle();
            setJoinCode(null);
            showState(ViewState.LOBBY);
            refreshRoster();
            return;
        }

        setStatusInGame();
        setJoinCode(joinCode);
        showState(ViewState.IN_GAME);

        boolean isCommander = plugin.isLocalCommander();
        endGameBtn.setVisible(isCommander);
        stoplightSection.setVisible(isCommander);

        if (isCommander)
        {
            boolean isRed = "RED".equals(plugin.getStoplightState());
            redLightBtn.setSelected(isRed);
            greenLightBtn.setSelected(!isRed);
        }

        // When in game, render roster for current game
        refreshRoster();
    }

    public void showGameEnded()
    {
        endGameBtn.setVisible(false);
        stoplightSection.setVisible(false);
        statusPill.setText("Ended");
        statusPill.setBackground(new Color(120, 30, 30));
        statusPill.setForeground(Color.WHITE);
        revalidate();
        repaint();
    }

    public void setStatusDisconnected()
    {
        statusPill.setText("Disconnected");
        statusPill.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        statusPill.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
    }

    public void setStatusIdle()
    {
        statusPill.setText("Ready");
        statusPill.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        statusPill.setForeground(Color.WHITE);
    }

    public void setStatusInGame()
    {
        statusPill.setText("In Game");
        statusPill.setBackground(ColorScheme.PROGRESS_COMPLETE_COLOR);
        statusPill.setForeground(ColorScheme.DARKER_GRAY_COLOR);
    }

    private void styleNavLabel(JLabel lbl, boolean active)
    {
        lbl.setFont(active ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont());
        lbl.setForeground(active ? Color.WHITE : ColorScheme.MEDIUM_GRAY_COLOR);
        lbl.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
    }

    private void switchTab(String name)
    {
        tabCardLayout.show(tabCardPanel, name);
        styleNavLabel(navHomeLabel, "HOME".equals(name));
        styleNavLabel(navHelpLabel, "HELP".equals(name));
    }

    private JPanel buildHelpPanel()
    {
        final JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        p.setBorder(new EmptyBorder(BORDER, BORDER, BORDER, BORDER));

        addHelpSection(p, "Getting Started",
                "Create a game from the Home tab and share the Join Code with participants, "
                + "or enter a code to join an existing game.");

        addHelpSection(p, "Assigning Roles",
                "If you are the commander of a game, you can right-click any player "
                + "in-game and select <b>Enlist</b> to assign them "
                + "as a Guard or Contestant, or to remove them from the game.");

        addHelpSection(p, "Tile Markers",
                "As Commander, hold <b>Shift</b> and right-click any ground tile to mark it. "
                        + "There are three types of tyles<br><br>"
                + "<b>Standard</b>: Purely cosmetic<br>"
                + "<b>Landmine</b>: Auto-eliminates contestants who step on it<br>"
                + "<b>Stoplight</b>: Controlled by the Red Light / Green Light button");

        addHelpSection(p, "Red Light / Green Light",
                "Use the Stoplight toggle on the Home tab. Contestants standing on "
                + "Stoplight tiles are eliminated the moment Red Light is activated.");

        addHelpSection(p, "Elimination",
                "Guards and the Commander can eliminate players by wielding a <b>Love Crossbow</b>."
                + " and clicking <b>Eliminate</b> on any Contestant in-game.");

        p.add(Box.createVerticalGlue());
        return p;
    }

    private static void addHelpSection(JPanel parent, String title, String body)
    {
        final JLabel titleLbl = new JLabel(title);
        titleLbl.setForeground(ColorScheme.BRAND_ORANGE);
        titleLbl.setFont(FontManager.getRunescapeBoldFont());
        titleLbl.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        parent.add(titleLbl);
        parent.add(Box.createVerticalStrut(3));

        final JLabel bodyLbl = new JLabel(
                "<html><body style='width:180px'>" + body + "</body></html>");
        bodyLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        bodyLbl.setFont(FontManager.getRunescapeSmallFont());
        bodyLbl.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        parent.add(bodyLbl);
        parent.add(Box.createVerticalStrut(12));
    }
}