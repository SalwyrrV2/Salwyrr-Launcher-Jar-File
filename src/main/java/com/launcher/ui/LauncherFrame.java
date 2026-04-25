package com.launcher.ui;

import com.launcher.Constants;
import com.launcher.auth.AccountStore;
import com.launcher.auth.AuthManager;
import com.launcher.auth.AuthManager.AuthResult;
import com.launcher.download.MinecraftDownloader;
import com.launcher.launch.GameLauncher;
import com.launcher.install.ModLoaderInstaller;
import com.launcher.install.ModLoaderInstaller.Loader;
import com.launcher.mods.ModManager;
import com.launcher.mods.ModManager.ModInfo;
import com.launcher.mods.ModManager.ModrinthResult;
import org.json.JSONObject;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.Properties;

/**
 * Main launcher window — tabbed UI.
 *
 * Tabs:
 *  1. PLAY      — version, RAM, quick launch
 *  2. ACCOUNTS  — offline / Microsoft / EasyMC / TheAltening; saved account list
 *  3. MODS      — list mods, enable/disable/delete, Modrinth search+install
 *  4. SETTINGS  — game dir, JVM flags, server direct-connect
 */
public class LauncherFrame extends JFrame {

    // ── Palette ───────────────────────────────────────────────────────────────
    private static final Color C_BG      = new Color(14, 14, 20);
    private static final Color C_SURFACE = new Color(24, 24, 34);
    private static final Color C_CARD    = new Color(32, 32, 46);
    private static final Color C_BORDER  = new Color(55, 55, 78);
    private static final Color C_ACCENT  = new Color(99, 102, 241);
    private static final Color C_GREEN   = new Color(34, 197, 94);
    private static final Color C_RED     = new Color(239, 68, 68);
    private static final Color C_YELLOW  = new Color(234, 179, 8);
    private static final Color C_PURPLE  = new Color(168, 85, 247);
    private static final Color C_TEAL    = new Color(20, 184, 166);
    private static final Color C_TEXT    = new Color(226, 232, 240);
    private static final Color C_MUTED   = new Color(148, 163, 184);
    private static final Color C_CONSOLE = new Color(8, 8, 14);

    // ── Global state ──────────────────────────────────────────────────────────
    private AuthResult currentAuth = null;

    // ── Shared widgets ────────────────────────────────────────────────────────
    private final JProgressBar progressBar;
    private final JTextArea    console;
    private final JLabel       statusLabel;

    // ── Play tab ──────────────────────────────────────────────────────────────
    private final JComboBox<String> versionCombo;
    private final JComboBox<String> loaderCombo;
    private final JButton      installLoaderBtn;
    private final JLabel       loaderStatusLabel;
    private final JSlider      ramSlider;
    private final JLabel       ramLabel;
    private final JButton      downloadBtn;
    private final JButton      playBtn;
    private final JLabel       authStatusLabel;
    private final JTextField   serverField;

    // ── Accounts tab ─────────────────────────────────────────────────────────
    private final JList<String>  accountList;
    private final DefaultListModel<String> accountListModel;
    private final JTextField     usernameField;
    private final JTextField     tokenField;
    private       ButtonGroup    authTypeBG;
    private final JRadioButton   rbOffline, rbMicrosoft, rbEasyMC, rbAltening;

    // ── Mods tab ─────────────────────────────────────────────────────────────
    private final JList<String>         modList;
    private final DefaultListModel<String> modListModel;
    private List<ModInfo> cachedMods;
    private final JTextField            searchField;
    private final JList<String>         searchResultList;
    private final DefaultListModel<String> searchResultModel;
    private List<ModrinthResult> lastSearchResults;

