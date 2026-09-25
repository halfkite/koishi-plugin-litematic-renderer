package dev.qqbot.gpuagent;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class AgentFrame extends JFrame {
    private final Path root;
    private final Path configPath;
    private final AgentConfig config;
    private final RenderService renderer;
    private final HttpV1Server httpServer;
    private final CloudConnection cloud;
    private final BotManager bots;
    private final WebAdminServer web;
    private final MemoryWatchdog watchdog;
    private final JTextArea logs = new JTextArea();
    private final JLabel runtimeStatus = new JLabel("运行时：未启动");
    private final JLabel memoryStatus = new JLabel("内存：-");
    private final JLabel cloudStatus = new JLabel("云端：等待连接");
    private final JLabel currentTaskLabel = new JLabel("当前渲染：无");
    private final JProgressBar runtimeInstallProgress = new JProgressBar(0, 100);
    private final ViewTableModel views = new ViewTableModel();
    private final JTable viewTable = new JTable(views);
    private final JTextField inputFile = new JTextField();
    private final JTextField outputDirectory = new JTextField();
    private final DefaultListModel<Path> projectionModel = new DefaultListModel<>();
    private final JList<Path> projectionList = new JList<>(projectionModel);
    private final List<Component> projectionDropTargets = new ArrayList<>();
    private final PreviewPanel preview;
    private final DefaultTableModel history = new DefaultTableModel(new String[] {"时间", "文件", "视角", "耗时", "状态"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final DefaultListModel<AgentConfig.ResourcePackEntry> packModel = new DefaultListModel<>();
    private final DefaultTableModel announcementModel = new DefaultTableModel(
            new String[] {"发送", "机器人账号", "类型", "群标识", "群名称", "连接", "结果"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
    };
    private final List<AnnouncementGroup> announcementRows = new ArrayList<>();
    private final JTextArea announcementText = new JTextArea();
    private final JTextField announcementLegacyPath = new JTextField();
    private final JLabel announcementStatus = new JLabel("待发送");
    private final DefaultTableModel scheduledAnnouncementModel = new DefaultTableModel(
            new String[] {"发送时间", "状态", "成功", "失败", "公告摘要"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final List<AnnouncementSchedules.Job> scheduledAnnouncementRows = new ArrayList<>();
    private final AtomicBoolean announcementSending = new AtomicBoolean();
    private final AtomicBoolean announcementRefreshing = new AtomicBoolean();
    private final java.util.ArrayList<String> historyLocations = new java.util.ArrayList<>();
    private TrayIcon trayIcon;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final AtomicBoolean servicesClosed = new AtomicBoolean();

    AgentFrame(Path root, Path configPath, AgentConfig config) {
        super("Litematic GPU Agent");
        this.root = root; this.configPath = configPath; this.config = config;
        this.renderer = new RenderService(root, config);
        this.renderer.setLog(this::log);
        this.renderer.runtime().setInstallProgress(progress -> SwingUtilities.invokeLater(() -> updateRuntimeInstallProgress(progress)));
        this.httpServer = new HttpV1Server(config, renderer, this::log);
        this.cloud = new CloudConnection(config, renderer, this::log);
        this.bots = new BotManager(root, configPath, config, renderer, this::log);
        this.bots.setDownloadProgress(progress -> SwingUtilities.invokeLater(() -> updateAttachmentDownloadProgress(progress)));
        this.web = new WebAdminServer(root, configPath, config, renderer, bots, cloud, this::log);
        this.watchdog = new MemoryWatchdog(config, renderer, renderer.runtime(), root, this::log);
        this.preview = new PreviewPanel(renderer, renderer.runtime(), config,
                () -> outputDirectory.getText(), this::log, this::showError,
                path -> {
                    inputFile.setText(path.toString());
                    if (outputDirectory.getText().isBlank())
                        outputDirectory.setText(path.getParent().resolve("渲染结果").toString());
                });
        this.watchdog.start();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(900, 640));
        setSize(1080, 760);
        setLocationRelativeTo(null);
        setContentPane(buildUi());
        installDropTarget();
        installTray();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { confirmWindowClose(); }
        });
        for (var pack : config.resourcePacks) packModel.addElement(pack);
        if (config.outputDirectory != null && !config.outputDirectory.isBlank()) outputDirectory.setText(config.outputDirectory);
        for (AgentConfig.HistoryEntry entry : config.history) {
            historyLocations.add(entry.location());
            history.addRow(new Object[] {entry.time(), entry.file(), entry.views(), entry.elapsed() + " ms", entry.status()});
        }
        views.setOnChange(this::syncViews);
        renderer.setHistoryListener(record -> {
            AgentConfig.HistoryEntry entry = new AgentConfig.HistoryEntry(
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")),
                    record.file(), record.views(), record.elapsedMillis(), record.status(), record.location());
            config.history.add(0, entry);
            while (config.history.size() > 200) config.history.remove(config.history.size() - 1);
            try { config.save(configPath); } catch (Exception ignored) {}
            addHistory(record.file(), record.views(), record.elapsedMillis(), record.status(), record.location());
        });
        log("渲染缓存目录：" + renderer.cacheDirectory().toAbsolutePath());
        if (config.views != null && !config.views.isEmpty()) {
            List<RenderModels.View> loaded = new ArrayList<>();
            for (AgentConfig.ViewEntry entry : config.views) {
                loaded.add(new RenderModels.View(entry.id(), entry.name(), entry.yaw(), entry.pitch(), entry.zoom(), entry.autoFillEnabled(), entry.width(), entry.height(), entry.background(), entry.transparentBackground(), entry.supersampling(), entry.brightnessFactor()));
            }
            views.reset(loaded);
        } else if (config.renderWidth > 0 && config.renderHeight > 0) {
            views.applyResolutionToAll(config.renderWidth, config.renderHeight);
        }
        new Timer(1000, event -> refreshStatus()).start();
        Thread.startVirtualThread(() -> {
            // 自动重启交接时旧实例可能还占着端口，最多重试 30 秒
            for (int attempt = 1; attempt <= 30; attempt++) {
                try { httpServer.start(); break; }
                catch (Exception error) {
                    if (attempt == 30) log("HTTP v1 启动失败（重试 30 秒后放弃）：" + error.getMessage());
                    else { log("HTTP v1 端口被占用（第 " + attempt + " 次重试）：" + error.getMessage()); try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; } }
                }
            }
            cloud.start();
            bots.start();
            if (config.webEnabled) {
                try { web.start(); }
                catch (Exception error) { log("Web 管理后台启动失败：" + error.getMessage()); }
            }
        });
    }

    private JComponent buildUi() {
        JPanel rootPanel = new JPanel(new BorderLayout());
        JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 6));
        runtimeInstallProgress.setStringPainted(true); runtimeInstallProgress.setString("客户端未下载"); runtimeInstallProgress.setPreferredSize(new Dimension(170, 20));
        status.add(runtimeStatus); status.add(runtimeInstallProgress); status.add(cloudStatus); status.add(currentTaskLabel); status.add(memoryStatus); status.add(new JLabel("Minecraft 26.3 / Java 25"));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        actions.add(new JLabel("工具版本 " + Main.VERSION));
        JButton openConfig = new JButton("打开配置文件");
        openConfig.addActionListener(e -> openConfigFile());
        actions.add(openConfig);
        JButton saveReload = new JButton("保存并重载配置");
        saveReload.setToolTipText("保存当前配置并重载云端与机器人连接");
        saveReload.addActionListener(e -> saveAndReloadConfiguration());
        actions.add(saveReload);
        JButton restartApp = new JButton("重启程序");
        restartApp.addActionListener(e -> restartApplication(false));
        actions.add(restartApp);
        JButton exitApp = new JButton("退出程序");
        exitApp.addActionListener(e -> confirmExit());
        actions.add(exitApp);
        JPanel header = new JPanel(new BorderLayout());
        header.add(status, BorderLayout.WEST);
        header.add(actions, BorderLayout.EAST);
        rootPanel.add(header, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("本地渲染", localPanel()); tabs.addTab("渲染设置", renderSettingsPanel()); tabs.addTab("预览", preview);
        tabs.addTab("机器人账号", botPanel());
        JComponent announcement = announcementPanel();
        tabs.addTab("发送公告", announcement);
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == announcement) {
                refreshAnnouncementGroups();
                refreshScheduledAnnouncements();
            }
        });
        tabs.addTab("任务历史", historyPanel());
        tabs.addTab("资源包", resourcePackPanel()); tabs.addTab("连接设置", settingsPanel()); tabs.addTab("日志", logPanel());
        rootPanel.add(tabs, BorderLayout.CENTER);
        return rootPanel;
    }

    private JComponent announcementPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JTable table = new JTable(announcementModel);
        table.setFillsViewportHeight(true);
        table.setRowHeight(27);
        table.getColumnModel().getColumn(0).setMaxWidth(55);
        table.getColumnModel().getColumn(2).setPreferredWidth(70);
        table.getColumnModel().getColumn(3).setPreferredWidth(250);
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton refresh = new JButton("刷新群列表");
        refresh.addActionListener(e -> refreshAnnouncementGroups());
        JButton add = new JButton("添加群标识");
        add.addActionListener(e -> addAnnouncementGroup());
        JButton remove = new JButton("移除已保存群");
        remove.addActionListener(e -> {
            if (announcementSending.get() || announcementRefreshing.get()) return;
            int row = table.getSelectedRow();
            if (row < 0 || row >= announcementRows.size()) return;
            AnnouncementGroup group = announcementRows.get(table.convertRowIndexToModel(row));
            if (!bots.isSavedAnnouncementGroup(group.profileId(), group.groupId())) {
                JOptionPane.showMessageDialog(this, "该群来自 OneBot 实时群列表，取消勾选即可跳过本次发送。");
                return;
            }
            bots.removeAnnouncementGroup(group.profileId(), group.groupId());
            refreshAnnouncementGroups();
        });
        toolbar.add(refresh); toolbar.add(add); toolbar.add(remove);
        JPanel top = new JPanel(new BorderLayout(4, 8));
        top.add(toolbar, BorderLayout.NORTH);
        announcementLegacyPath.setText(config.announcementLegacyIndexPath == null || config.announcementLegacyIndexPath.isBlank()
                ? root.resolve("rendered-message-index.json5").toString() : config.announcementLegacyIndexPath);
        JPanel oldCache = new JPanel(new BorderLayout(6, 0));
        oldCache.add(new JLabel("旧缓存位置"), BorderLayout.WEST);
        oldCache.add(announcementLegacyPath, BorderLayout.CENTER);
        JPanel oldCacheActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton chooseOldCache = new JButton("选择...");
        chooseOldCache.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("选择旧渲染消息索引或所在目录");
            chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            Path current = Path.of(announcementLegacyPath.getText().trim());
            if (Files.exists(current)) chooser.setSelectedFile(current.toFile());
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                announcementLegacyPath.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        JButton readOldCache = new JButton("读取旧缓存");
        readOldCache.addActionListener(e -> importAnnouncementGroups(readOldCache));
        oldCacheActions.add(chooseOldCache); oldCacheActions.add(readOldCache);
        oldCache.add(oldCacheActions, BorderLayout.EAST);
        top.add(oldCache, BorderLayout.SOUTH);
        panel.add(top, BorderLayout.NORTH);

        JScrollPane groups = new JScrollPane(table);
        groups.setBorder(BorderFactory.createTitledBorder("公告目标群"));
        announcementText.setLineWrap(true);
        announcementText.setWrapStyleWord(true);
        announcementText.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        JScrollPane editor = new JScrollPane(announcementText);
        editor.setBorder(BorderFactory.createTitledBorder("公告内容"));
        JPanel compose = new JPanel(new BorderLayout(5, 5));
        compose.add(editor, BorderLayout.CENTER);
        JPanel scheduleControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        scheduleControls.add(new JLabel("发送时间"));
        JSpinner scheduleTime = new JSpinner(new SpinnerDateModel(
                new java.util.Date(System.currentTimeMillis() + 60 * 60 * 1000), null, null,
                java.util.Calendar.MINUTE));
        scheduleTime.setEditor(new JSpinner.DateEditor(scheduleTime, "yyyy-MM-dd HH:mm"));
        scheduleControls.add(scheduleTime);
        JButton schedule = new JButton("添加定时发送");
        scheduleControls.add(schedule);
        scheduleControls.add(new JLabel("单次；发送前刷新群列表"));
        compose.add(scheduleControls, BorderLayout.SOUTH);

        JTable scheduledTable = new JTable(scheduledAnnouncementModel);
        scheduledTable.setFillsViewportHeight(true);
        JPanel scheduleList = new JPanel(new BorderLayout(5, 5));
        scheduleList.add(new JScrollPane(scheduledTable), BorderLayout.CENTER);
        JPanel scheduleActions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancelSchedule = new JButton("取消选中任务");
        cancelSchedule.addActionListener(e -> cancelScheduledAnnouncement(scheduledTable));
        JButton refreshSchedules = new JButton("刷新任务");
        refreshSchedules.addActionListener(e -> refreshScheduledAnnouncements());
        scheduleActions.add(refreshSchedules); scheduleActions.add(cancelSchedule);
        scheduleList.add(scheduleActions, BorderLayout.SOUTH);
        JTabbedPane announcementTabs = new JTabbedPane();
        announcementTabs.addTab("编辑公告", compose);
        announcementTabs.addTab("定时任务", scheduleList);
        schedule.addActionListener(e -> scheduleAnnouncement(scheduleTime, announcementTabs));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, groups, announcementTabs);
        split.setResizeWeight(0.5);
        split.setDividerLocation(245);
        panel.add(split, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        JLabel note = new JLabel("<html>OneBot 自动获取群列表；官方 QQ 需已收到群事件或手动填写该账号的群 OpenID。<br>主动发送仍受平台权限和频次限制。</html>");
        footer.add(note, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton send = new JButton("发送公告");
        send.addActionListener(e -> sendAnnouncement(send));
        buttons.add(announcementStatus); buttons.add(send);
        footer.add(buttons, BorderLayout.SOUTH);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private void refreshAnnouncementGroups() {
        if (announcementSending.get() || !announcementRefreshing.compareAndSet(false, true)) return;
        announcementStatus.setText("正在获取群列表...");
        Thread.startVirtualThread(() -> {
            try {
                List<AnnouncementGroup> groups = bots.listAnnouncementGroups();
                SwingUtilities.invokeLater(() -> {
                    java.util.Map<String, Boolean> selections = new java.util.HashMap<>();
                    for (int i = 0; i < announcementRows.size(); i++)
                        selections.put(announcementRows.get(i).profileId() + "/" + announcementRows.get(i).groupId(),
                                Boolean.TRUE.equals(announcementModel.getValueAt(i, 0)));
                    announcementRows.clear();
                    announcementModel.setRowCount(0);
                    for (AnnouncementGroup group : groups) {
                        announcementRows.add(group);
                        String key = group.profileId() + "/" + group.groupId();
                        announcementModel.addRow(new Object[] {selections.getOrDefault(key, group.connected()),
                                group.profileName(), group.type(), group.groupId(), group.groupName(),
                                group.connected() ? "已连接" : "未连接", ""});
                    }
                    announcementStatus.setText("共 " + groups.size() + " 个目标群");
                    announcementRefreshing.set(false);
                });
            } catch (Throwable error) {
                SwingUtilities.invokeLater(() -> {
                    announcementRefreshing.set(false);
                    announcementStatus.setText("获取群列表失败"); showError(error);
                });
            }
        });
    }

    private void saveAnnouncementLegacyPath() throws Exception {
        String value = announcementLegacyPath.getText().trim();
        if (!value.isBlank()) Path.of(value);
        config.announcementLegacyIndexPath = value;
        config.save(configPath);
    }

    private void importAnnouncementGroups(JButton button) {
        if (announcementSending.get() || announcementRefreshing.get()) return;
        try { saveAnnouncementLegacyPath(); }
        catch (Exception error) { showError(error); return; }
        button.setEnabled(false);
        announcementStatus.setText("正在读取旧缓存...");
        Thread.startVirtualThread(() -> {
            try {
                AnnouncementGroups.ImportResult result = bots.importAnnouncementGroupsFromLegacy();
                SwingUtilities.invokeLater(() -> {
                    button.setEnabled(true);
                    refreshAnnouncementGroups();
                    JOptionPane.showMessageDialog(this, "找到 " + result.matchingRecords()
                            + " 条群消息记录，新增 " + result.addedGroups() + " 个群标识。",
                            "读取完成", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Throwable error) {
                SwingUtilities.invokeLater(() -> {
                    button.setEnabled(true);
                    announcementStatus.setText("旧缓存读取失败");
                    showError(error);
                });
            }
        });
    }

    private void refreshScheduledAnnouncements() {
        scheduledAnnouncementRows.clear();
        scheduledAnnouncementModel.setRowCount(0);
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (AnnouncementSchedules.Job job : bots.scheduledAnnouncements()) {
            scheduledAnnouncementRows.add(job);
            String state = switch (job.state()) {
                case "pending" -> "待发送";
                case "running" -> "发送中";
                case "completed" -> "已完成";
                case "partial" -> "部分失败";
                case "cancelled" -> "已取消";
                case "interrupted" -> "已中断";
                default -> job.state();
            };
            String summary = job.content().replace('\n', ' ').replace('\r', ' ');
            if (summary.length() > 60) summary = summary.substring(0, 60) + "...";
            scheduledAnnouncementModel.addRow(new Object[] {
                    java.time.Instant.ofEpochMilli(job.runAtMillis()).atZone(java.time.ZoneId.systemDefault()).format(format),
                    state, job.succeeded(), job.failed(), summary});
        }
    }

    private void scheduleAnnouncement(JSpinner time, JTabbedPane tabs) {
        try {
            saveAnnouncementLegacyPath();
            long when = ((java.util.Date) time.getValue()).getTime();
            String content = announcementText.getText();
            if (content == null || content.isBlank() || content.length() > 2000)
                throw new IllegalArgumentException("公告内容须为 1 至 2000 字符");
            String date = java.time.Instant.ofEpochMilli(when).atZone(java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            if (JOptionPane.showConfirmDialog(this, "将在 " + date + " 向届时可用的群发送一次公告。\n"
                    + "发送前会读取旧缓存并刷新群列表。", "确认定时发送", JOptionPane.OK_CANCEL_OPTION)
                    != JOptionPane.OK_OPTION) return;
            bots.scheduleAnnouncement(when, content);
            refreshScheduledAnnouncements();
            tabs.setSelectedIndex(1);
        } catch (Throwable error) { showError(error); }
    }

    private void cancelScheduledAnnouncement(JTable table) {
        int row = table.getSelectedRow();
        if (row < 0 || row >= scheduledAnnouncementRows.size()) return;
        AnnouncementSchedules.Job job = scheduledAnnouncementRows.get(table.convertRowIndexToModel(row));
        if (!"pending".equals(job.state())) {
            JOptionPane.showMessageDialog(this, "只能取消尚未开始的定时公告。");
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "取消这条定时公告？", "确认取消",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            bots.cancelScheduledAnnouncement(job.id());
            refreshScheduledAnnouncements();
        } catch (Throwable error) { showError(error); }
    }

    private void addAnnouncementGroup() {
        if (announcementSending.get() || announcementRefreshing.get()) return;
        List<AgentConfig.BotProfile> profiles = config.botProfiles == null ? List.of()
                : config.botProfiles.stream().filter(profile -> profile != null && profile.enabled).toList();
        if (profiles.isEmpty()) { JOptionPane.showMessageDialog(this, "请先启用机器人账号。"); return; }
        JComboBox<AgentConfig.BotProfile> accounts = new JComboBox<>(profiles.toArray(AgentConfig.BotProfile[]::new));
        accounts.setRenderer((list, value, index, selected, focused) -> new JLabel(
                value == null ? "" : value.name + " (" + value.type + ")"));
        JTextField groupId = new JTextField(32);
        JPanel form = new JPanel(new GridLayout(2, 2, 8, 8));
        form.add(new JLabel("机器人账号")); form.add(accounts);
        form.add(new JLabel("群 OpenID / 群号")); form.add(groupId);
        if (JOptionPane.showConfirmDialog(this, form, "添加公告目标群", JOptionPane.OK_CANCEL_OPTION)
                != JOptionPane.OK_OPTION) return;
        try {
            AgentConfig.BotProfile profile = (AgentConfig.BotProfile) accounts.getSelectedItem();
            if (profile == null) return;
            bots.addAnnouncementGroup(profile.id, groupId.getText());
            refreshAnnouncementGroups();
        } catch (Throwable error) { showError(error); }
    }

    private void sendAnnouncement(JButton sendButton) {
        if (announcementSending.get() || announcementRefreshing.get()) return;
        String content = announcementText.getText();
        if (content == null || content.isBlank() || content.length() > 2000) {
            JOptionPane.showMessageDialog(this, "公告内容须为 1 至 2000 字符。");
            return;
        }
        List<AnnouncementGroup> selected = new ArrayList<>();
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < announcementRows.size(); i++) {
            if (Boolean.TRUE.equals(announcementModel.getValueAt(i, 0))) {
                selected.add(announcementRows.get(i)); rows.add(i);
            }
        }
        if (selected.isEmpty()) { JOptionPane.showMessageDialog(this, "请先选择要发送的群。"); return; }
        String preview = content.length() > 120 ? content.substring(0, 120) + "..." : content;
        int answer = JOptionPane.showConfirmDialog(this,
                "向 " + selected.size() + " 个群发送公告？\n\n" + preview,
                "确认发送公告", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION || !announcementSending.compareAndSet(false, true)) return;
        sendButton.setEnabled(false);
        announcementStatus.setText("正在发送 0/" + selected.size());
        Thread.startVirtualThread(() -> {
            int succeeded = 0;
            try {
                for (int i = 0; i < selected.size(); i++) {
                    String result;
                    try {
                        bots.sendAnnouncement(selected.get(i), content);
                        succeeded++;
                        result = "成功";
                    } catch (Throwable error) {
                        result = "失败：" + String.valueOf(error.getMessage());
                        log("公告发送失败：" + selected.get(i).profileName() + " / "
                                + selected.get(i).groupId() + "：" + error.getMessage());
                    }
                    final int row = rows.get(i);
                    final String rowResult = result;
                    final int completed = i + 1;
                    SwingUtilities.invokeLater(() -> {
                        announcementModel.setValueAt(rowResult, row, 6);
                        announcementStatus.setText("正在发送 " + completed + "/" + selected.size());
                    });
                    if (i + 1 < selected.size()) Thread.sleep(1000);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                int successCount = succeeded;
                SwingUtilities.invokeLater(() -> {
                    announcementSending.set(false);
                    sendButton.setEnabled(true);
                    announcementStatus.setText("完成 " + successCount + "/" + selected.size());
                    JOptionPane.showMessageDialog(this, "公告发送完成：成功 " + successCount + " 个，失败 "
                            + (selected.size() - successCount) + " 个。", "发送结果", JOptionPane.INFORMATION_MESSAGE);
                });
            }
        });
    }

    private JComponent localPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8)); panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel files = new JPanel(new GridBagLayout()); GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(3,3,3,3); c.fill = GridBagConstraints.HORIZONTAL;
        JButton inputOpen = new JButton("打开位置"); inputOpen.addActionListener(e -> openLocation(inputFile.getText()));
        JButton inputHistory = new JButton(); inputHistory.addActionListener(e -> showRecentMenu(inputFile, config.recentProjectionPaths, this::setInputPath));
        JButton chooseInput = new JButton("导入投影"); chooseInput.addActionListener(e -> chooseInputFiles());
        JPanel inputButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0)); inputButtons.add(inputOpen); inputButtons.add(chooseInput);
        projectionDropTargets.add(chooseInput);
        c.gridx=0; c.gridy=0; c.weightx=0; files.add(new JLabel("当前投影"), c); c.gridx=1; c.weightx=1; files.add(fieldWithArrow(inputFile, inputHistory),c);
        c.gridx=2; c.weightx=0; files.add(inputButtons,c);
        JButton outputOpen = new JButton("打开位置"); outputOpen.addActionListener(e -> openLocation(outputDirectory.getText()));
        JButton outputHistory = new JButton(); outputHistory.addActionListener(e -> showRecentMenu(outputDirectory, config.recentOutputDirectories, this::setOutputPath));
        JButton chooseOutput = new JButton("选择..."); chooseOutput.addActionListener(e -> chooseOutput());
        JPanel outputButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0)); outputButtons.add(outputOpen); outputButtons.add(chooseOutput);
        c.gridx=0; c.gridy=1; files.add(new JLabel("输出目录"),c); c.gridx=1; c.weightx=1; files.add(fieldWithArrow(outputDirectory, outputHistory),c);
        c.gridx=2; c.weightx=0; files.add(outputButtons,c);
        panel.add(files, BorderLayout.NORTH);

        projectionList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        projectionList.setCellRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value == null ? "" : value.getFileName().toString());
            label.setToolTipText(value == null ? null : value.toAbsolutePath().toString());
            label.setOpaque(true); label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6)); return label;
        });
        projectionList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            Path selected = projectionList.getSelectedValue();
            if (selected != null) selectProjection(selected);
        });
        JPanel projectionPanel = new JPanel(new BorderLayout(4, 4)); projectionPanel.setBorder(BorderFactory.createTitledBorder("已导入投影"));
        projectionPanel.add(new JScrollPane(projectionList), BorderLayout.CENTER);
        JPanel projectionTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton importProjection = new JButton("导入投影"); importProjection.addActionListener(e -> chooseInputFiles());
        projectionDropTargets.add(importProjection);
        JButton removeProjection = new JButton("移除选中"); removeProjection.addActionListener(e -> removeSelectedProjections());
        JButton clearProjections = new JButton("清空"); clearProjections.addActionListener(e -> clearImportedProjections());
        projectionTools.add(importProjection); projectionTools.add(removeProjection); projectionTools.add(clearProjections);
        projectionPanel.add(projectionTools, BorderLayout.SOUTH);
        viewTable.setFillsViewportHeight(true); viewTable.putClientProperty("terminateEditOnFocusLost", true);
        viewTable.getColumnModel().getColumn(1).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            private boolean arrow;
            @Override public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int column) {
                this.arrow = selected;
                java.awt.Component component = super.getTableCellRendererComponent(table, value, selected, focused, row, column);
                ((javax.swing.JLabel) component).setText(value == null ? "" : value.toString());
                return component;
            }
            @Override public void paintComponent(java.awt.Graphics g) {
                super.paintComponent(g);
                if (this.arrow) g.drawString("▾", getWidth() - 14, getHeight() / 2 + 5);
            }
        });
        viewTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent event) {
                int row = viewTable.rowAtPoint(event.getPoint());
                int column = viewTable.columnAtPoint(event.getPoint());
                if (row < 0 || column != 1 || event.getClickCount() != 1) return;
                java.awt.Rectangle cell = viewTable.getCellRect(row, column, false);
                if (cell.width - (event.getX() - cell.x) <= 24) {
                    viewTable.setRowSelectionInterval(row, row);
                    showPresetMenu(viewTable, event.getX(), event.getY(), row);
                }
            }
        });
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, projectionPanel, new JScrollPane(viewTable));
        center.setResizeWeight(0.25); center.setDividerLocation(130); center.setBorder(null);
        panel.add(center, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton add = new JButton("添加视角"); add.addActionListener(e -> views.addView());
        JButton remove = new JButton("删除视角"); remove.addActionListener(e -> views.remove(viewTable.getSelectedRow()));
        JButton render = new JButton("开始渲染"); render.addActionListener(e -> renderLocal(render));
        JButton cancel = new JButton("终止任务"); cancel.addActionListener(e -> renderer.cancelAll());
        buttons.add(add); buttons.add(remove); buttons.add(render); buttons.add(cancel); panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent renderSettingsPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;

        JTextField widthField = new JTextField(String.valueOf(Math.max(64, Math.min(4096, config.renderWidth))), 6);
        JTextField heightField = new JTextField(String.valueOf(Math.max(64, Math.min(4096, config.renderHeight))), 6);
        JButton applyResolution = new JButton("应用到全部视角");
        java.awt.event.ActionListener applyResolutionAction = event -> {
            try {
                int width = Integer.parseInt(widthField.getText().trim());
                int height = Integer.parseInt(heightField.getText().trim());
                if (width < 64 || width > 4096 || height < 64 || height > 4096) throw new NumberFormatException();
                config.renderWidth = width;
                config.renderHeight = height;
                views.applyResolutionToAll(width, height);
            } catch (Exception error) {
                widthField.setText(String.valueOf(Math.max(64, Math.min(4096, config.renderWidth))));
                heightField.setText(String.valueOf(Math.max(64, Math.min(4096, config.renderHeight))));
                showError(new RuntimeException("宽和高需为 64–4096 之间的整数，可分别设置"));
            }
        };
        applyResolution.addActionListener(applyResolutionAction);
        widthField.addActionListener(applyResolutionAction);
        heightField.addActionListener(applyResolutionAction);
        JPanel resolution = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        resolution.add(new JLabel("宽")); resolution.add(widthField);
        resolution.add(new JLabel("高")); resolution.add(heightField); resolution.add(applyResolution);
        addSetting(form, c, 0, "分辨率", resolution);

        JComboBox<String> mergeLayout = new JComboBox<>(new String[] {"横向拼接", "竖向拼接", "不拼接发送"});
        mergeLayout.setSelectedIndex(sendLayoutIndex(config.cloudMergeLayout));
        JCheckBox localMerge = new JCheckBox("生成本地拼接图", config.localMergeEnabled);
        JPanel mergePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        mergePanel.add(localMerge); mergePanel.add(mergeLayout); mergePanel.add(new JLabel("云端/本地方向"));
        addSetting(form, c, 1, "拼接方向", mergePanel);

        JTextField cacheDir = new JTextField(config.cacheDirectory == null ? "" : config.cacheDirectory, 24);
        JCheckBox keepProjection = new JCheckBox("保留投影文件", config.cacheKeepProjections);
        JCheckBox keepHashDirectories = new JCheckBox("导出时保留哈希值目录", config.exportKeepHashDirectories);
        JPanel cachePanel = new JPanel(new BorderLayout(0, 2));
        JPanel cacheControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        cacheControls.add(cacheDir);
        JButton openCache = new JButton("打开缓存");
        openCache.addActionListener(event -> {
            Path directory = cacheDir.getText().isBlank() ? renderer.cacheDirectory() : Path.of(cacheDir.getText().trim());
            try { Files.createDirectories(directory); new ProcessBuilder("explorer", directory.toAbsolutePath().toString()).start(); }
            catch (Exception error) { showError(error); }
        });
        cacheControls.add(openCache); cacheControls.add(keepProjection);
        JButton exportCache = new JButton("导出投影文件");
        exportCache.setToolTipText("压缩 .litematic 投影文件和名称哈希索引，不包含图片或 about 记录");
        exportCache.addActionListener(event -> exportCachedProjections(cacheDir.getText(), keepHashDirectories.isSelected(), exportCache));
        cacheControls.add(exportCache);
        JButton importCache = new JButton("导入缓存");
        importCache.setToolTipText("导入其他机器导出的缓存 ZIP，只导入 .litematic 投影文件");
        importCache.addActionListener(event -> chooseCacheArchive(importCache));
        cacheControls.add(importCache);
        cacheControls.add(keepHashDirectories);
        JButton rebuildSearchIndex = new JButton("重建搜索索引");
        rebuildSearchIndex.setToolTipText("重新扫描当前缓存中的投影名称和哈希值，修复搜索索引");
        rebuildSearchIndex.addActionListener(event -> rebuildProjectionSearchIndex(rebuildSearchIndex));
        JPanel indexControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        indexControls.add(rebuildSearchIndex);
        cachePanel.add(cacheControls, BorderLayout.NORTH);
        cachePanel.add(indexControls, BorderLayout.SOUTH);
        addSetting(form, c, 2, "缓存目录", cachePanel);

        JTextField cacheRecentGb = new JTextField(String.valueOf(config.cacheRecentImageMaxBytes / (1024L * 1024 * 1024)), 5);
        JTextField cacheHistoricalGb = new JTextField(String.valueOf(config.cacheHistoricalImageMaxBytes / (1024L * 1024 * 1024)), 5);
        JPanel cacheLimit = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        cacheLimit.add(new JLabel("近一年投影图")); cacheLimit.add(cacheRecentGb);
        cacheLimit.add(new JLabel("GB；历史投影图")); cacheLimit.add(cacheHistoricalGb);
        cacheLimit.add(new JLabel("GB；每类优先清理少调用的完整图片，保留投影文件"));
        addSetting(form, c, 3, "缓存容量", cacheLimit);

        JTextField idleStop = new JTextField(String.valueOf(config.renderIdleStopMillis), 7);
        JTextField concurrentField = new JTextField(String.valueOf(Math.max(1, config.maxConcurrentRenders)), 4);
        JPanel runtime = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        runtime.add(new JLabel("空闲关闭")); runtime.add(idleStop); runtime.add(new JLabel("毫秒；并行客户端"));
        runtime.add(concurrentField); runtime.add(new JLabel("个"));
        addSetting(form, c, 4, "运行参数", runtime);

        JTextField memoryGbField = new JTextField(String.valueOf(config.memoryRestartThresholdBytes / (1024L * 1024 * 1024)), 4);
        JPanel memory = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        memory.add(memoryGbField); memory.add(new JLabel("GB，0 = 关闭内存自动重启"));
        addSetting(form, c, 5, "内存阈值", memory);

        JTextField localMinecraftClient = new JTextField(config.localMinecraftClientPath, 30);
        JPanel localClientPanel = new JPanel(new BorderLayout(4, 0));
        localClientPanel.add(localMinecraftClient, BorderLayout.CENTER);
        JButton chooseClient = new JButton("选择 JAR");
        chooseClient.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("选择 Minecraft 26.3 客户端 JAR");
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Minecraft 客户端 (*.jar)", "jar"));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) localMinecraftClient.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        JButton openClient = new JButton("打开下载位置");
        openClient.addActionListener(event -> openDownloadedClient());
        JPanel clientActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        clientActions.add(chooseClient);
        clientActions.add(openClient);
        localClientPanel.add(clientActions, BorderLayout.EAST);
        addSetting(form, c, 6, "本地 Minecraft 26.3 客户端", localClientPanel);

        JTextField maxFileSizeKb = new JTextField(String.valueOf(config.maxFileSizeKb), 8);
        JTextField privateMaxFileSizeKb = new JTextField(String.valueOf(config.privateMaxFileSizeKb), 8);
        JPanel fileLimits = new JPanel(new GridLayout(1, 2, 8, 0));
        fileLimits.add(labeledField("全局群文件上限 KB", maxFileSizeKb));
        fileLimits.add(labeledField("全局私聊文件上限 KB", privateMaxFileSizeKb));
        addSetting(form, c, 7, "文件大小", fileLimits);

        JPanel metadataPanel = new JPanel(new GridLayout(0, 2, 8, 2));
        JCheckBox showProjectionName = new JCheckBox("投影名称", config.showMetadataProjectionName);
        JCheckBox showAuthor = new JCheckBox("保存者游戏 ID", config.showMetadataAuthor);
        JCheckBox showCreatedAt = new JCheckBox("创建时间", config.showMetadataCreatedAt);
        JCheckBox showBlockStats = new JCheckBox("方块数/体积", config.showMetadataBlockStats);
        JCheckBox showSize = new JCheckBox("尺寸", config.showMetadataSize);
        JCheckBox showLitematicVersion = new JCheckBox("Litematic 版本", config.showMetadataLitematicVersion);
        JCheckBox showGameVersion = new JCheckBox("游戏版本", config.showMetadataGameVersion);
        metadataPanel.add(showProjectionName); metadataPanel.add(showAuthor); metadataPanel.add(showCreatedAt);
        metadataPanel.add(showBlockStats); metadataPanel.add(showSize); metadataPanel.add(showLitematicVersion); metadataPanel.add(showGameVersion);
        JComboBox<String> metadataFormat = new JComboBox<>(new String[] {"完整标签版", "精简版（模式2）", "图片版"});
        metadataFormat.setSelectedIndex("compact".equalsIgnoreCase(config.metadataFormat) ? 1 : "image".equalsIgnoreCase(config.metadataFormat) ? 2 : 0);
        addSetting(form, c, 8, "介绍格式", metadataFormat);
        addSetting(form, c, 9, "显示项目", metadataPanel);

        JTextField searchLimit = new JTextField(String.valueOf(Math.max(1, Math.min(100, config.projectionSearchResultLimit))), 6);
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        searchPanel.add(searchLimit); searchPanel.add(new JLabel("个（1-100，近半年保存的投影优先占前五，其余按渲染次数排序）"));
        addSetting(form, c, 10, "搜索投影结果", searchPanel);

        config.normalizeCommandDefinitions();
        CommandEditorPanel commandEditor = new CommandEditorPanel(config.commands, config.automaticRenderingEnabled);
        addSetting(form, c, 11, "机器人功能与指令", commandEditor);

        JTextArea commands = new JTextArea(HelpCardRenderer.text(config));
        commands.setEditable(false); commands.setLineWrap(true); commands.setWrapStyleWord(true); commands.setOpaque(false);
        commands.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        c.gridx = 1; c.gridy = 12; c.weightx = 1; c.gridwidth = 1; form.add(commands, c);

        JButton importClient = new JButton("保存并导入本地客户端");
        importClient.addActionListener(event -> {
            try {
                Path source = Path.of(localMinecraftClient.getText().trim());
                if (!Files.isRegularFile(source)) throw new IllegalArgumentException("客户端 JAR 不存在");
                config.localMinecraftClientPath = source.toAbsolutePath().normalize().toString();
                config.save(configPath);
                Thread.startVirtualThread(() -> {
                    try {
                        renderer.runtime().importLocalClient(source);
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Minecraft 26.3 客户端已准备完成", "导入完成", JOptionPane.INFORMATION_MESSAGE));
                    } catch (Throwable error) { SwingUtilities.invokeLater(() -> showError(error)); }
                });
            } catch (Exception error) { showError(error); }
        });
        JButton saveRender = new JButton("保存渲染设置并重启");
        saveRender.addActionListener(event -> saveRenderSettings(localMerge, mergeLayout, cacheDir, keepProjection, keepHashDirectories,
                cacheRecentGb, cacheHistoricalGb, idleStop, concurrentField, memoryGbField, localMinecraftClient, maxFileSizeKb, privateMaxFileSizeKb,
                showProjectionName, showAuthor, showCreatedAt, showBlockStats, showSize, showLitematicVersion, showGameVersion, metadataFormat));
        JButton saveSearch = new JButton("仅保存搜索设置");
        saveSearch.addActionListener(event -> saveProjectionSearchSettings(searchLimit));
        JButton saveCommands = new JButton("保存功能与指令");
        saveCommands.addActionListener(event -> saveCommandSettings(commandEditor, commands));
        JButton syncCommands = new JButton("同步 QQ 指令面板");
        syncCommands.addActionListener(event -> {
            bots.syncOfficialCommandPanels();
            JOptionPane.showMessageDialog(this, "已开始同步，结果请查看日志。", "QQ 指令面板", JOptionPane.INFORMATION_MESSAGE);
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(importClient); buttons.add(saveRender); buttons.add(saveSearch); buttons.add(saveCommands); buttons.add(syncCommands);
        c.gridx = 1; c.gridy = 13; c.fill = GridBagConstraints.NONE; form.add(buttons, c);
        return new JScrollPane(form);
    }

    /** 输入框右缘内嵌历史小箭头（组合框样式）。 */
    private JComponent fieldWithArrow(javax.swing.JTextField field, javax.swing.JButton arrow) {
        arrow.setText("▾");
        arrow.setMargin(new java.awt.Insets(0, 0, 0, 0));
        arrow.setFocusable(false);
        arrow.setPreferredSize(new java.awt.Dimension(22, field.getPreferredSize().height));
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(field, BorderLayout.CENTER);
        wrap.add(arrow, BorderLayout.EAST);
        return wrap;
    }

    private void showPresetMenu(java.awt.Component component, int x, int y, final int row) {
        JPopupMenu menu = new JPopupMenu();
        String[][] presets = {
            {"正等轴测", "135", "36"}, {"反等轴测", "315", "36"},
            {"东视图", "90", "0"}, {"南视图", "180", "0"}, {"西视图", "270", "0"}, {"北视图", "0", "0"},
            {"顶视图", "0", "89.9"}, {"底视图", "0", "-89.9"}
        };
        for (String[] preset : presets) {
            JMenuItem item = new JMenuItem(preset[0] + "（Yaw " + preset[1] + "° Pitch " + preset[2] + "°）");
            item.addActionListener(e -> views.applyPreset(row, preset[0], Double.parseDouble(preset[1]), Double.parseDouble(preset[2])));
            menu.add(item);
        }
        menu.show(component, x, y);
    }

    /** 历史下拉：直接贴在输入框正下方，宽度与输入框一致，长路径显示尾部（文件名）。 */
    private void showRecentMenu(javax.swing.JTextField field, java.util.List<String> items, java.util.function.Consumer<String> onSelect) {
        JPopupMenu menu = new JPopupMenu();
        if (items.isEmpty()) {
            JMenuItem empty = new JMenuItem("（暂无历史）"); empty.setEnabled(false); menu.add(empty);
        } else {
            java.awt.FontMetrics metrics = field.getFontMetrics(field.getFont());
            int maxWidth = Math.max(200, field.getWidth() - 30);
            for (String item : items) {
                String label = item;
                if (metrics.stringWidth(label) > maxWidth) {
                    while (label.length() > 1 && metrics.stringWidth("…" + label) > maxWidth) label = label.substring(1);
                    label = "…" + label;
                }
                JMenuItem entry = new JMenuItem(label);
                entry.setToolTipText(item);
                entry.addActionListener(e -> onSelect.accept(item));
                menu.add(entry);
            }
        }
        menu.setPreferredSize(new java.awt.Dimension(field.getWidth(), menu.getPreferredSize().height));
        menu.show(field, 0, field.getHeight());
    }

    private void setInputPath(String path) {
        try { importProjectionFiles(List.of(Path.of(path))); }
        catch (Exception error) { showError(error); }
    }

    private void setOutputPath(String path) {
        outputDirectory.setText(path);
        persistOutputDirectory(path);
    }

    private void persistOutputDirectory(String directory) {
        config.outputDirectory = directory;
        addRecent(config.recentOutputDirectories, directory);
        try { config.save(configPath); } catch (Exception ex) { log("保存设置失败：" + ex.getMessage()); }
    }

    private static void addRecent(java.util.List<String> list, String value) {
        if (value == null || value.isBlank()) return;
        list.remove(value);
        list.add(0, value);
        while (list.size() > 10) list.remove(list.size() - 1);
    }

    private void openLocation(String path) {
        try {
            Path target = Path.of(path);
            if (!Files.exists(target)) {
                JOptionPane.showMessageDialog(this, "路径不存在：" + target, "无法打开", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (Files.isRegularFile(target)) new ProcessBuilder("explorer", "/select," + target.toAbsolutePath()).start();
            else new ProcessBuilder("explorer", target.toAbsolutePath().toString()).start();
        } catch (Exception ex) { showError(ex); }
    }

    private void openConfigFile() {
        try {
            if (!Files.isRegularFile(configPath)) config.save(configPath);
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(configPath.toFile());
            else openLocation(configPath.toString());
        } catch (Exception error) { showError(error); }
    }

    private void openDownloadedClient() {
        Path clientJar = renderer.runtime().minecraftClientJar();
        if (Files.isRegularFile(clientJar)) {
            openLocation(clientJar.toString());
            return;
        }
        Path runtimeDirectory = root.resolve("runtime");
        if (Files.isDirectory(runtimeDirectory)) openLocation(runtimeDirectory.toString());
        else JOptionPane.showMessageDialog(this, "内置客户端尚未下载。开始一次渲染后会自动下载。",
                "客户端位置", JOptionPane.INFORMATION_MESSAGE);
    }

    private JComponent historyPanel(){JTable table=new JTable(history);table.setFillsViewportHeight(true);table.addMouseListener(new java.awt.event.MouseAdapter(){@Override public void mouseClicked(java.awt.event.MouseEvent event){if(event.getClickCount()==2){int row=table.rowAtPoint(event.getPoint());if(row>=0&&row<historyLocations.size())openLocation(historyLocations.get(row));}}});return new JScrollPane(table);}

    private JComponent resourcePackPanel() {
        JPanel panel = new JPanel(new BorderLayout(8,8)); panel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        JList<AgentConfig.ResourcePackEntry> list = new JList<>(packModel);
        list.setCellRenderer((component, value, index, selected, focus) -> {
            JLabel label = new JLabel((value.enabled() ? "[启用] " : "[停用] ") + Path.of(value.path()).getFileName());
            label.setOpaque(true); label.setBackground(selected ? component.getSelectionBackground() : component.getBackground());
            label.setForeground(selected ? component.getSelectionForeground() : component.getForeground()); label.setBorder(BorderFactory.createEmptyBorder(5,5,5,5)); return label;
        });
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton add = new JButton("添加"); add.addActionListener(e -> addPack());
        JButton remove = new JButton("删除"); remove.addActionListener(e -> { int i=list.getSelectedIndex(); if(i>=0){packModel.remove(i);syncResourcePacks();} });
        JButton toggle = new JButton("启用/停用"); toggle.addActionListener(e -> { int i=list.getSelectedIndex(); if(i>=0){var p=packModel.get(i);packModel.set(i,new AgentConfig.ResourcePackEntry(p.path(),!p.enabled()));syncResourcePacks();} });
        JButton up = new JButton("上移"); up.addActionListener(e -> movePack(list,-1)); JButton down = new JButton("下移"); down.addActionListener(e -> movePack(list,1));
        JButton apply = new JButton("应用并重载"); apply.addActionListener(e -> applyPacks(apply));
        tools.add(add);tools.add(remove);tools.add(toggle);tools.add(up);tools.add(down);tools.add(apply); panel.add(tools,BorderLayout.SOUTH); return panel;
    }

    private JComponent settingsPanel() {
        JPanel panel = new JPanel(new GridBagLayout()); panel.setBorder(BorderFactory.createEmptyBorder(16,16,16,16));
         JTextField id = new JTextField(config.agentId,30); JPasswordField secret = new JPasswordField(config.sharedSecret,30);
          JTextField url = new JTextField(config.cloudWebSocketUrl,30); JCheckBox enabled = new JCheckBox("主动连接云端 Koishi",config.cloudEnabled);
          JCheckBox startup = new JCheckBox("随 Windows 登录启动",config.startWithWindows); JCheckBox tray = new JCheckBox("关闭窗口时最小化到托盘",config.minimizeToTray);
          JTextField webUsername = new JTextField(config.webUsername,30);
          JPasswordField newWebPassword = new JPasswordField(30); JPasswordField newWebPasswordAgain = new JPasswordField(30);
         GridBagConstraints c=new GridBagConstraints();c.insets=new Insets(6,6,6,6);c.fill=GridBagConstraints.HORIZONTAL;c.anchor=GridBagConstraints.WEST;
         addSetting(panel,c,0,"Agent ID",id);addSetting(panel,c,1,"共享密钥",secret);addSetting(panel,c,2,"WebSocket 地址",url);
         JCheckBox webEnabled = new JCheckBox("启用 Web 管理后台", config.webEnabled);
         JTextField webBindHost = new JTextField(config.webBindHost, 30);
         JTextField webPort = new JTextField(String.valueOf(config.webPort), 8);
          addSetting(panel,c,3,"Web 监听地址",webBindHost);addSetting(panel,c,4,"Web 监听端口",webPort);
          addSetting(panel,c,5,"Web 登录用户名",webUsername);
           addSetting(panel,c,6,"新 Web 密码",newWebPassword);addSetting(panel,c,7,"确认新密码",newWebPasswordAgain);
           c.gridx=1;c.gridy=8;panel.add(webEnabled,c);
           c.gridx=1;c.gridy=9;panel.add(enabled,c);
           c.gridx=1;c.gridy=10;panel.add(startup,c);c.gridy=11;panel.add(tray,c);
           JLabel warning=new JLabel("密码可使用字母、数字和符号；留空保持不变。");warning.setForeground(new Color(170,70,0));c.gridy=12;panel.add(warning,c);
         JButton save=new JButton("保存连接设置");save.addActionListener(e->{try{
              boolean oldWebEnabled = config.webEnabled;
              String oldWebBindHost = config.webBindHost;
              int oldWebPort = config.webPort;
              String oldWebUsername = config.webUsername;
             int newWebPort = Integer.parseInt(webPort.getText().trim());
             if (newWebPort < 1 || newWebPort > 65535) throw new IllegalArgumentException("Web 端口必须为 1-65535");
             String newUsername = webUsername.getText().trim();
             if (newUsername.isBlank()) throw new IllegalArgumentException("Web 登录用户名不能为空");
              String password = new String(newWebPassword.getPassword());
             String passwordAgain = new String(newWebPasswordAgain.getPassword());
             if (!password.isBlank() || !passwordAgain.isBlank()) {
                 if (password.isBlank() || !password.equals(passwordAgain)) throw new IllegalArgumentException("两次新 Web 密码必须一致且不能为空");
                 config.webPassword = password;
                 config.webPasswordChangeNotice = false;
             }
             boolean cloudChanged = config.cloudEnabled != enabled.isSelected()
                     || !java.util.Objects.equals(config.cloudWebSocketUrl, url.getText().trim())
                     || !java.util.Objects.equals(config.agentId, id.getText().trim())
                     || !java.util.Objects.equals(config.sharedSecret, new String(secret.getPassword()));
             config.agentId=id.getText().trim();config.sharedSecret=new String(secret.getPassword());config.cloudWebSocketUrl=url.getText().trim();config.cloudEnabled=enabled.isSelected();
             config.webEnabled=webEnabled.isSelected();config.webBindHost=webBindHost.getText().trim().isBlank()?"0.0.0.0":webBindHost.getText().trim();config.webPort=newWebPort;
             config.webUsername = newUsername;
              config.startWithWindows=startup.isSelected();config.minimizeToTray=tray.isSelected();config.save(configPath);StartupManager.setEnabled(config.startWithWindows);
              if (!password.isBlank() || !java.util.Objects.equals(oldWebUsername, config.webUsername)) {
                  try { Files.writeString(configPath.resolveSibling("web-credentials.txt"), "用户名：" + config.webUsername + "\n密码：" + config.webPassword + "\n"); }
                  catch (Exception error) { log("保存 Web 凭据提示文件失败：" + error.getMessage()); }
              }
              newWebPassword.setText(""); newWebPasswordAgain.setText("");
             boolean endpointChanged = !java.util.Objects.equals(oldWebBindHost, config.webBindHost) || oldWebPort != config.webPort;
              if (cloudChanged) cloud.reload();
              if (!oldWebEnabled && config.webEnabled) web.start();
             else if (oldWebEnabled && !config.webEnabled) web.close();
             String message = endpointChanged && oldWebEnabled && config.webEnabled ? "连接设置已保存，Web 监听地址或端口需要重启后生效。" : "连接设置已保存。";
             JOptionPane.showMessageDialog(this,message, "保存成功",JOptionPane.INFORMATION_MESSAGE);
           }catch(Exception ex){showError(ex);}});c.gridy=13;c.fill=GridBagConstraints.NONE;panel.add(save,c);
           JButton saveRestart=new JButton("保存并重启");saveRestart.addActionListener(e->{save.doClick();restartApplication(false);});c.gridx=2;c.gridy=13;panel.add(saveRestart,c);
        return new JScrollPane(panel);
    }

    private JComponent botPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        DefaultListModel<AgentConfig.BotProfile> model = new DefaultListModel<>();
        if (config.botProfiles != null) for (AgentConfig.BotProfile profile : config.botProfiles) if (profile != null) model.addElement(profile);
        JList<AgentConfig.BotProfile> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((component, value, index, selected, focus) -> {
            String name = value == null || value.name == null ? "机器人账号" : value.name;
            String type = value != null && "onebot".equalsIgnoreCase(value.type) ? "OneBot" : "官方 QQ";
            JLabel label = new JLabel((value != null && value.enabled ? "[启用] " : "[停用] ") + name + "（" + type + "）");
            label.setOpaque(true);
            label.setBackground(selected ? component.getSelectionBackground() : component.getBackground());
            label.setForeground(selected ? component.getSelectionForeground() : component.getForeground());
            label.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
            return label;
        });
        JPanel listPanel = new JPanel(new BorderLayout(5, 5));
        listPanel.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addOfficial = new JButton("添加官方 QQ");
        JButton addOneBot = new JButton("添加 OneBot");
        JButton remove = new JButton("删除");
        listButtons.add(addOfficial); listButtons.add(addOneBot); listButtons.add(remove);
        listPanel.add(listButtons, BorderLayout.SOUTH);
        listPanel.setPreferredSize(new Dimension(270, 0));

        JTextField name = new JTextField(); JCheckBox enabled = new JCheckBox("启用账号"); JCheckBox autoConnect = new JCheckBox("启动时自动连接");
        JComboBox<String> type = new JComboBox<>(new String[] {"官方 QQ", "OneBot / NekoBot"});
        JTextField appId = new JTextField(); JPasswordField appSecret = new JPasswordField();
        JCheckBox sandbox = new JCheckBox("沙箱环境"); JTextField intents = new JTextField();
        JComboBox<String> groupMode = new JComboBox<>(new String[] {"接收群文件自动识别", "仅被 @ 时识别", "都可以（文件或 @）"});
        JCheckBox commandRequireMention = new JCheckBox("指令需要 @（关闭后不 @ 也可）");
        JTextField groupLimit = new JTextField(); JTextField privateLimit = new JTextField();
        JCheckBox allowPrivate = new JCheckBox("允许单人对话渲染");
        JComboBox<String> transport = new JComboBox<>(new String[] {"正向 WebSocket", "反向 WebSocket"});
        JTextField webSocketUrl = new JTextField(); JPasswordField accessToken = new JPasswordField();
        JTextField listenHost = new JTextField(); JTextField listenPort = new JTextField(); JTextField path = new JTextField();
        JComboBox<String> sendMode = new JComboBox<>(new String[] {"联合发送", "合并转发"});
        JComboBox<String> imageSendLayout = new JComboBox<>(new String[] {"横向拼接", "竖向拼接", "不拼接发送"});
        JCheckBox replyAndMention = new JCheckBox("引用原消息并 @ 发送者");
        JCheckBox showViewTitles = new JCheckBox("显示视角标题"); JCheckBox successNotice = new JCheckBox("发送渲染成功提示");
        JCheckBox whitelistEnabled = new JCheckBox("启用群白名单"); JCheckBox blacklistEnabled = new JCheckBox("启用群黑名单");
        JTextArea whitelist = new JTextArea(3, 20); JTextArea blacklist = new JTextArea(3, 20);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(4, 6, 4, 6); c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.WEST;
        addSetting(form, c, 0, "账号名称", name); c.gridx = 1; c.gridy = 1; form.add(enabled, c); c.gridx = 2; form.add(autoConnect, c);
        addSetting(form, c, 2, "账号类型", type); addSetting(form, c, 3, "群消息模式", groupMode);

        JPanel official = new JPanel(new GridBagLayout()); GridBagConstraints oc = new GridBagConstraints(); oc.insets = new Insets(2, 2, 2, 2); oc.fill = GridBagConstraints.HORIZONTAL;
        addSetting(official, oc, 0, "AppID", appId); addSetting(official, oc, 1, "AppSecret", appSecret);
        oc.gridx = 1; oc.gridy = 2; official.add(sandbox, oc); addSetting(official, oc, 3, "事件意图", intents);
        addSetting(form, c, 4, "官方 QQ", official);

        JPanel onebot = new JPanel(new GridBagLayout()); GridBagConstraints nc = new GridBagConstraints(); nc.insets = new Insets(2, 2, 2, 2); nc.fill = GridBagConstraints.HORIZONTAL;
        addSetting(onebot, nc, 0, "连接方式", transport); addSetting(onebot, nc, 1, "WebSocket 地址", webSocketUrl);
        addSetting(onebot, nc, 2, "Token", accessToken); addSetting(onebot, nc, 3, "监听地址", listenHost);
        addSetting(onebot, nc, 4, "监听端口", listenPort); addSetting(onebot, nc, 5, "反向路径", path);
        addSetting(form, c, 5, "OneBot / NekoBot", onebot);

        JPanel limits = new JPanel(new GridLayout(1, 2, 8, 0));
        limits.add(labeledField("群文件上限 KB（0 跟随全局）", groupLimit));
        limits.add(labeledField("私聊文件上限 KB（0 跟随全局）", privateLimit));
        addSetting(form, c, 6, "文件大小", limits);
        JPanel send = new JPanel(new GridLayout(0, 2, 8, 2));
        send.add(allowPrivate); send.add(commandRequireMention); send.add(replyAndMention); send.add(showViewTitles); send.add(successNotice);
        send.add(new JLabel("发送模式")); send.add(sendMode); send.add(new JLabel("渲染图发送")); send.add(imageSendLayout); send.add(whitelistEnabled); send.add(blacklistEnabled);
        addSetting(form, c, 7, "发送与权限", send);
        JPanel lists = new JPanel(new GridLayout(1, 2, 8, 0));
        lists.add(labeledArea("群白名单（每行一个）", whitelist)); lists.add(labeledArea("群黑名单（每行一个）", blacklist));
        addSetting(form, c, 8, "群名单", lists);
        JButton save = new JButton("保存账号并重连"); JLabel message = new JLabel(" ");
        JPanel savePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0)); savePanel.add(save); savePanel.add(message);
        addSetting(form, c, 9, "", savePanel);

        Runnable load = () -> {
            AgentConfig.BotProfile profile = list.getSelectedValue();
            boolean has = profile != null;
            name.setEnabled(has); enabled.setEnabled(has); autoConnect.setEnabled(has); type.setEnabled(has); groupMode.setEnabled(has);
            appId.setEnabled(has); appSecret.setEnabled(has); sandbox.setEnabled(has); intents.setEnabled(has);
            transport.setEnabled(has); webSocketUrl.setEnabled(has); accessToken.setEnabled(has); listenHost.setEnabled(has); listenPort.setEnabled(has); path.setEnabled(has);
        groupLimit.setEnabled(has); privateLimit.setEnabled(has); allowPrivate.setEnabled(has); commandRequireMention.setEnabled(has); sendMode.setEnabled(has); imageSendLayout.setEnabled(has); replyAndMention.setEnabled(has); showViewTitles.setEnabled(has); successNotice.setEnabled(has); whitelistEnabled.setEnabled(has); blacklistEnabled.setEnabled(has); whitelist.setEnabled(has); blacklist.setEnabled(has);
            if (!has) return;
            name.setText(profile.name); enabled.setSelected(profile.enabled); autoConnect.setSelected(profile.autoConnect); type.setSelectedIndex("onebot".equalsIgnoreCase(profile.type) ? 1 : 0);
            groupMode.setSelectedIndex("mentiononly".equalsIgnoreCase(profile.groupMessageMode) ? 1 : "any".equalsIgnoreCase(profile.groupMessageMode) ? 2 : 0); appId.setText(profile.appId); appSecret.setText(profile.appSecret); sandbox.setSelected(profile.sandbox); intents.setText(String.valueOf(profile.intents));
            transport.setSelectedIndex("reverse".equalsIgnoreCase(profile.transport) ? 1 : 0); webSocketUrl.setText(profile.webSocketUrl); accessToken.setText(profile.accessToken); listenHost.setText(profile.listenHost); listenPort.setText(String.valueOf(profile.listenPort)); path.setText(profile.path);
            groupLimit.setText(String.valueOf(profile.maxFileSizeKb)); privateLimit.setText(String.valueOf(profile.privateMaxFileSizeKb)); allowPrivate.setSelected(profile.allowPrivateRender); commandRequireMention.setSelected(profile.commandRequireMention); sendMode.setSelectedIndex("forward".equalsIgnoreCase(profile.sendMode) ? 1 : 0); imageSendLayout.setSelectedIndex(sendLayoutIndex(profile.imageSendLayout)); replyAndMention.setSelected(profile.replyAndMention); showViewTitles.setSelected(profile.showViewTitles); successNotice.setSelected(profile.successNotice);
            whitelistEnabled.setSelected(profile.groupWhitelistEnabled); blacklistEnabled.setSelected(profile.groupBlacklistEnabled); whitelist.setText(String.join(System.lineSeparator(), profile.groupWhitelist)); blacklist.setText(String.join(System.lineSeparator(), profile.groupBlacklist));
            message.setText(" ");
        };
        list.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) load.run(); });
        Runnable add = () -> { AgentConfig.BotProfile profile = new AgentConfig.BotProfile(); profile.id = "bot-" + UUID.randomUUID(); profile.type = "official"; profile.name = "官方 QQ 账号"; profile.normalize(); model.addElement(profile); list.setSelectedIndex(model.size() - 1); };
        addOfficial.addActionListener(event -> add.run());
        addOneBot.addActionListener(event -> { AgentConfig.BotProfile profile = new AgentConfig.BotProfile(); profile.id = "bot-" + UUID.randomUUID(); profile.type = "onebot"; profile.name = "OneBot 账号"; profile.normalize(); model.addElement(profile); list.setSelectedIndex(model.size() - 1); });
        remove.addActionListener(event -> { int index = list.getSelectedIndex(); if (index >= 0) { model.remove(index); if (!model.isEmpty()) list.setSelectedIndex(Math.min(index, model.size() - 1)); else load.run(); } });
        save.addActionListener(event -> {
            AgentConfig.BotProfile profile = list.getSelectedValue();
            if (profile == null) { message.setText("请先选择账号"); return; }
            try {
                profile.name = name.getText().trim(); profile.enabled = enabled.isSelected(); profile.autoConnect = autoConnect.isSelected(); profile.type = type.getSelectedIndex() == 1 ? "onebot" : "official"; profile.groupMessageMode = switch (groupMode.getSelectedIndex()) { case 1 -> "mentionOnly"; case 2 -> "any"; default -> "received"; };
                profile.appId = appId.getText().trim(); profile.appSecret = new String(appSecret.getPassword()); profile.sandbox = sandbox.isSelected(); profile.intents = Long.parseLong(intents.getText().trim());
                profile.transport = transport.getSelectedIndex() == 1 ? "reverse" : "forward"; profile.webSocketUrl = webSocketUrl.getText().trim(); profile.accessToken = new String(accessToken.getPassword()); profile.listenHost = listenHost.getText().trim(); profile.listenPort = Integer.parseInt(listenPort.getText().trim()); profile.path = path.getText().trim();
                profile.maxFileSizeKb = Long.parseLong(groupLimit.getText().trim()); profile.privateMaxFileSizeKb = Long.parseLong(privateLimit.getText().trim());
                profile.allowPrivateRender = allowPrivate.isSelected(); profile.commandRequireMention = commandRequireMention.isSelected(); profile.sendMode = sendMode.getSelectedIndex() == 1 ? "forward" : "combined"; profile.imageSendLayout = sendLayoutValue(imageSendLayout.getSelectedIndex()); profile.replyAndMention = replyAndMention.isSelected(); profile.showViewTitles = showViewTitles.isSelected(); profile.successNotice = successNotice.isSelected(); profile.groupWhitelistEnabled = whitelistEnabled.isSelected(); profile.groupBlacklistEnabled = blacklistEnabled.isSelected(); profile.groupWhitelist = textLines(whitelist.getText()); profile.groupBlacklist = textLines(blacklist.getText()); profile.normalize();
                config.botProfiles = java.util.Collections.list(model.elements()); config.save(configPath); bots.reload(); list.repaint(); message.setText("已保存并重连");
            } catch (Exception error) { message.setText("保存失败：" + error.getMessage()); }
        });
        type.addActionListener(event -> { boolean officialSelected = type.getSelectedIndex() == 0; official.setVisible(officialSelected); onebot.setVisible(!officialSelected); form.revalidate(); form.repaint(); });
        boolean initialOfficial = type.getSelectedIndex() == 0;
        official.setVisible(initialOfficial);
        onebot.setVisible(!initialOfficial);
        if (!model.isEmpty()) list.setSelectedIndex(0); else load.run();
        panel.add(listPanel, BorderLayout.WEST); panel.add(new JScrollPane(form), BorderLayout.CENTER);
        return panel;
    }

    private static JPanel labeledField(String title, JComponent field) { JPanel panel = new JPanel(new BorderLayout(3, 2)); panel.add(new JLabel(title), BorderLayout.NORTH); panel.add(field, BorderLayout.CENTER); return panel; }
    private static JPanel labeledArea(String title, JTextArea area) { JPanel panel = new JPanel(new BorderLayout(3, 2)); panel.add(new JLabel(title), BorderLayout.NORTH); panel.add(new JScrollPane(area), BorderLayout.CENTER); return panel; }
    private static List<String> textLines(String value) { return java.util.Arrays.stream((value == null ? "" : value).split("\\r?\\n")).map(String::trim).filter(item -> !item.isBlank()).toList(); }

    private static void addSetting(JPanel panel, GridBagConstraints c, int row, String name, JComponent field) { c.gridy=row;c.gridx=0;c.weightx=0;panel.add(new JLabel(name),c);c.gridx=1;c.weightx=1;panel.add(field,c); }
    private JComponent logPanel() {
        logs.setEditable(false); logs.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));
        JPanel panel = new JPanel(new BorderLayout(6,6));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton export = new JButton("导出日志（诊断包）");
        export.addActionListener(e -> exportLogs());
        JButton openFolder = new JButton("打开日志文件夹");
        openFolder.addActionListener(e -> {
            try { Files.createDirectories(logFile().getParent()); new ProcessBuilder("explorer", logFile().getParent().toAbsolutePath().toString()).start(); }
            catch (Exception ex) { showError(ex); }
        });
        buttons.add(export); buttons.add(openFolder);
        buttons.add(new JLabel("日志同时自动写入 agent-gui.log，可跨设备排查"));
        panel.add(buttons, BorderLayout.NORTH);
        panel.add(new JScrollPane(logs), BorderLayout.CENTER);
        return panel;
    }

    private Path logFile() { return root.resolve("agent-gui.log"); }

    /** GUI 日志同步落盘（agent-gui.log，超过 2MB 轮转为 .old），保证重启/崩溃前的记录可查。 */
    private void persistLog(String line) {
        try {
            Path file = logFile();
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > 2L * 1024 * 1024) {
                Path old = file.resolveSibling("agent-gui.log.old");
                Files.move(file, old, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, LocalDateTime.now() + "  " + line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    /** 打包跨设备诊断材料：GUI 日志、调试日志、运行时日志、配置（密钥脱敏）。 */
    private void exportLogs() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("导出诊断日志包");
        chooser.setSelectedFile(new java.io.File("litematic-agent-logs-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip"));
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        Path target = chooser.getSelectedFile().toPath().toAbsolutePath();
        Thread.startVirtualThread(() -> {
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(target))) {
                List<Path> files = new ArrayList<>();
                files.add(logFile());
                files.add(logFile().resolveSibling("agent-gui.log.old"));
                files.add(root.resolve("agent.log"));
                files.add(root.resolve("agent-crash.log"));
                files.add(root.resolve("runtime-crash.log"));
                files.add(root.resolve("send-debug.log"));
                files.add(root.resolve("cache-debug.log"));
                files.add(root.resolve("restart-guard.json"));
                files.add(configPath);
                files.add(root.resolve("runtime/launch-diagnostics.txt"));
                files.add(root.resolve("runtime/minecraft-runtime.log"));
                files.add(root.resolve("runtime/minecraft-runtime-2.log"));
                files.add(root.resolve("runtime/minecraft-runtime-3.log"));
                for (Path file : files) {
                    if (file == null || !Files.isRegularFile(file)) continue;
                    byte[] bytes = Files.readAllBytes(file);
                    if (file.equals(configPath)) bytes = redactSecrets(bytes);
                    zip.putNextEntry(new java.util.zip.ZipEntry(file.getFileName().toString()));
                    zip.write(bytes);
                    zip.closeEntry();
                }
                zip.finish();
                SwingUtilities.invokeLater(() -> log("诊断日志包已导出：" + target));
            } catch (Throwable error) {
                SwingUtilities.invokeLater(() -> showError(error));
            }
        });
    }

    /** 配置文件进诊断包前脱敏：共享密钥与连接地址保留结构但遮住值。 */
    private static byte[] redactSecrets(byte[] configBytes) {
        try {
            String text = new String(configBytes, java.nio.charset.StandardCharsets.UTF_8);
            String redacted = text
                    .replaceAll("(\"sharedSecret\"\\s*:\\s*\")[^\"]*(\")", "$1<已脱敏>$2")
                    .replaceAll("(\"cloudWebSocketUrl\"\\s*:\\s*\")[^\"]*(\")", "$1<已脱敏>$2");
            return redacted.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "<脱敏失败，已省略>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 导出当前缓存中的投影源文件，输出到缓存目录的上一级。 */
    private void exportCachedProjections(String configuredDirectory, boolean keepHashDirectories, JButton button) {
        Path cacheDirectory;
        try {
            cacheDirectory = configuredDirectory == null || configuredDirectory.isBlank()
                    ? renderer.cacheDirectory()
                    : Path.of(configuredDirectory.trim()).toAbsolutePath().normalize();
        } catch (Exception error) {
            showError(error);
            return;
        }
        button.setEnabled(false);
        Thread.startVirtualThread(() -> {
            try {
                Path archive = ProjectionArchiveExporter.export(cacheDirectory, keepHashDirectories);
                config.exportKeepHashDirectories = keepHashDirectories;
                try { config.save(configPath); } catch (Exception error) { log("保存导出选项失败：" + error.getMessage()); }
                SwingUtilities.invokeLater(() -> {
                    button.setEnabled(true);
                    log("缓存投影文件已导出（仅 .litematic）：" + archive);
                    JOptionPane.showMessageDialog(this, "已导出投影文件：\n" + archive,
                            "导出完成", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Throwable error) {
                SwingUtilities.invokeLater(() -> {
                    button.setEnabled(true);
                    showError(error);
                });
            }
        });
    }

    private void rebuildProjectionSearchIndex(JButton button) {
        button.setEnabled(false);
        Thread.startVirtualThread(() -> {
            try {
                int count = renderer.rebuildProjectionIndex();
                SwingUtilities.invokeLater(() -> {
                    button.setEnabled(true);
                    String message = "已重新扫描缓存并生成投影名称/哈希索引，共 " + count + " 个有效投影。现在可直接使用搜索投影指令。";
                    log(message);
                    JOptionPane.showMessageDialog(this, message, "搜索索引已更新", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Throwable error) {
                SwingUtilities.invokeLater(() -> {
                    button.setEnabled(true);
                    showError(error);
                });
            }
        });
    }

    private void chooseInputFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导入投影");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Litematica 投影 (*.litematic)", "litematic"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            java.io.File[] selected = chooser.getSelectedFiles();
            if (selected.length == 0 && chooser.getSelectedFile() != null) selected = new java.io.File[] {chooser.getSelectedFile()};
            importProjectionFiles(java.util.Arrays.stream(selected).map(java.io.File::toPath).toList());
        }
    }

    private void chooseCacheArchive(JButton button) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导入投影缓存");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("投影缓存 ZIP (*.zip)", "zip"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            importCacheArchive(chooser.getSelectedFile().toPath(), button);
        }
    }

    private void importCacheArchive(Path archive, JButton button) {
        button.setEnabled(false);
        Thread.startVirtualThread(() -> {
            try {
                ProjectionArchiveImporter.ImportResult result = renderer.importCacheArchive(archive);
                SwingUtilities.invokeLater(() -> {
                    button.setEnabled(true);
                    importProjectionFiles(result.importedFiles());
                    String message = "已导入 " + result.importedCount() + " 个投影";
                    if (result.existingFiles() > 0) message += "，已存在 " + result.existingFiles() + " 个";
                    if (result.invalidEntries() > 0) message += "，跳过无效条目 " + result.invalidEntries() + " 个";
                    log(message);
                    JOptionPane.showMessageDialog(this, message, "导入完成", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Throwable error) {
                SwingUtilities.invokeLater(() -> {
                    button.setEnabled(true);
                    showError(error);
                });
            }
        });
    }

    private void importProjectionFiles(List<Path> files) {
        Path first = null;
        for (Path value : files) {
            if (value == null) continue;
            Path path = value.toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || !path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".litematic")) continue;
            boolean exists = false;
            for (int i = 0; i < projectionModel.size(); i++) if (projectionModel.get(i).equals(path)) { exists = true; break; }
            if (!exists) projectionModel.addElement(path);
            if (first == null) first = path;
            addRecent(config.recentProjectionPaths, path.toString());
        }
        if (first != null) selectProjection(first);
        try { config.save(configPath); } catch (Exception error) { log("保存投影列表失败：" + error.getMessage()); }
    }

    private void selectProjection(Path path) {
        inputFile.setText(path.toString());
        if (outputDirectory.getText().isBlank() && path.getParent() != null) outputDirectory.setText(path.getParent().resolve("渲染结果").toString());
        try { preview.fileChanged(path); } catch (Exception ignored) {}
        for (int i = 0; i < projectionModel.size(); i++) {
            if (projectionModel.get(i).equals(path)) { projectionList.setSelectedIndex(i); break; }
        }
    }

    private void removeSelectedProjections() {
        int[] selected = projectionList.getSelectedIndices();
        for (int i = selected.length - 1; i >= 0; i--) projectionModel.remove(selected[i]);
        if (projectionModel.isEmpty()) inputFile.setText("");
        else selectProjection(projectionModel.get(Math.min(selected.length == 0 ? 0 : selected[0], projectionModel.size() - 1)));
        try { config.save(configPath); } catch (Exception error) { log("保存投影列表失败：" + error.getMessage()); }
    }

    private void clearImportedProjections() {
        projectionModel.clear(); inputFile.setText("");
        try { config.save(configPath); } catch (Exception error) { log("保存投影列表失败：" + error.getMessage()); }
    }

    private void saveRenderSettings(JCheckBox localMerge,
                                    JComboBox<String> mergeLayout,
                                     JTextField cacheDir, JCheckBox keepProjection, JCheckBox keepHashDirectories,
                                     JTextField cacheRecentGb, JTextField cacheHistoricalGb,
                                     JTextField idleStop, JTextField concurrentField, JTextField memoryGbField,
                                    JTextField localMinecraftClient, JTextField maxFileSizeKb, JTextField privateMaxFileSizeKb,
                                    JCheckBox showProjectionName, JCheckBox showAuthor, JCheckBox showCreatedAt,
                                    JCheckBox showBlockStats, JCheckBox showSize, JCheckBox showLitematicVersion,
                                    JCheckBox showGameVersion, JComboBox<String> metadataFormat) {
        try {
            config.localMergeEnabled = localMerge.isSelected();
            config.cloudMergeLayout = sendLayoutValue(mergeLayout.getSelectedIndex());
            config.cacheDirectory = cacheDir.getText().trim();
            config.cacheKeepProjections = keepProjection.isSelected();
            config.exportKeepHashDirectories = keepHashDirectories.isSelected();
            config.cacheRecentImageMaxBytes = Math.max(0, Long.parseLong(cacheRecentGb.getText().trim())) * 1024L * 1024 * 1024;
            config.cacheHistoricalImageMaxBytes = Math.max(0, Long.parseLong(cacheHistoricalGb.getText().trim())) * 1024L * 1024 * 1024;
            config.cacheMaxBytes = config.cacheRecentImageMaxBytes + config.cacheHistoricalImageMaxBytes;
            config.renderIdleStopMillis = Math.max(0, Integer.parseInt(idleStop.getText().trim()));
            config.maxConcurrentRenders = Math.max(1, Math.min(4, Integer.parseInt(concurrentField.getText().trim())));
            config.memoryRestartThresholdBytes = Math.max(0, Long.parseLong(memoryGbField.getText().trim())) * 1024L * 1024 * 1024;
            long newMaxFileSizeKb = Long.parseLong(maxFileSizeKb.getText().trim());
            long newPrivateMaxFileSizeKb = Long.parseLong(privateMaxFileSizeKb.getText().trim());
            if (newMaxFileSizeKb < 1 || newPrivateMaxFileSizeKb < 1) throw new IllegalArgumentException("文件大小上限必须为正整数 KB");
            config.localMinecraftClientPath = localMinecraftClient.getText().trim();
            config.maxFileSizeKb = newMaxFileSizeKb;
            config.privateMaxFileSizeKb = newPrivateMaxFileSizeKb;
            config.showMetadataProjectionName = showProjectionName.isSelected();
            config.showMetadataAuthor = showAuthor.isSelected();
            config.showMetadataCreatedAt = showCreatedAt.isSelected();
            config.showMetadataBlockStats = showBlockStats.isSelected();
            config.showMetadataSize = showSize.isSelected();
            config.showMetadataLitematicVersion = showLitematicVersion.isSelected();
            config.showMetadataGameVersion = showGameVersion.isSelected();
            config.metadataFormat = switch (metadataFormat.getSelectedIndex()) {
                case 1 -> "compact";
                case 2 -> "image";
                default -> "full";
            };
            config.save(configPath);
            log("本地渲染设置已保存，正在重启 Agent 使缓存目录、并行客户端和拼接设置生效");
            restartApplication(false);
        } catch (Exception error) { showError(error); }
    }

    private void saveProjectionSearchSettings(JTextField resultLimit) {
        try {
            int limit = Integer.parseInt(resultLimit.getText().trim());
            if (limit < 1 || limit > 100) throw new NumberFormatException();
            config.projectionSearchResultLimit = limit;
            config.save(configPath);
            log("搜索投影设置已保存：默认返回 " + limit + " 个结果");
            JOptionPane.showMessageDialog(this, "搜索投影设置已保存，下一次搜索立即生效。", "配置已应用", JOptionPane.INFORMATION_MESSAGE);
        } catch (NumberFormatException error) {
            showError(new RuntimeException("搜索结果数量必须是 1–100 之间的整数"));
        } catch (Exception error) {
            showError(error);
        }
    }

    private void saveCommandSettings(CommandEditorPanel editor, JTextArea commands) {
        try {
            AgentConfig staged = new AgentConfig();
            staged.commands = editor.values();
            staged.normalizeCommandDefinitions();
            OfficialSlashCommandPanels.validateConfiguredNames(staged);
            config.commands = staged.commands;
            config.automaticRenderingEnabled = editor.automaticRenderingEnabled();
            config.save(configPath);
            commands.setText(HelpCardRenderer.text(config));
            bots.syncOfficialCommandPanels();
            log("机器人功能开关、指令名称和别名已保存；官方 QQ / 指令面板正在同步");
            JOptionPane.showMessageDialog(this, "功能设置已保存并立即生效；官方 QQ 的 / 指令面板正在同步。", "配置已应用", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception error) {
            showError(error);
        }
    }

    private static final class CommandEditorPanel extends JPanel {
        private final List<CommandRow> rows = new ArrayList<>();
        private final JCheckBox automaticRendering;

        private CommandEditorPanel(List<AgentConfig.CommandDefinition> definitions, boolean automaticRenderingEnabled) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            JPanel automaticRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            automaticRendering = new JCheckBox("群内自动识别并渲染投影文件", automaticRenderingEnabled);
            automaticRow.add(automaticRendering);
            add(automaticRow);
            if (definitions != null) for (AgentConfig.CommandDefinition definition : definitions) {
                if (definition == null) continue;
                CommandRow row = new CommandRow(definition);
                rows.add(row);
                add(row.panel);
            }
        }

        private List<AgentConfig.CommandDefinition> values() {
            List<AgentConfig.CommandDefinition> result = new ArrayList<>();
            for (CommandRow row : rows) {
                AgentConfig.CommandDefinition definition = new AgentConfig.CommandDefinition(row.id, row.defaultName);
                definition.name = row.name.getText().trim();
                definition.enabled = row.enabled.isSelected();
                for (CommandRow.AliasRow alias : row.aliases) {
                    definition.aliases.add(new AgentConfig.CommandAlias(alias.name.getText().trim(), alias.enabled.isSelected()));
                }
                result.add(definition);
            }
            return result;
        }

        private boolean automaticRenderingEnabled() { return automaticRendering.isSelected(); }

        private static String label(String id) {
            return switch (id) {
                case "search" -> "搜索投影";
                case "sendProjection" -> "发送投影";
                case "sendMaterials" -> "导出材料";
                case "projectionList" -> "投影列表";
                case "help" -> "帮助";
                case "introduction" -> "机器人介绍";
                case "moreViews" -> "更多视图菜单";
                case "projectionView" -> "详细视图渲染";
                case "mapView" -> "地图配色视图";
                default -> id;
            };
        }

        private static final class CommandRow {
            private final String id;
            private final String defaultName;
            private final JPanel panel = new JPanel();
            private final JTextField name = new JTextField(14);
            private final JCheckBox enabled = new JCheckBox("启用功能", true);
            private final JPanel aliasPanel = new JPanel();
            private final List<AliasRow> aliases = new ArrayList<>();

            private CommandRow(AgentConfig.CommandDefinition definition) {
                id = definition.id;
                defaultName = definition.defaultName;
                panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
                panel.setBorder(BorderFactory.createTitledBorder(label(id)));
                JPanel nameLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
                name.setText(definition.name == null ? defaultName : definition.name);
                enabled.setSelected(definition.enabled);
                nameLine.add(enabled);
                nameLine.add(new JLabel("主指令名称"));
                nameLine.add(name);
                nameLine.add(new JLabel("默认：" + defaultName));
                JButton reset = new JButton("重置默认名称");
                reset.addActionListener(event -> name.setText(defaultName));
                nameLine.add(reset);
                panel.add(nameLine);

                aliasPanel.setLayout(new BoxLayout(aliasPanel, BoxLayout.Y_AXIS));
                if (definition.aliases != null) for (AgentConfig.CommandAlias alias : definition.aliases) {
                    if (alias != null) addAlias(alias.name, alias.enabled);
                }
                JPanel aliasHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
                aliasHeader.add(new JLabel("别名（可单独禁用）"));
                JButton add = new JButton("增加别名");
                add.addActionListener(event -> addAlias("", true));
                aliasHeader.add(add);
                panel.add(aliasHeader);
                panel.add(aliasPanel);
            }

            private void addAlias(String value, boolean enabledValue) {
                AliasRow alias = new AliasRow(value, enabledValue);
                aliases.add(alias);
                aliasPanel.add(alias.panel);
                aliasPanel.revalidate();
                aliasPanel.repaint();
            }

            private final class AliasRow {
                private final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
                private final JTextField name = new JTextField(14);
                private final JCheckBox enabled = new JCheckBox("启用", true);

                private AliasRow(String value, boolean enabledValue) {
                    name.setText(value == null ? "" : value);
                    enabled.setSelected(enabledValue);
                    panel.add(name);
                    panel.add(enabled);
                    JButton delete = new JButton("删除");
                    delete.addActionListener(event -> {
                        aliases.remove(this);
                        aliasPanel.remove(panel);
                        aliasPanel.revalidate();
                        aliasPanel.repaint();
                    });
                    panel.add(delete);
                }
            }
        }
    }

    private void chooseOutput(){JFileChooser f=new JFileChooser();f.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(f.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){String chosen=f.getSelectedFile().getAbsolutePath();outputDirectory.setText(chosen);persistOutputDirectory(chosen);}}
    private void renderLocal(JButton button) {
        if (viewTable.isEditing()) viewTable.getCellEditor().stopCellEditing();
        List<Path> inputs = new ArrayList<>();
        for (int i = 0; i < projectionModel.size(); i++) inputs.add(projectionModel.get(i));
        if (inputs.isEmpty() && !inputFile.getText().isBlank()) inputs.add(Path.of(inputFile.getText().trim()));
        if (inputs.isEmpty()) { showError(new RuntimeException("请先点击“导入投影”或拖入 .litematic 文件")); return; }
        List<RenderModels.View> renderViews = views.values();
        if (renderViews.isEmpty()) { showError(new RuntimeException("至少需要一个视角")); return; }
        String configuredOutput = outputDirectory.getText().trim();
        button.setEnabled(false);
        Thread.startVirtualThread(() -> {
            List<String> failures = new ArrayList<>(); int completed = 0;
            for (Path input : inputs) {
                try {
                    if (!Files.isRegularFile(input)) throw new java.io.IOException("文件不存在");
                    Path base = configuredOutput.isBlank()
                            ? (input.getParent() == null ? root.resolve("渲染结果") : input.getParent().resolve("渲染结果"))
                            : Path.of(configuredOutput);
                    Path output = base.resolve(renderFolderName(input.getFileName().toString()));
                    byte[] bytes = Files.readAllBytes(input);
                    var request = new RenderModels.Request(2, UUID.randomUUID().toString(), input.getFileName().toString(), renderViews, null);
                    var result = renderer.submit(request, bytes, Duration.ofMillis(config.renderTimeoutMillis), "本地", output.toString()).join();
                    Files.createDirectories(output);
                    for (var image : result.images()) Files.copy(image.path(), output.resolve(image.name()), StandardCopyOption.REPLACE_EXISTING);
                    if (config.localMergeEnabled && !AgentConfig.separateImageSend(config.cloudMergeLayout) && result.images().size() > 1) {
                        CloudConnection.MergedPng merged = CloudConnection.mergeImages(result.images(), renderViews.getFirst(), config.cloudMergeLayout);
                        if (merged != null) Files.write(output.resolve("merged.png"), merged.bytes());
                    }
                    config.outputDirectory = base.toString(); addRecent(config.recentOutputDirectories, base.toString());
                    completed++; log("本地渲染完成：" + input.getFileName() + " -> " + output);
                } catch (Throwable error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    failures.add(input.getFileName() + "：" + cause.getMessage()); log("本地渲染失败：" + input.getFileName() + "，" + cause.getMessage());
                }
            }
            try { config.save(configPath); } catch (Exception error) { log("保存输出目录失败：" + error.getMessage()); }
            int completedCount = completed;
            SwingUtilities.invokeLater(() -> {
                button.setEnabled(true);
                if (!failures.isEmpty()) showError(new RuntimeException("完成 " + completedCount + " 个，失败 " + failures.size() + " 个：\n" + String.join("\n", failures)));
            });
        });
    }
    static String renderFolderName(String fileName){String stem=fileName.endsWith(".litematic")?fileName.substring(0,fileName.length()-".litematic".length()):fileName;return stem+"-"+java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));}
    private static int sendLayoutIndex(String value) {
        return switch (AgentConfig.normalizeImageSendLayout(value)) {
            case "vertical" -> 1;
            case "separate" -> 2;
            default -> 0;
        };
    }
    private static String sendLayoutValue(int index) {
        return switch (index) {
            case 1 -> "vertical";
            case 2 -> "separate";
            default -> "horizontal";
        };
    }
    /** 材质包列表任何改动立即持久化到配置。 */
    private void syncResourcePacks(){List<AgentConfig.ResourcePackEntry> entries=new ArrayList<>();for(int i=0;i<packModel.size();i++)entries.add(packModel.get(i));config.resourcePacks=entries;try{config.save(configPath);}catch(Exception ex){log("保存设置失败："+ex.getMessage());}}

    /** 视角列表任何改动立即持久化到配置。 */
    private void syncViews(){List<AgentConfig.ViewEntry> entries=new ArrayList<>();for(var v:views.values())entries.add(new AgentConfig.ViewEntry(v.id(),v.name(),v.yaw(),v.pitch(),v.zoom(),v.width(),v.height(),v.supersampling(),v.background(),v.transparentBackground(),Boolean.TRUE.equals(v.autoFill()),v.brightnessFactor()));config.views=entries;try{config.save(configPath);}catch(Exception ex){log("保存视角失败："+ex.getMessage());}}
    private void addPack(){Path chosen=NativeFilePicker.chooseFile("选择资源包","Minecraft 资源包","*.zip");if(chosen!=null){packModel.addElement(new AgentConfig.ResourcePackEntry(chosen.toString(),true));syncResourcePacks();}}
    private void movePack(JList<?> list,int delta){int from=list.getSelectedIndex(),to=from+delta;if(from<0||to<0||to>=packModel.size())return;var value=packModel.remove(from);packModel.add(to,value);list.setSelectedIndex(to);syncResourcePacks();}
    private void applyPacks(JButton button){List<AgentConfig.ResourcePackEntry> entries=new ArrayList<>();for(int i=0;i<packModel.size();i++)entries.add(packModel.get(i));button.setEnabled(false);Thread.startVirtualThread(()->{try{new ResourcePackManager(config,renderer.runtime(),this::log).applyTransactional(entries);config.save(configPath);}catch(Throwable e){SwingUtilities.invokeLater(()->showError(e));}finally{SwingUtilities.invokeLater(()->button.setEnabled(true));}});}
    @SuppressWarnings("unchecked") private void installDropTarget(){
        installProjectionDropTarget(projectionList);
        installProjectionDropTarget(inputFile);
        for (Component target : projectionDropTargets) installProjectionDropTarget(target);
    }
    @SuppressWarnings("unchecked") private void installProjectionDropTarget(Component target){new DropTarget(target,DnDConstants.ACTION_COPY,null){@Override public synchronized void drop(DropTargetDropEvent event){try{event.acceptDrop(DnDConstants.ACTION_COPY);List<java.io.File> files=(List<java.io.File>)event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);importProjectionFiles(files.stream().map(java.io.File::toPath).toList());event.dropComplete(true);}catch(Exception e){event.dropComplete(false);}}};}
    private void installTray(){if(!SystemTray.isSupported())return;try{BufferedImage image=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();g.setColor(new Color(55,145,90));g.fillRect(2,2,12,12);g.dispose();PopupMenu menu=new PopupMenu();MenuItem show=new MenuItem("打开");show.addActionListener(e->SwingUtilities.invokeLater(()->{setVisible(true);setState(NORMAL);}));MenuItem exit=new MenuItem("退出");exit.addActionListener(e->shutdown());menu.add(show);menu.add(exit);trayIcon=new TrayIcon(image,"Litematic GPU Agent",menu);trayIcon.setImageAutoSize(true);trayIcon.addActionListener(e->setVisible(true));SystemTray.getSystemTray().add(trayIcon);}catch(Exception e){log("托盘初始化失败："+e.getMessage());}}
    private void refreshStatus(){runtimeStatus.setText("运行时："+(renderer.runtime().isAlive()?(renderer.isBusy()?"渲染中":"已启动"):"未启动")+(config.maxConcurrentRenders>1?"（并行 "+config.maxConcurrentRenders+"）":"")+(renderer.isDraining()?"【等待重启】":""));cloudStatus.setText("队列："+renderer.queueLength()+"（"+String.format(java.util.Locale.ROOT,"%.0f",renderer.retainedRequestBytes()/1024.0/1024.0)+"MB）");long memoryBytes=watchdog.lastReportedTotalBytes();memoryStatus.setText("内存："+(memoryBytes>0?String.format(java.util.Locale.ROOT,"%.1f",memoryBytes/1024.0/1024/1024)+"GB"+(config.memoryRestartThresholdBytes>0?"/"+config.memoryRestartThresholdBytes/(1024L*1024*1024)+"GB":""):"-"));var attachmentDownload=bots.downloadProgress();if(attachmentDownload.active()||!attachmentDownload.error().isBlank())updateAttachmentDownloadProgress(attachmentDownload);else updateRuntimeInstallProgress(renderer.runtime().installProgress());String current=renderer.currentFile();if(current==null){currentTaskLabel.setText("当前渲染：无");}else{var status=renderer.runtime().currentStatus();String stage=status!=null&&status.stage()!=null?status.stage():"";int percent=status!=null?(int)Math.round(status.progress()*100):0;currentTaskLabel.setText("当前渲染["+(renderer.currentSource() == null ? "本地" : renderer.currentSource())+"]："+current+(stage.isEmpty()?"":"（"+stage+(percent>0?" "+percent+"%":"")+"）"));}}

    private void updateAttachmentDownloadProgress(BotManager.DownloadProgress value){
        if(value==null)return;
        runtimeInstallProgress.setIndeterminate(value.active()&&value.totalBytes()<=0);
        if(value.totalBytes()>0)runtimeInstallProgress.setValue((int)Math.round(value.fraction()*100));
        String text="投影下载"+(value.file().isBlank()?"":" "+value.file());
        if(value.totalBytes()>0)text+=" "+formatBytes(value.downloadedBytes())+" / "+formatBytes(value.totalBytes())+"（"+(int)Math.round(value.fraction()*100)+"%）";
        else if(value.downloadedBytes()>0)text+=" "+formatBytes(value.downloadedBytes())+" / 总量未知";
        if(!value.error().isBlank())text+=" "+value.error();
        runtimeInstallProgress.setString(text);
    }
    private void updateRuntimeInstallProgress(RuntimeInstaller.InstallProgress value){
        if(value==null)return;
        boolean active=value.totalFiles()>value.completedFiles() || (value.stage()!=null && value.stage().contains("下载"));
        runtimeInstallProgress.setIndeterminate(active && value.totalBytes()<=0);
        if(value.totalBytes()>0)runtimeInstallProgress.setValue((int)Math.round(value.fraction()*100));
        String text=value.stage()==null?"":value.stage(); if(value.totalBytes()>0)text+=" "+formatBytes(value.completedBytes())+" / "+formatBytes(value.totalBytes())+"（"+(int)Math.round(value.fraction()*100)+"%）"; if(value.currentFile()!=null&&!value.currentFile().isBlank())text+=" "+value.currentFile();
        runtimeInstallProgress.setString(active?text:(value.stage()==null?"":value.stage()));
    }
    private static String formatBytes(long bytes){if(bytes<1024*1024)return (bytes/1024)+" KB";if(bytes<1024L*1024*1024)return String.format(java.util.Locale.ROOT,"%.1f MB",bytes/1024.0/1024);return String.format(java.util.Locale.ROOT,"%.2f GB",bytes/1024.0/1024/1024);}
    private void addHistory(String fileName,int count,long elapsed,String status,String location){SwingUtilities.invokeLater(()->{String time=LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"));historyLocations.add(0,location);history.insertRow(0,new Object[]{time,fileName,count,elapsed+" ms",status});while(historyLocations.size()>200)historyLocations.remove(historyLocations.size()-1);while(history.getRowCount()>200)history.removeRow(history.getRowCount()-1);});}
    private void log(String message){persistLog(message);SwingUtilities.invokeLater(()->{logs.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))+"  "+message+System.lineSeparator());logs.setCaretPosition(logs.getDocument().getLength());});}
    private void showError(Throwable error){JOptionPane.showMessageDialog(this,error.getMessage(),"操作失败",JOptionPane.ERROR_MESSAGE);log("错误："+error.getMessage());}
    /** 重启程序：复用当前进程的启动命令，兼容 jpackage EXE、java -jar 和开发环境。 */
    private void restartApplication(boolean saveFirst) {
        if (saveFirst) {
            try { config.save(configPath); } catch (Exception ex) { showError(ex); return; }
        }
        List<String> launch;
        try { launch = ProcessRelauncher.currentCommand(); }
        catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "无法取得当前启动命令，请手动重启程序。", "重启程序", JOptionPane.WARNING_MESSAGE);
            log("重启程序失败：" + ex.getMessage());
            return;
        }
        try { new ProcessBuilder(launch).directory(ProcessRelauncher.workingDirectory(launch).toFile()).start(); }
        catch (Exception ex) { showError(ex); return; }
        log("正在重启程序...");
        shutdown();
    }

    private void saveAndReloadConfiguration() {
        try {
            config.save(configPath);
            cloud.reload();
            bots.reload();
            log("配置已保存并重载云端与机器人连接");
            JOptionPane.showMessageDialog(this, "配置已保存并重载。监听地址、端口和 Java 路径仍需重启程序。", "配置已应用", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception error) {
            showError(error);
        }
    }

    static List<String> restartCommand(String command, String[] arguments) {
        return ProcessRelauncher.command(command, arguments);
    }

    private void confirmWindowClose() {
        if (trayIcon == null) {
            confirmExit();
            return;
        }
        Object[] options = closeOptions();
        int selected = JOptionPane.showOptionDialog(this,
                "关闭窗口时要最小化到托盘，还是退出程序？\n直接退出会同时关闭内置 Minecraft 渲染客户端。",
                "关闭 Litematic GPU Agent", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (selected == 0) {
            setVisible(false);
            trayIcon.displayMessage("Litematic GPU Agent", "程序仍在托盘运行。", TrayIcon.MessageType.INFO);
        } else if (selected == 1) {
            shutdown();
        }
    }

    static Object[] closeOptions() {
        return new Object[] {"最小化到托盘", "直接退出", "取消"};
    }

    private void confirmExit() {
        int selected = JOptionPane.showConfirmDialog(this,
                "确定退出程序吗？内置 Minecraft 渲染客户端也会一并关闭。",
                "退出 Litematic GPU Agent", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (selected == JOptionPane.YES_OPTION) shutdown();
    }

    private void closeServices() {
        if (!servicesClosed.compareAndSet(false, true)) return;
        try { watchdog.close(); } catch (Throwable error) { log("关闭内存看门狗失败：" + error.getMessage()); }
        try { bots.close(); } catch (Throwable error) { log("关闭机器人连接失败：" + error.getMessage()); }
        try { web.close(); } catch (Throwable error) { log("关闭 Web 管理后台失败：" + error.getMessage()); }
        try { preview.closeForShutdown(); } catch (Throwable error) { log("关闭预览失败：" + error.getMessage()); }
        try { cloud.close(); } catch (Throwable error) { log("关闭云端连接失败：" + error.getMessage()); }
        try { httpServer.close(); } catch (Throwable error) { log("关闭 HTTP 服务失败：" + error.getMessage()); }
        try { renderer.close(); } catch (Throwable error) { log("关闭渲染服务失败：" + error.getMessage()); }
    }

    private void shutdown(){
        if (!shuttingDown.compareAndSet(false, true)) return;
        setVisible(false);
        dispose();
        // 清理可能包含 Minecraft 进程回收的阻塞操作，不能卡在 Swing EDT 上。
        Thread.startVirtualThread(() -> {
            try { closeServices(); }
            finally {
                try { if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon); } catch (Throwable ignored) {}
                System.exit(0);
            }
        });
    }
}
