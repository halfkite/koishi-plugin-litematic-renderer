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

final class AgentFrame extends JFrame {
    private final Path root;
    private final Path configPath;
    private final AgentConfig config;
    private final RenderService renderer;
    private final HttpV1Server httpServer;
    private final CloudConnection cloud;
    private final MemoryWatchdog watchdog;
    private final JTextArea logs = new JTextArea();
    private final JLabel runtimeStatus = new JLabel("运行时：未启动");
    private final JLabel memoryStatus = new JLabel("内存：-");
    private final JLabel cloudStatus = new JLabel("云端：等待连接");
    private final JLabel currentTaskLabel = new JLabel("当前渲染：无");
    private final ViewTableModel views = new ViewTableModel();
    private final JTable viewTable = new JTable(views);
    private final JTextField inputFile = new JTextField();
    private final JTextField outputDirectory = new JTextField();
    private final PreviewPanel preview;
    private final DefaultTableModel history = new DefaultTableModel(new String[] {"时间", "文件", "视角", "耗时", "状态"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final DefaultListModel<AgentConfig.ResourcePackEntry> packModel = new DefaultListModel<>();
    private final java.util.ArrayList<String> historyLocations = new java.util.ArrayList<>();
    private TrayIcon trayIcon;

    AgentFrame(Path root, Path configPath, AgentConfig config) {
        super("Litematic GPU Agent");
        this.root = root; this.configPath = configPath; this.config = config;
        this.renderer = new RenderService(root, config);
        this.renderer.setLog(this::log);
        this.httpServer = new HttpV1Server(config, renderer, this::log);
        this.cloud = new CloudConnection(config, renderer, this::log);
        this.watchdog = new MemoryWatchdog(config, renderer, renderer.runtime(), root, this::log);
        this.watchdog.start();
        this.preview = new PreviewPanel(renderer, renderer.runtime(), config,
                () -> outputDirectory.getText(), this::log, this::showError,
                path -> {
                    inputFile.setText(path.toString());
                    if (outputDirectory.getText().isBlank())
                        outputDirectory.setText(path.getParent().resolve("渲染结果").toString());
                });
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(900, 640));
        setSize(1080, 760);
        setLocationRelativeTo(null);
        setContentPane(buildUi());
        installDropTarget();
        installTray();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { if (config.minimizeToTray && trayIcon != null) setVisible(false); else shutdown(); }
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
                loaded.add(new RenderModels.View(entry.id(), entry.name(), entry.yaw(), entry.pitch(), entry.zoom(), true, entry.width(), entry.height(), entry.background(), entry.transparentBackground(), entry.supersampling()));
            }
            views.reset(loaded);
        } else if (config.renderWidth > 0 && config.renderHeight > 0) {
            views.applyResolutionToAll(config.renderWidth, config.renderHeight);
        }
        new Timer(1000, event -> refreshStatus()).start();
        Thread.startVirtualThread(() -> {
            try { httpServer.start(); } catch (Exception error) { log("HTTP v1 启动失败：" + error.getMessage()); }
            cloud.start();
        });
    }

    private JComponent buildUi() {
        JPanel rootPanel = new JPanel(new BorderLayout());
        JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 6));
        status.add(runtimeStatus); status.add(cloudStatus); status.add(currentTaskLabel); status.add(memoryStatus); status.add(new JLabel("Minecraft 26.2 / Java 25"));
        JButton restartApp = new JButton("重启程序");
        restartApp.addActionListener(e -> restartApplication(false));
        status.add(restartApp);
        rootPanel.add(status, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("本地渲染", localPanel()); tabs.addTab("预览", preview);
        tabs.addTab("任务历史", historyPanel());
        tabs.addTab("资源包", resourcePackPanel()); tabs.addTab("连接设置", settingsPanel()); tabs.addTab("日志", logPanel());
        rootPanel.add(tabs, BorderLayout.CENTER);
        return rootPanel;
    }

    private JComponent localPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8)); panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel files = new JPanel(new GridBagLayout()); GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(3,3,3,3); c.fill = GridBagConstraints.HORIZONTAL;
        JButton inputOpen = new JButton("打开位置"); inputOpen.addActionListener(e -> openLocation(inputFile.getText()));
        JButton inputHistory = new JButton(); inputHistory.addActionListener(e -> showRecentMenu(inputFile, config.recentProjectionPaths, this::setInputPath));
        JButton chooseInput = new JButton("选择..."); chooseInput.addActionListener(e -> chooseInput());
        JPanel inputButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0)); inputButtons.add(inputOpen); inputButtons.add(chooseInput);
        c.gridx=0; c.gridy=0; c.weightx=0; files.add(new JLabel("投影文件"), c); c.gridx=1; c.weightx=1; files.add(fieldWithArrow(inputFile, inputHistory),c);
        c.gridx=2; c.weightx=0; files.add(inputButtons,c);
        JButton outputOpen = new JButton("打开位置"); outputOpen.addActionListener(e -> openLocation(outputDirectory.getText()));
        JButton outputHistory = new JButton(); outputHistory.addActionListener(e -> showRecentMenu(outputDirectory, config.recentOutputDirectories, this::setOutputPath));
        JButton chooseOutput = new JButton("选择..."); chooseOutput.addActionListener(e -> chooseOutput());
        JPanel outputButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0)); outputButtons.add(outputOpen); outputButtons.add(chooseOutput);
        c.gridx=0; c.gridy=1; files.add(new JLabel("输出目录"),c); c.gridx=1; c.weightx=1; files.add(fieldWithArrow(outputDirectory, outputHistory),c);
        c.gridx=2; c.weightx=0; files.add(outputButtons,c);
        JTextField widthField = new JTextField(String.valueOf(Math.max(64, Math.min(4096, config.renderWidth))), 6);
        JTextField heightField = new JTextField(String.valueOf(Math.max(64, Math.min(4096, config.renderHeight))), 6);
        JButton applyResolution = new JButton("应用到全部视角");
        java.awt.event.ActionListener applyResolutionAction = e -> {
            try {
                int width = Integer.parseInt(widthField.getText().trim());
                int height = Integer.parseInt(heightField.getText().trim());
                if (width < 64 || width > 4096 || height < 64 || height > 4096) throw new NumberFormatException();
                config.renderWidth = width;
                config.renderHeight = height;
                views.applyResolutionToAll(width, height);
            } catch (Exception ex) {
                widthField.setText(String.valueOf(Math.max(64, Math.min(4096, config.renderWidth))));
                heightField.setText(String.valueOf(Math.max(64, Math.min(4096, config.renderHeight))));
                showError(new RuntimeException("宽和高需为 64–4096 之间的整数，可分别设置"));
            }
        };
        applyResolution.addActionListener(applyResolutionAction);
        widthField.addActionListener(applyResolutionAction);
        heightField.addActionListener(applyResolutionAction);
        JPanel resolutionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        resolutionPanel.add(new JLabel("宽"));
        resolutionPanel.add(widthField);
        resolutionPanel.add(new JLabel("高"));
        resolutionPanel.add(heightField);
        resolutionPanel.add(applyResolution);
        c.gridx=0; c.gridy=2; files.add(new JLabel("分辨率"),c); c.gridx=1; c.weightx=1; files.add(resolutionPanel,c);
        panel.add(files, BorderLayout.NORTH);
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
        panel.add(new JScrollPane(viewTable), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton add = new JButton("添加视角"); add.addActionListener(e -> views.addView());
        JButton remove = new JButton("删除视角"); remove.addActionListener(e -> views.remove(viewTable.getSelectedRow()));
        JButton render = new JButton("开始渲染"); render.addActionListener(e -> renderLocal(render));
        JButton cancel = new JButton("终止任务"); cancel.addActionListener(e -> renderer.cancelAll());
        buttons.add(add); buttons.add(remove); buttons.add(render); buttons.add(cancel); panel.add(buttons, BorderLayout.SOUTH);
        return panel;
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
        inputFile.setText(path);
        try { preview.fileChanged(Path.of(path)); } catch (Exception ignored) {}
        addRecent(config.recentProjectionPaths, path);
        try { config.save(configPath); } catch (Exception ex) { log("保存设置失败：" + ex.getMessage()); }
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
        JTextField idleStop = new JTextField(String.valueOf(config.renderIdleStopMillis),30);
        JTextField cacheDir = new JTextField(config.cacheDirectory == null ? "" : config.cacheDirectory, 30);
        JCheckBox keepProjection = new JCheckBox("保存投影文件到缓存", config.cacheKeepProjections);
        JTextField cacheMaxGb = new JTextField(String.valueOf(Math.max(1, config.cacheMaxBytes / (1024L * 1024 * 1024))), 6);
        JTextField concurrentField = new JTextField(String.valueOf(Math.max(1, config.maxConcurrentRenders)), 6);
        JTextField memoryGbField = new JTextField(String.valueOf(config.memoryRestartThresholdBytes / (1024L * 1024 * 1024)), 6);
        JCheckBox startup = new JCheckBox("随 Windows 登录启动",config.startWithWindows); JCheckBox tray = new JCheckBox("关闭窗口时最小化到托盘",config.minimizeToTray);
        GridBagConstraints c=new GridBagConstraints();c.insets=new Insets(6,6,6,6);c.fill=GridBagConstraints.HORIZONTAL;c.anchor=GridBagConstraints.WEST;
        addSetting(panel,c,0,"Agent ID",id);addSetting(panel,c,1,"共享密钥",secret);addSetting(panel,c,2,"WebSocket 地址",url);addSetting(panel,c,3,"空闲自动关闭(毫秒,0=不关闭)",idleStop);
        addSetting(panel,c,4,"缓存目录(空=默认)",cacheDir);
        JButton openCache = new JButton("打开缓存");
        openCache.addActionListener(e -> {
            Path dir = renderer.cacheDirectory();
            try { Files.createDirectories(dir); } catch (Exception ignored) {}
            try { new ProcessBuilder("explorer", dir.toAbsolutePath().toString()).start(); }
            catch (Exception ex) { showError(ex); }
        });
        JPanel cacheDirPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        cacheDirPanel.add(cacheDir); cacheDirPanel.add(openCache);
        c.gridx=1;c.gridy=4;c.weightx=1;panel.add(cacheDirPanel,c);
        c.gridx=1;c.gridy=5;panel.add(enabled,c);
        c.gridx=1;c.gridy=6;panel.add(keepProjection,c);
        JPanel cacheMaxPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        cacheMaxPanel.add(cacheMaxGb); cacheMaxPanel.add(new JLabel("GB（超出自动清理最旧文件）"));
        addSetting(panel,c,7,"缓存容量上限",cacheMaxPanel);
        JPanel concurrentPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        concurrentPanel.add(concurrentField); concurrentPanel.add(new JLabel("个（1-4，同时渲染的 Minecraft 客户端数量，重启后生效）"));
        addSetting(panel,c,8,"并行渲染数",concurrentPanel);
        JPanel memoryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        memoryPanel.add(memoryGbField); memoryPanel.add(new JLabel("GB（工具+渲染端总内存超限后，手头任务完成即自动重启；0=关闭）"));
        addSetting(panel,c,9,"内存重启阈值",memoryPanel);
        c.gridx=1;c.gridy=10;panel.add(startup,c);c.gridy=11;panel.add(tray,c);
        JLabel warning=new JLabel("公网 ws:// 不加密投影内容，建议使用 wss://");warning.setForeground(new Color(170,70,0));c.gridy=12;panel.add(warning,c);
        JButton save=new JButton("保存设置");save.addActionListener(e->{try{config.agentId=id.getText().trim();config.sharedSecret=new String(secret.getPassword());config.cloudWebSocketUrl=url.getText().trim();config.cloudEnabled=enabled.isSelected();config.renderIdleStopMillis=Math.max(0,Integer.parseInt(idleStop.getText().trim()));config.cacheDirectory=cacheDir.getText().trim();config.cacheKeepProjections=keepProjection.isSelected();config.cacheMaxBytes=Math.max(1,Long.parseLong(cacheMaxGb.getText().trim()))*1024L*1024*1024;config.maxConcurrentRenders=Math.max(1,Math.min(4,Integer.parseInt(concurrentField.getText().trim())));config.memoryRestartThresholdBytes=Math.max(0,Long.parseLong(memoryGbField.getText().trim()))*1024L*1024*1024;config.startWithWindows=startup.isSelected();config.minimizeToTray=tray.isSelected();config.save(configPath);StartupManager.setEnabled(config.startWithWindows);JOptionPane.showMessageDialog(this,"设置已保存。并行渲染数与网络设置重启 Agent 后生效，内存阈值立即生效。", "保存成功",JOptionPane.INFORMATION_MESSAGE);}catch(Exception ex){showError(ex);}});c.gridy=13;c.fill=GridBagConstraints.NONE;panel.add(save,c);
        JButton saveRestart=new JButton("保存并重启");saveRestart.addActionListener(e->{save.doClick();restartApplication(false);});c.gridx=2;c.gridy=13;panel.add(saveRestart,c);
        return new JScrollPane(panel);
    }

    private static void addSetting(JPanel panel, GridBagConstraints c, int row, String name, JComponent field) { c.gridy=row;c.gridx=0;c.weightx=0;panel.add(new JLabel(name),c);c.gridx=1;c.weightx=1;panel.add(field,c); }
    private JComponent logPanel() { logs.setEditable(false); logs.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12)); return new JScrollPane(logs); }

    private void chooseInput(){Path chosen=NativeFilePicker.chooseFile("选择投影文件","Litematica 投影","*.litematic");if(chosen!=null){inputFile.setText(chosen.toString());preview.fileChanged(chosen);addRecent(config.recentProjectionPaths,chosen.toString());try{config.save(configPath);}catch(Exception ignored){}}}
    private void chooseOutput(){JFileChooser f=new JFileChooser();f.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);if(f.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){String chosen=f.getSelectedFile().getAbsolutePath();outputDirectory.setText(chosen);persistOutputDirectory(chosen);}}
    private void renderLocal(JButton button){Path input=Path.of(inputFile.getText());Path base=outputDirectory.getText().isBlank()?input.getParent().resolve("渲染结果"):Path.of(outputDirectory.getText());Path output=base.resolve(renderFolderName(input.getFileName().toString()));button.setEnabled(false);Thread.startVirtualThread(()->{try{byte[] bytes=Files.readAllBytes(input);var request=new RenderModels.Request(2,UUID.randomUUID().toString(),input.getFileName().toString(),views.values(),null);var result=renderer.submit(request,bytes,Duration.ofMillis(config.renderTimeoutMillis),"本地",output.toString()).join();Files.createDirectories(output);for(var image:result.images())Files.copy(image.path(),output.resolve(image.name()),StandardCopyOption.REPLACE_EXISTING);config.outputDirectory=base.toString();addRecent(config.recentOutputDirectories,base.toString());try{config.save(configPath);}catch(Exception ignored){}log("本地渲染完成："+output);}catch(Throwable error){Throwable cause=error.getCause()==null?error:error.getCause();SwingUtilities.invokeLater(()->showError(cause));}finally{SwingUtilities.invokeLater(()->button.setEnabled(true));}});}
    static String renderFolderName(String fileName){String stem=fileName.endsWith(".litematic")?fileName.substring(0,fileName.length()-".litematic".length()):fileName;return stem+"-"+java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));}
    /** 材质包列表任何改动立即持久化到配置。 */
    private void syncResourcePacks(){List<AgentConfig.ResourcePackEntry> entries=new ArrayList<>();for(int i=0;i<packModel.size();i++)entries.add(packModel.get(i));config.resourcePacks=entries;try{config.save(configPath);}catch(Exception ex){log("保存设置失败："+ex.getMessage());}}

    /** 视角列表任何改动立即持久化到配置。 */
    private void syncViews(){List<AgentConfig.ViewEntry> entries=new ArrayList<>();for(var v:views.values())entries.add(new AgentConfig.ViewEntry(v.id(),v.name(),v.yaw(),v.pitch(),v.zoom(),v.width(),v.height(),v.supersampling(),v.background(),v.transparentBackground()));config.views=entries;try{config.save(configPath);}catch(Exception ex){log("保存视角失败："+ex.getMessage());}}
    private void addPack(){Path chosen=NativeFilePicker.chooseFile("选择资源包","Minecraft 资源包","*.zip");if(chosen!=null){packModel.addElement(new AgentConfig.ResourcePackEntry(chosen.toString(),true));syncResourcePacks();}}
    private void movePack(JList<?> list,int delta){int from=list.getSelectedIndex(),to=from+delta;if(from<0||to<0||to>=packModel.size())return;var value=packModel.remove(from);packModel.add(to,value);list.setSelectedIndex(to);syncResourcePacks();}
    private void applyPacks(JButton button){List<AgentConfig.ResourcePackEntry> entries=new ArrayList<>();for(int i=0;i<packModel.size();i++)entries.add(packModel.get(i));button.setEnabled(false);Thread.startVirtualThread(()->{try{new ResourcePackManager(config,renderer.runtime(),this::log).applyTransactional(entries);config.save(configPath);}catch(Throwable e){SwingUtilities.invokeLater(()->showError(e));}finally{SwingUtilities.invokeLater(()->button.setEnabled(true));}});}
    @SuppressWarnings("unchecked") private void installDropTarget(){new DropTarget(this,DnDConstants.ACTION_COPY,null){@Override public synchronized void drop(DropTargetDropEvent event){try{event.acceptDrop(DnDConstants.ACTION_COPY);List<java.io.File> files=(List<java.io.File>)event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);if(!files.isEmpty()&&files.getFirst().getName().toLowerCase().endsWith(".litematic")){Path chosen=files.getFirst().toPath();inputFile.setText(chosen.toString());preview.fileChanged(chosen);addRecent(config.recentProjectionPaths,chosen.toString());try{config.save(configPath);}catch(Exception ignored){}}event.dropComplete(true);}catch(Exception e){event.dropComplete(false);}}};}
    private void installTray(){if(!SystemTray.isSupported())return;try{BufferedImage image=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();g.setColor(new Color(55,145,90));g.fillRect(2,2,12,12);g.dispose();PopupMenu menu=new PopupMenu();MenuItem show=new MenuItem("打开");show.addActionListener(e->SwingUtilities.invokeLater(()->{setVisible(true);setState(NORMAL);}));MenuItem exit=new MenuItem("退出");exit.addActionListener(e->shutdown());menu.add(show);menu.add(exit);trayIcon=new TrayIcon(image,"Litematic GPU Agent",menu);trayIcon.setImageAutoSize(true);trayIcon.addActionListener(e->setVisible(true));SystemTray.getSystemTray().add(trayIcon);}catch(Exception e){log("托盘初始化失败："+e.getMessage());}}
    private void refreshStatus(){runtimeStatus.setText("运行时："+(renderer.runtime().isAlive()?(renderer.isBusy()?"渲染中":"已启动"):"未启动")+(config.maxConcurrentRenders>1?"（并行 "+config.maxConcurrentRenders+"）":"")+(renderer.isDraining()?"【等待重启】":""));cloudStatus.setText("队列："+renderer.queueLength());long memoryBytes=watchdog.lastReportedTotalBytes();memoryStatus.setText("内存："+(memoryBytes>0?String.format(java.util.Locale.ROOT,"%.1f",memoryBytes/1024.0/1024/1024)+"GB"+(config.memoryRestartThresholdBytes>0?"/"+config.memoryRestartThresholdBytes/(1024L*1024*1024)+"GB":""):"-"));String current=renderer.currentFile();if(current==null){currentTaskLabel.setText("当前渲染：无");}else{var status=renderer.runtime().currentStatus();String stage=status!=null&&status.stage()!=null?status.stage():"";int percent=status!=null?(int)Math.round(status.progress()*100):0;currentTaskLabel.setText("当前渲染["+(renderer.currentSource() == null ? "本地" : renderer.currentSource())+"]："+current+(stage.isEmpty()?"":"（"+stage+(percent>0?" "+percent+"%":"")+"）"));}}
    private void addHistory(String fileName,int count,long elapsed,String status,String location){SwingUtilities.invokeLater(()->{String time=LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"));historyLocations.add(0,location);history.insertRow(0,new Object[]{time,fileName,count,elapsed+" ms",status});while(historyLocations.size()>200)historyLocations.remove(historyLocations.size()-1);while(history.getRowCount()>200)history.removeRow(history.getRowCount()-1);});}
    private void log(String message){SwingUtilities.invokeLater(()->{logs.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))+"  "+message+System.lineSeparator());logs.setCaretPosition(logs.getDocument().getLength());});}
    private void showError(Throwable error){JOptionPane.showMessageDialog(this,error.getMessage(),"操作失败",JOptionPane.ERROR_MESSAGE);log("错误："+error.getMessage());}
    /** 重启程序：以当前可执行文件重新拉起自身后退出（仅支持 jpackage/exe 启动方式）。 */
    private void restartApplication(boolean saveFirst) {
        if (saveFirst) {
            try { config.save(configPath); } catch (Exception ex) { showError(ex); return; }
        }
        String command = ProcessHandle.current().info().command().orElse("");
        var arguments = ProcessHandle.current().info().arguments().orElse(new String[0]);
        if (!command.toLowerCase().endsWith(".exe") || arguments.length > 0) {
            JOptionPane.showMessageDialog(this, "当前以非安装方式运行，无法自动重启，请手动重启程序。", "重启程序", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try { new ProcessBuilder(command).start(); }
        catch (Exception ex) { showError(ex); return; }
        log("正在重启程序...");
        shutdown();
    }

    private void shutdown(){preview.closeForShutdown();cloud.close();httpServer.close();renderer.close();if(trayIcon!=null)SystemTray.getSystemTray().remove(trayIcon);dispose();System.exit(0);}
}