    public LauncherFrame() {
        super("Salwyrr Launcher — " + Constants.MINECRAFT_VERSION);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(860, 640);
        setMinimumSize(new Dimension(760, 540));
        setLocationRelativeTo(null);
        getContentPane().setBackground(C_BG);

        // ── Shared bottom bar ─────────────────────────────────────────────────
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Ready");
        progressBar.setForeground(C_ACCENT);
        progressBar.setBackground(C_CARD);
        progressBar.setBorder(new EmptyBorder(0, 12, 0, 12));
        progressBar.setPreferredSize(new Dimension(0, 20));

        console = new JTextArea();
        console.setEditable(false);
        console.setBackground(C_CONSOLE);
        console.setForeground(new Color(134, 239, 172));
        console.setFont(new Font("Consolas", Font.PLAIN, 11));
        console.setMargin(new Insets(6, 8, 6, 8));
        JScrollPane consoleScroll = new JScrollPane(console);
        consoleScroll.setBorder(BorderFactory.createLineBorder(C_BORDER));
        consoleScroll.setPreferredSize(new Dimension(0, 130));

        statusLabel = new JLabel("  " + Constants.GAME_DIR);
        statusLabel.setForeground(C_MUTED);
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        JPanel statusBar = panel(new Color(10, 10, 16));
        statusBar.setLayout(new BorderLayout());
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.setBorder(new EmptyBorder(2, 0, 2, 0));

        // ── Accounts tab — init fields ─────────────────────────────────────────
        usernameField   = styledField("Player", 18);
        tokenField      = styledField("", 22);
        tokenField.setToolTipText("Enter EasyMC token or TheAltening token");
        rbOffline   = radio("Offline / Cracked", true);
        rbMicrosoft = radio("Microsoft Account", false);
        rbEasyMC    = radio("EasyMC Token",      false);
        rbAltening  = radio("TheAltening Token", false);
        authTypeBG  = new ButtonGroup();
        for (JRadioButton rb : new JRadioButton[]{rbOffline, rbMicrosoft, rbEasyMC, rbAltening})
            authTypeBG.add(rb);
        accountListModel = new DefaultListModel<>();
        accountList = styledList(accountListModel);

        // ── Play tab — init fields ─────────────────────────────────────────────
        versionCombo = new JComboBox<>(new String[]{
            "1.21.4","1.21.1","1.20.4","1.20.1","1.19.4","1.19.2",
            "1.18.2","1.17.1","1.16.5","1.12.2","1.8.9","1.7.10"
        });
        versionCombo.setSelectedItem(Constants.MINECRAFT_VERSION);
        versionCombo.setBackground(C_CARD);
        versionCombo.setForeground(C_TEXT);
        versionCombo.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        versionCombo.addActionListener(e -> {
            String v = (String) versionCombo.getSelectedItem();
            if (v != null) {
                Constants.MINECRAFT_VERSION = v;
                refreshLoaderStatus();
            }
        });

        // ── Loader selector ───────────────────────────────────────────────────
        loaderCombo = new JComboBox<>(new String[]{
            "None (Vanilla)", "Fabric", "Forge", "NeoForge", "OptiFine"
        });
        loaderCombo.setBackground(C_CARD);
        loaderCombo.setForeground(C_TEXT);
        loaderCombo.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        // Sync combo to saved loader for current version
        syncLoaderCombo();

        installLoaderBtn = smallBtn("Install / Update", C_TEAL);
        installLoaderBtn.addActionListener(e -> installSelectedLoader());

        loaderStatusLabel = lbl("", C_MUTED, Font.ITALIC, 11);

        long totalMemMb = 4096;
        try {
            com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean)
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            totalMemMb = os.getTotalPhysicalMemorySize() / 1024 / 1024;
        } catch (Throwable ignored) {}
        int maxRam = (int) Math.max(1024, totalMemMb - 1024);
        int defRam = Math.min(4096, Math.max(2048, maxRam / 2));
        int savedRam = loadRam(defRam);

        ramSlider = new JSlider(512, maxRam, savedRam);
        ramSlider.setMajorTickSpacing(1024);
        ramSlider.setPaintTicks(true);
        ramSlider.setBackground(C_SURFACE);
        ramSlider.setForeground(C_MUTED);
        ramLabel = lbl(savedRam + " MB", C_ACCENT, Font.BOLD, 13);
        ramLabel.setPreferredSize(new Dimension(76, 20));
        ramSlider.addChangeListener(e -> {
            ramLabel.setText(ramSlider.getValue() + " MB");
            saveRam(ramSlider.getValue());
        });

        authStatusLabel = lbl("No account selected — go to Accounts tab", C_YELLOW, Font.ITALIC, 12);
        serverField = styledField("", 24);
        serverField.setToolTipText("host:port  (optional — direct-connect on launch)");

        downloadBtn = btn("⬇  Download / Update", C_ACCENT);
        playBtn     = btn("▶  Play",              C_GREEN);
        downloadBtn.addActionListener(e -> startDownload());
        playBtn.addActionListener(e -> startGame());

        // ── Mods tab — init fields ────────────────────────────────────────────
        modListModel    = new DefaultListModel<>();
        modList         = styledList(modListModel);
        searchField     = styledField("", 20);
        searchResultModel = new DefaultListModel<>();
        searchResultList  = styledList(searchResultModel);

        // ── Build tabs ────────────────────────────────────────────────────────
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(C_SURFACE);
        tabs.setForeground(C_TEXT);
        tabs.setFont(new Font("Segoe UI", Font.BOLD, 12));
        tabs.addTab("▶  Play",      buildPlayTab());
        tabs.addTab("👤  Accounts", buildAccountsTab());
        tabs.addTab("🧩  Mods",     buildModsTab());
        tabs.addTab("⚙  Settings", buildSettingsTab());

        // ── Header ────────────────────────────────────────────────────────────
        JLabel header = new JLabel("SALWYRR CLIENT", SwingConstants.CENTER);
        header.setFont(new Font("Segoe UI", Font.BOLD, 22));
        header.setForeground(C_ACCENT);
        header.setBorder(new EmptyBorder(12, 0, 8, 0));
        header.setBackground(C_BG);
        header.setOpaque(true);

        // ── Layout ────────────────────────────────────────────────────────────
        setLayout(new BorderLayout(0, 0));
        add(header, BorderLayout.NORTH);
        JPanel center = panel(C_BG);
        center.setLayout(new BorderLayout(0, 6));
        center.add(tabs, BorderLayout.CENTER);
        center.add(consoleScroll, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);
        JPanel bottom = panel(C_BG);
        bottom.setLayout(new BorderLayout());
        bottom.add(progressBar, BorderLayout.NORTH);
        bottom.add(statusBar, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);

        // ── Init ──────────────────────────────────────────────────────────────
        refreshAccountList();
        refreshModList();
        log("Welcome to Salwyrr Launcher 2.1");
        log("Game dir: " + Constants.GAME_DIR);
        log(Files.exists(Constants.clientJar())
            ? "✓ Client found — pick an account and Play."
            : "⚠ Client not downloaded. Click Download first.");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TAB — PLAY
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildPlayTab() {
        JPanel p = panel(C_SURFACE);
        p.setLayout(new GridBagLayout());
        GridBagConstraints g = gbc();

        // Version row
        g.gridy = 0;
        p.add(lbl("MC Version:", C_MUTED, Font.PLAIN, 12), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        p.add(versionCombo, g);
        g.gridx = 0; g.fill = GridBagConstraints.NONE; g.weightx = 0;

        // RAM row
        g.gridy = 1;
        p.add(lbl("RAM:", C_MUTED, Font.PLAIN, 12), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        p.add(ramSlider, g);
        g.gridx = 2; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        p.add(ramLabel, g);

        // Server row
        g.gridy = 2; g.gridx = 0;
        p.add(lbl("Server IP:", C_MUTED, Font.PLAIN, 12), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        p.add(serverField, g);

        // Loader row
        g.gridy = 3; g.gridx = 0; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        p.add(lbl("Mod Loader:", C_MUTED, Font.PLAIN, 12), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        JPanel loaderRow = panel(C_SURFACE);
        loaderRow.setLayout(new BorderLayout(6, 0));
        loaderRow.add(loaderCombo, BorderLayout.CENTER);
        loaderRow.add(installLoaderBtn, BorderLayout.EAST);
        p.add(loaderRow, g);
        g.gridx = 0; g.gridwidth = 3; g.fill = GridBagConstraints.HORIZONTAL;
        g.gridy = 4;
        p.add(loaderStatusLabel, g);
        refreshLoaderStatus();

        // Auth status
        g.gridy = 5; g.gridx = 0; g.gridwidth = 3;
        g.fill = GridBagConstraints.HORIZONTAL;
        JPanel authBar = panel(C_CARD);
        authBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER),
            new EmptyBorder(6, 10, 6, 10)));
        authBar.setLayout(new BorderLayout());
        authBar.add(authStatusLabel, BorderLayout.WEST);
        JButton switchAccBtn = smallBtn("Switch Account", C_ACCENT);
        switchAccBtn.addActionListener(e -> {
            JTabbedPane tabs = (JTabbedPane) SwingUtilities.getAncestorOfClass(
                JTabbedPane.class, playBtn);
            if (tabs != null) tabs.setSelectedIndex(1); // accounts tab
        });
        authBar.add(switchAccBtn, BorderLayout.EAST);
        p.add(authBar, g);

        // Buttons
        g.gridy = 6; g.gridx = 0; g.gridwidth = 3;
        JPanel btnRow = panel(C_SURFACE);
        btnRow.setLayout(new FlowLayout(FlowLayout.CENTER, 12, 8));
        btnRow.add(downloadBtn);
        btnRow.add(playBtn);
        p.add(btnRow, g);

        return p;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TAB — ACCOUNTS
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildAccountsTab() {
        JPanel p = panel(C_SURFACE);
        p.setLayout(new BorderLayout(8, 8));
        p.setBorder(new EmptyBorder(10, 12, 10, 12));

        // Left — auth type + input
        JPanel inputPanel = panel(C_CARD);
        inputPanel.setBorder(titled("Add Account"));
        inputPanel.setLayout(new GridBagLayout());
        GridBagConstraints g = gbc();

        // Radio row
        g.gridy = 0; g.gridwidth = 2;
        JPanel radioRow = panel(C_CARD);
        radioRow.setLayout(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (JRadioButton rb : new JRadioButton[]{rbOffline, rbMicrosoft, rbEasyMC, rbAltening})
            radioRow.add(rb);
        inputPanel.add(radioRow, g);

        g.gridy = 1; g.gridwidth = 1;
        g.gridx = 0; inputPanel.add(lbl("Username:", C_MUTED, Font.PLAIN, 12), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        inputPanel.add(usernameField, g);

        g.gridy = 2; g.gridx = 0; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        inputPanel.add(lbl("Token:", C_MUTED, Font.PLAIN, 12), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        inputPanel.add(tokenField, g);

        // Token hint
        g.gridy = 3; g.gridx = 0; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL;
        JLabel hint = lbl("  Token only needed for EasyMC / TheAltening modes", C_MUTED, Font.ITALIC, 11);
        inputPanel.add(hint, g);

        // Add/login button
        g.gridy = 4; g.gridwidth = 2;
        JButton addBtn = btn("Login / Add Account", C_GREEN);
        addBtn.addActionListener(e -> handleAddAccount());
        inputPanel.add(addBtn, g);

        // Microsoft device code section
        g.gridy = 5;
        JButton msBtn = btn("Login with Microsoft Browser", C_ACCENT);
        msBtn.addActionListener(e -> {
            rbMicrosoft.setSelected(true);
            handleAddAccount();
        });
        inputPanel.add(msBtn, g);

        // Right — saved accounts list
        JPanel listPanel = panel(C_CARD);
        listPanel.setBorder(titled("Saved Accounts"));
        listPanel.setLayout(new BorderLayout(4, 4));
        listPanel.add(new JScrollPane(accountList), BorderLayout.CENTER);

        JPanel listBtns = panel(C_CARD);
        listBtns.setLayout(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton selectBtn = smallBtn("Use Selected", C_GREEN);
        JButton removeBtn = smallBtn("Remove",       C_RED);
        listBtns.add(selectBtn);
        listBtns.add(removeBtn);
        listPanel.add(listBtns, BorderLayout.SOUTH);

        selectBtn.addActionListener(e -> {
            int idx = accountList.getSelectedIndex();
            if (idx < 0) return;
            List<AccountStore.Account> accs = AccountStore.load();
            if (idx >= accs.size()) return;
            AccountStore.Account acc = accs.get(idx);
            // Restore auth from stored account
            currentAuth = new AuthResult(acc.username, acc.uuid, acc.accessToken,
                acc.type.equals("offline") ? "legacy" : "msa",
                !acc.type.equals("offline"));
            authStatusLabel.setText("✓  " + acc + "  (click Play to launch)");
            authStatusLabel.setForeground(C_GREEN);
            log("Account selected: " + acc);
        });

        removeBtn.addActionListener(e -> {
            int idx = accountList.getSelectedIndex();
            if (idx < 0) return;
            List<AccountStore.Account> accs = AccountStore.load();
            if (idx >= accs.size()) return;
            AccountStore.Account acc = accs.get(idx);
            AccountStore.remove(acc.username, acc.type);
            if (acc.type.equals("microsoft")) AuthManager.logout();
            refreshAccountList();
            log("Removed account: " + acc.username);
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, inputPanel, listPanel);
        split.setDividerLocation(380);
        split.setBackground(C_SURFACE);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TAB — MODS
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildModsTab() {
        JPanel p = panel(C_SURFACE);
        p.setLayout(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(8, 10, 8, 10));

        // Left — installed mods
        JPanel installedPanel = panel(C_CARD);
        installedPanel.setBorder(titled("Installed Mods"));
        installedPanel.setLayout(new BorderLayout(4, 4));
        installedPanel.add(new JScrollPane(modList), BorderLayout.CENTER);

        JPanel modBtns = panel(C_CARD);
        modBtns.setLayout(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton toggleBtn    = smallBtn("Enable/Disable", C_YELLOW);
        JButton deleteModBtn = smallBtn("Delete",         C_RED);
        JButton openFolderBtn= smallBtn("Open Folder",   C_ACCENT);
        JButton refreshBtn   = smallBtn("Refresh",        C_MUTED);
        modBtns.add(toggleBtn);
        modBtns.add(deleteModBtn);
        modBtns.add(openFolderBtn);
        modBtns.add(refreshBtn);
        installedPanel.add(modBtns, BorderLayout.SOUTH);

        toggleBtn.addActionListener(e -> {
            int idx = modList.getSelectedIndex();
            if (idx < 0 || cachedMods == null || idx >= cachedMods.size()) return;
            ModInfo mod = cachedMods.get(idx);
            try {
                if (mod.disabled) ModManager.enableMod(mod);
                else              ModManager.disableMod(mod);
                refreshModList();
                log((mod.disabled ? "Enabled: " : "Disabled: ") + mod.name);
            } catch (Exception ex) { log("Error: " + ex.getMessage()); }
        });

        deleteModBtn.addActionListener(e -> {
            int idx = modList.getSelectedIndex();
            if (idx < 0 || cachedMods == null || idx >= cachedMods.size()) return;
            ModInfo mod = cachedMods.get(idx);
            int confirm = JOptionPane.showConfirmDialog(this,
                "Delete " + mod.name + "?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
            try {
                ModManager.deleteMod(mod);
                refreshModList();
                log("Deleted: " + mod.fileName);
            } catch (Exception ex) { log("Error: " + ex.getMessage()); }
        });

        openFolderBtn.addActionListener(e -> {
            try {
                Files.createDirectories(Constants.MODS_DIR);
                Desktop.getDesktop().open(Constants.MODS_DIR.toFile());
            } catch (Exception ex) { log("Error: " + ex.getMessage()); }
        });

        refreshBtn.addActionListener(e -> refreshModList());

        // Right — Modrinth search
        JPanel searchPanel = panel(C_CARD);
        searchPanel.setBorder(titled("Search Modrinth"));
        searchPanel.setLayout(new BorderLayout(4, 4));

        JPanel searchBar = panel(C_CARD);
        searchBar.setLayout(new BorderLayout(4, 0));
        searchBar.setBorder(new EmptyBorder(0, 0, 6, 0));
        searchBar.add(searchField, BorderLayout.CENTER);
        JButton searchBtn = smallBtn("Search", C_PURPLE);
        searchBar.add(searchBtn, BorderLayout.EAST);
        searchPanel.add(searchBar, BorderLayout.NORTH);
        searchPanel.add(new JScrollPane(searchResultList), BorderLayout.CENTER);

        JButton installBtn = btn("⬇ Install Selected", C_GREEN);
        searchPanel.add(installBtn, BorderLayout.SOUTH);

        searchBtn.addActionListener(e -> {
            String query = searchField.getText().trim();
            if (query.isEmpty()) return;
            searchBtn.setEnabled(false);
            searchResultModel.clear();
            searchResultModel.addElement("Searching...");
            new Thread(() -> {
                try {
                    List<ModrinthResult> results = ModManager.searchModrinth(
                        query, Constants.MINECRAFT_VERSION);
                    lastSearchResults = results;
                    SwingUtilities.invokeLater(() -> {
                        searchResultModel.clear();
                        if (results.isEmpty()) {
                            searchResultModel.addElement("No results found.");
                        } else {
                            for (ModrinthResult r : results)
                                searchResultModel.addElement(r.name + " — " + r.downloads);
                        }
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        searchResultModel.clear();
                        searchResultModel.addElement("Error: " + ex.getMessage());
                    });
                } finally {
                    SwingUtilities.invokeLater(() -> searchBtn.setEnabled(true));
                }
            }, "ModSearch").start();
        });

        searchField.addActionListener(e -> searchBtn.doClick());

        installBtn.addActionListener(e -> {
            int idx = searchResultList.getSelectedIndex();
            if (idx < 0 || lastSearchResults == null || idx >= lastSearchResults.size()) return;
            ModrinthResult result = lastSearchResults.get(idx);
            installBtn.setEnabled(false);
            log("Installing: " + result.name);
            new Thread(() -> {
                try {
                    ModManager.downloadFromModrinth(result.projectId,
                        Constants.MINECRAFT_VERSION, msg -> SwingUtilities.invokeLater(() -> log(msg)));
                    SwingUtilities.invokeLater(() -> {
                        refreshModList();
                        installBtn.setEnabled(true);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        log("✗ Install failed: " + ex.getMessage());
                        installBtn.setEnabled(true);
                    });
                }
            }, "ModInstall").start();
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, installedPanel, searchPanel);
        split.setDividerLocation(320);
        split.setBackground(C_SURFACE);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TAB — SETTINGS
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildSettingsTab() {
        JPanel p = panel(C_SURFACE);
        p.setLayout(new GridBagLayout());
        p.setBorder(new EmptyBorder(14, 16, 14, 16));
        GridBagConstraints g = gbc();
        g.insets = new Insets(6, 8, 6, 8);

        // Game directory info
        g.gridy = 0; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL;
        JPanel infoBox = panel(C_CARD);
        infoBox.setBorder(titled("Paths"));
        infoBox.setLayout(new GridBagLayout());
        GridBagConstraints gi = gbc();
        gi.insets = new Insets(3, 6, 3, 6);
        String[][] paths = {
            {"Game Dir",     Constants.GAME_DIR.toString()},
            {"Versions",     Constants.VERSIONS_DIR.toString()},
            {"Mods",         Constants.MODS_DIR.toString()},
            {"Libraries",    Constants.LIBRARIES_DIR.toString()},
        };
        for (int i = 0; i < paths.length; i++) {
            gi.gridy = i; gi.gridx = 0; gi.fill = GridBagConstraints.NONE; gi.weightx = 0;
            infoBox.add(lbl(paths[i][0] + ":", C_MUTED, Font.PLAIN, 11), gi);
            gi.gridx = 1; gi.fill = GridBagConstraints.HORIZONTAL; gi.weightx = 1;
            JTextField tf = styledField(paths[i][1], 30);
            tf.setEditable(false);
            tf.setFont(new Font("Consolas", Font.PLAIN, 11));
            infoBox.add(tf, gi);
        }
        p.add(infoBox, g);

        // Open game folder button
        g.gridy = 1; g.gridwidth = 1; g.fill = GridBagConstraints.NONE; g.weightx = 0;
        g.gridx = 0;
        JButton openDir = btn("📁  Open Game Folder", C_ACCENT);
        openDir.addActionListener(e -> {
            try {
                Files.createDirectories(Constants.GAME_DIR);
                Desktop.getDesktop().open(Constants.GAME_DIR.toFile());
            } catch (Exception ex) { log("Error: " + ex.getMessage()); }
        });
        p.add(openDir, g);

        // Clear cache button
        g.gridx = 1;
        JButton clearLog = btn("🗑  Clear Console", C_YELLOW);
        clearLog.addActionListener(e -> console.setText(""));
        p.add(clearLog, g);

        // Launcher version info
        g.gridy = 2; g.gridx = 0; g.gridwidth = 2;
        JLabel about = lbl(
            "Salwyrr Launcher " + Constants.LAUNCHER_VERSION
            + "  •  Java " + System.getProperty("java.version")
            + "  •  OS: " + Constants.osName(),
            C_MUTED, Font.PLAIN, 11);
        p.add(about, g);

        return p;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  AUTH LOGIC
    // ═════════════════════════════════════════════════════════════════════════

    private void handleAddAccount() {
        String username = usernameField.getText().trim();
        String token    = tokenField.getText().trim();

        if (rbOffline.isSelected()) {
            if (username.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Enter a username.", "Missing", JOptionPane.WARNING_MESSAGE);
                return;
            }
            currentAuth = AuthManager.loginOffline(username);
            onAuthSuccess(currentAuth);
        } else if (rbMicrosoft.isSelected()) {
            startMicrosoftLogin();
        } else if (rbEasyMC.isSelected()) {
            if (token.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Enter your EasyMC token.", "Missing", JOptionPane.WARNING_MESSAGE);
                return;
            }
            new Thread(() -> {
                try {
                    AuthResult r = AuthManager.loginEasyMC(token);
                    SwingUtilities.invokeLater(() -> onAuthSuccess(r));
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        log("✗ EasyMC login failed: " + ex.getMessage());
                        JOptionPane.showMessageDialog(this, ex.getMessage(), "EasyMC Error", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }, "EasyMCLogin").start();
        } else if (rbAltening.isSelected()) {
            if (token.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Enter your TheAltening token.", "Missing", JOptionPane.WARNING_MESSAGE);
                return;
            }
            new Thread(() -> {
                try {
                    AuthResult r = AuthManager.loginAltening(token);
                    SwingUtilities.invokeLater(() -> onAuthSuccess(r));
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        log("✗ TheAltening login failed: " + ex.getMessage());
                        JOptionPane.showMessageDialog(this, ex.getMessage(), "Altening Error", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }, "AlteningLogin").start();
        }
    }

    private void onAuthSuccess(AuthResult r) {
        currentAuth = r;
        String typeLabel = r.userType.equals("msa") ? "Microsoft"
                         : r.online                 ? "Alt Token"
                                                    : "Offline";
        authStatusLabel.setText("✓  " + r.username + "  [" + typeLabel + "]");
        authStatusLabel.setForeground(C_GREEN);
        log("✓ Logged in as " + r.username + " [" + typeLabel + "]");
        refreshAccountList();
    }

    private void startMicrosoftLogin() {
        new Thread(() -> {
            SwingUtilities.invokeLater(() -> {
                authStatusLabel.setText("Checking cached token...");
                log("Checking cached Microsoft token...");
            });
            AuthResult cached = AuthManager.trySilentLogin();
            if (cached != null) {
                SwingUtilities.invokeLater(() -> onAuthSuccess(cached));
                return;
            }
            try {
                JSONObject dc      = AuthManager.requestDeviceCode();
                String userCode    = dc.getString("user_code");
                String verifyUrl   = dc.getString("verification_uri");
                String devCode     = dc.getString("device_code");
                int    interval    = dc.optInt("interval", 5);

                // Copy code to clipboard
                try {
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(userCode), null);
                } catch (Exception ignored) {}

                SwingUtilities.invokeLater(() -> {
                    log("Open: " + verifyUrl + "  |  Code: " + userCode);
                    JOptionPane.showMessageDialog(this,
                        "<html><b>Open this URL in your browser:</b><br>" + verifyUrl
                        + "<br><br><b>Code (copied to clipboard):</b>&nbsp;<code>"
                        + userCode + "</code><br><br>Waiting for you to complete login...</html>",
                        "Microsoft Login", JOptionPane.INFORMATION_MESSAGE);
                });

                AuthResult auth = null;
                long deadline = System.currentTimeMillis() + 900_000L;
                while (auth == null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(interval * 1000L);
                    auth = AuthManager.pollDeviceCode(devCode);
                }
                if (auth == null) throw new IOException("Login timed out.");
                final AuthResult fa = auth;
                SwingUtilities.invokeLater(() -> onAuthSuccess(fa));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    log("✗ Microsoft login failed: " + ex.getMessage());
                    JOptionPane.showMessageDialog(this, ex.getMessage(), "Login Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "MSLogin").start();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  DOWNLOAD & LAUNCH
    // ═════════════════════════════════════════════════════════════════════════

    // ═════════════════════════════════════════════════════════════════════════
    //  MOD LOADER INSTALL
    // ═════════════════════════════════════════════════════════════════════════

    private void syncLoaderCombo() {
        Loader current = ModLoaderInstaller.getSelectedLoader();
        switch (current) {
            case FABRIC:   loaderCombo.setSelectedIndex(1); break;
            case FORGE:    loaderCombo.setSelectedIndex(2); break;
            case NEOFORGE: loaderCombo.setSelectedIndex(3); break;
            case OPTIFINE: loaderCombo.setSelectedIndex(4); break;
            default:       loaderCombo.setSelectedIndex(0); break;
        }
    }

    private void refreshLoaderStatus() {
        Loader installed = ModLoaderInstaller.getSelectedLoader();
        boolean hasJson  = ModLoaderInstaller.getLoaderVersionJson() != null;
        if (installed == Loader.NONE || !hasJson) {
            loaderStatusLabel.setText("  No mod loader installed — click Install to add one.");
            loaderStatusLabel.setForeground(C_MUTED);
        } else {
            loaderStatusLabel.setText("  ✓ " + installed.name().charAt(0)
                + installed.name().substring(1).toLowerCase()
                + " installed for MC " + Constants.MINECRAFT_VERSION);
            loaderStatusLabel.setForeground(C_GREEN);
        }
        syncLoaderCombo();
    }

    private void installSelectedLoader() {
        int idx = loaderCombo.getSelectedIndex();
        Loader loader;
        switch (idx) {
            case 1: loader = Loader.FABRIC;   break;
            case 2: loader = Loader.FORGE;    break;
            case 3: loader = Loader.NEOFORGE; break;
            case 4: loader = Loader.OPTIFINE; break;
            default: loader = Loader.NONE;    break;
        }

        if (loader == Loader.NONE) {
            try {
                ModLoaderInstaller.install(Loader.NONE, msg -> log(msg));
                refreshLoaderStatus();
            } catch (Exception ex) {
                log("✗ " + ex.getMessage());
            }
            return;
        }

        if (!Files.exists(Constants.clientJar())) {
            JOptionPane.showMessageDialog(this,
                "Download Minecraft first before installing a mod loader.",
                "Not Downloaded", JOptionPane.WARNING_MESSAGE);
            return;
        }

        setButtons(false);
        installLoaderBtn.setEnabled(false);
        progressBar.setValue(0);
        progressBar.setString("Installing " + loader.name() + "...");
        progressBar.setForeground(C_TEAL);
        log("");
        log("═══ Installing " + loader.name() + " for MC " + Constants.MINECRAFT_VERSION + " ═══");

        final Loader finalLoader = loader;
        new Thread(() -> {
            try {
                ModLoaderInstaller.install(finalLoader,
                    msg -> SwingUtilities.invokeLater(() -> log(msg)));
                SwingUtilities.invokeLater(() -> {
                    log("═══ " + finalLoader.name() + " installation complete! ═══");
                    progressBar.setString("Loader Ready");
                    progressBar.setForeground(C_GREEN);
                    refreshLoaderStatus();
                    refreshModList();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    log("✗ Loader install failed: " + ex.getMessage());
                    progressBar.setString("Error");
                    progressBar.setForeground(C_RED);
                    JOptionPane.showMessageDialog(this, ex.getMessage(),
                        "Loader Install Error", JOptionPane.ERROR_MESSAGE);
                });
            } finally {
                SwingUtilities.invokeLater(() -> {
                    setButtons(true);
                    installLoaderBtn.setEnabled(true);
                });
            }
        }, "LoaderInstaller").start();
    }

    private void startDownload() {
        setButtons(false);
        progressBar.setValue(0);
        progressBar.setString("Starting...");
        progressBar.setForeground(C_ACCENT);
        log("");
        log("═══ Downloading Minecraft " + Constants.MINECRAFT_VERSION + " ═══");

        new Thread(() -> {
            try {
                MinecraftDownloader dl = new MinecraftDownloader(
                    msg -> SwingUtilities.invokeLater(() -> log(msg)),
                    pct -> SwingUtilities.invokeLater(() -> {
                        progressBar.setValue(pct);
                        progressBar.setString(pct + "%");
                    }));
                dl.downloadAll();
                SwingUtilities.invokeLater(() -> {
                    progressBar.setString("Complete!");
                    progressBar.setForeground(C_GREEN);
                    log("═══ Download complete! ═══");
                    refreshModList();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    log("✗ Download failed: " + ex.getMessage());
                    progressBar.setString("Error");
                    progressBar.setForeground(C_RED);
                    JOptionPane.showMessageDialog(this, ex.getMessage(), "Download Error", JOptionPane.ERROR_MESSAGE);
                });
            } finally {
                SwingUtilities.invokeLater(() -> setButtons(true));
            }
        }, "Downloader").start();
    }

    private void startGame() {
        if (currentAuth == null) {
            JOptionPane.showMessageDialog(this,
                "No account selected.\nGo to the Accounts tab and login first.",
                "No Account", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!Files.exists(Constants.clientJar())) {
            JOptionPane.showMessageDialog(this,
                "Minecraft not downloaded.\nClick Download / Update first.",
                "Not Downloaded", JOptionPane.WARNING_MESSAGE);
            return;
        }
        setButtons(false);
        progressBar.setString("Launching...");
        progressBar.setForeground(C_ACCENT);
        log("");
        log("═══ Launching as " + currentAuth.username + " ═══");

        String serverArg = serverField.getText().trim();
        int ram = ramSlider.getValue();

        new Thread(() -> {
            try {
                GameLauncher launcher = new GameLauncher(
                    msg -> SwingUtilities.invokeLater(() -> log(msg)));
                Process proc = launcher.launch(currentAuth, ram, serverArg.isEmpty() ? null : serverArg);
                SwingUtilities.invokeLater(() -> {
                    log("✓ Minecraft launched! PID: " + proc.pid());
                    progressBar.setString("Running");
                    progressBar.setForeground(C_GREEN);
                    statusLabel.setText("  Running MC " + Constants.MINECRAFT_VERSION
                        + " as " + currentAuth.username);
                });

                new Thread(() -> {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(proc.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            final String l = line;
                            SwingUtilities.invokeLater(() -> log(l));
                        }
                    } catch (IOException ignored) {}
                    int code;
                    try { code = proc.waitFor(); } catch (Exception e) { code = -1; }
                    final int exitCode = code;
                    SwingUtilities.invokeLater(() -> {
                        log("Game exited with code " + exitCode);
                        progressBar.setString("Exited (" + exitCode + ")");
                        progressBar.setForeground(exitCode == 0 ? C_MUTED : C_RED);
                        statusLabel.setText("  " + Constants.GAME_DIR);
                        setButtons(true);
                    });
                }, "GameOutput").start();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    log("✗ Launch failed: " + ex.getMessage());
                    progressBar.setString("Launch Error");
                    progressBar.setForeground(C_RED);
                    JOptionPane.showMessageDialog(this, ex.getMessage(), "Launch Error", JOptionPane.ERROR_MESSAGE);
                    setButtons(true);
                });
            }
        }, "Launcher").start();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private void refreshAccountList() {
        accountListModel.clear();
        for (AccountStore.Account a : AccountStore.load())
            accountListModel.addElement(a.toString());
    }

    private void refreshModList() {
        new Thread(() -> {
            List<ModInfo> mods = ModManager.listMods();
            cachedMods = mods;
            SwingUtilities.invokeLater(() -> {
                modListModel.clear();
                if (mods.isEmpty()) {
                    modListModel.addElement("No mods installed.");
                } else {
                    for (ModInfo m : mods)
                        modListModel.addElement(
                            (m.disabled ? "◉ " : "● ") + m.name + " v" + m.version
                            + "  [" + m.loader + "]");
                }
            });
        }, "ModRefresh").start();
    }

    private void log(String msg) {
        console.append(msg + "\n");
        JScrollBar sb = ((JScrollPane) console.getParent().getParent()).getVerticalScrollBar();
        if (sb.getValue() + sb.getVisibleAmount() >= sb.getMaximum() - 60)
            console.setCaretPosition(console.getDocument().getLength());
    }

    private void setButtons(boolean enabled) {
        downloadBtn.setEnabled(enabled);
        playBtn.setEnabled(enabled);
        installLoaderBtn.setEnabled(enabled);
    }

    // Prefs
    private int loadRam(int def) {
        try {
            if (Files.exists(Constants.PREFS_FILE)) {
                Properties p = new Properties();
                p.load(Files.newInputStream(Constants.PREFS_FILE));
                return Integer.parseInt(p.getProperty("ram", String.valueOf(def)));
            }
        } catch (Exception ignored) {}
        return def;
    }

    private void saveRam(int ram) {
        try {
            Properties p = new Properties();
            if (Files.exists(Constants.PREFS_FILE))
                p.load(Files.newInputStream(Constants.PREFS_FILE));
            p.setProperty("ram", String.valueOf(ram));
            Files.createDirectories(Constants.PREFS_FILE.getParent());
            p.store(Files.newOutputStream(Constants.PREFS_FILE), "Salwyrr Launcher");
        } catch (Exception ignored) {}
    }

    // ── Widget factories ──────────────────────────────────────────────────────

    private static JPanel panel(Color bg) {
        JPanel p = new JPanel(); p.setBackground(bg); return p;
    }

    private static JLabel lbl(String t, Color fg, int style, int size) {
        JLabel l = new JLabel(t);
        l.setForeground(fg);
        l.setFont(new Font("Segoe UI", style, size));
        return l;
    }

    private static JRadioButton radio(String text, boolean sel) {
        JRadioButton rb = new JRadioButton(text, sel);
        rb.setForeground(C_TEXT);
        rb.setBackground(C_CARD);
        rb.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        rb.setFocusPainted(false);
        return rb;
    }

    private static JTextField styledField(String text, int cols) {
        JTextField f = new JTextField(text, cols);
        f.setBackground(new Color(38, 38, 54));
        f.setForeground(C_TEXT);
        f.setCaretColor(C_TEXT);
        f.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER),
            new EmptyBorder(4, 6, 4, 6)));
        return f;
    }

    private static <T> JList<T> styledList(DefaultListModel<T> model) {
        JList<T> l = new JList<>(model);
        l.setBackground(new Color(20, 20, 30));
        l.setForeground(C_TEXT);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        l.setSelectionBackground(new Color(60, 60, 100));
        l.setSelectionForeground(Color.WHITE);
        l.setFixedCellHeight(24);
        return l;
    }

    private static JButton btn(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setBorder(new EmptyBorder(8, 18, 8, 18));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setOpaque(true);
        return b;
    }

    private static JButton smallBtn(String text, Color fg) {
        JButton b = new JButton(text);
        b.setBackground(C_CARD);
        b.setForeground(fg);
        b.setFocusPainted(false);
        b.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        b.setBorder(new EmptyBorder(4, 10, 4, 10));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setOpaque(true);
        return b;
    }

    private static Border titled(String title) {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(C_BORDER), title,
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Segoe UI", Font.PLAIN, 11), C_MUTED),
            new EmptyBorder(4, 8, 8, 8));
    }

    private static GridBagConstraints gbc() {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = 0;
        g.insets = new Insets(5, 8, 5, 8);
        g.anchor = GridBagConstraints.WEST;
        return g;
    }
}
