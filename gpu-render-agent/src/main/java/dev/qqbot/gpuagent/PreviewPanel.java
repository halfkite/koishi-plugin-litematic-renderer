package dev.qqbot.gpuagent;

import dev.qqbot.standalone.PreviewEngine;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 内嵌软件预览画布：复用 standalone-renderer 的 CPU 光栅化，实现拖拽旋转、滚轮缩放、
 * 预设视角和 180° 快捷旋转；当前相机可直接导出为 GPU 高清图（所见即所得）。
 * 默认不启用（烘焙大投影会占用 CPU/内存），由「本地渲染」页的开关控制。
 * 引擎与帧渲染共用单线程 worker 串行执行（PreviewEngine 非线程安全）。
 */
final class PreviewPanel extends JPanel {
    /** GPU 端 zoom 与取景比例的换算基准，与 Koishi 插件 isometricFill→zoom 的映射保持一致。 */
    private static final double GPU_ZOOM_BASE = 0.95;
    private static final String EXPORT_VIEW_ID = "preview-export";

    private final RenderService renderer;
    private final RuntimeManager runtime;
    private final AgentConfig config;
    private final Consumer<String> log;
    private final Consumer<Throwable> showError;
    private final Consumer<Path> onFileChosen;
    private final Supplier<String> outputPath;

    private final PreviewCanvas canvas = new PreviewCanvas();
    private final JLabel status = new JLabel("预览未启用");
    private final JCheckBox enabled = new JCheckBox("启用软件预览");
    private final JLabel cameraLabel = new JLabel();
    private final JButton exportButton = new JButton("导出当前视角（GPU 2048）");

    private volatile PreviewEngine engine;
    private volatile Path currentInput;
    private double yaw = 135;
    private double pitch = 36;
    private double zoom = 1.0;
    private final AtomicBoolean renderBusy = new AtomicBoolean();
    private final AtomicBoolean fastRequested = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "preview-render");
        thread.setDaemon(true);
        return thread;
    });

    PreviewPanel(RenderService renderer, RuntimeManager runtime, AgentConfig config,
                 Supplier<String> outputPath, Consumer<String> log, Consumer<Throwable> showError,
                 Consumer<Path> onFileChosen) {
        this.renderer = renderer;
        this.runtime = runtime;
        this.config = config;
        this.outputPath = outputPath;
        this.log = log;
        this.showError = showError;
        this.onFileChosen = onFileChosen;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton chooseFile = new JButton("选择投影...");
        chooseFile.addActionListener(event -> chooseFile());
        top.add(chooseFile);
        top.add(enabled);
        top.add(status);
        enabled.addActionListener(event -> {
            if (enabled.isSelected()) loadAsync();
            else { closeEngine(); status.setText("预览未启用"); canvas.clear(); }
        });
        add(top, BorderLayout.NORTH);

        Point[] dragOrigin = new Point[] {null};
        canvas.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { dragOrigin[0] = event.getPoint(); canvas.requestFocusInWindow(); }
            @Override public void mouseReleased(MouseEvent event) { dragOrigin[0] = null; requestFrame(false); }
        });
        canvas.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseDragged(MouseEvent event) {
                if (dragOrigin[0] == null) return;
                int dx = event.getX() - dragOrigin[0].x;
                int dy = event.getY() - dragOrigin[0].y;
                dragOrigin[0] = event.getPoint();
                if (engine == null) return;
                yaw = (yaw + dx * 0.6) % 360;
                pitch = Math.max(-90, Math.min(90, pitch + dy * 0.6));
                requestFrame(true);
            }
        });
        canvas.addMouseWheelListener((MouseWheelEvent event) -> {
            if (engine == null) return;
            double factor = Math.pow(1.15, -event.getPreciseWheelRotation());
            zoom = Math.max(0.2, Math.min(8.0, zoom * factor));
            requestFrame(true);
        });
        canvas.getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("SPACE"), "rotate-180");
        canvas.getActionMap().put("rotate-180", rotate180Action());
        new DropTarget(canvas, DnDConstants.ACTION_COPY, null, true) {
            @Override public synchronized void drop(DropTargetDropEvent event) {
                try {
                    event.acceptDrop(DnDConstants.ACTION_COPY);
                    List<java.io.File> files = (List<java.io.File>) event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty() && files.getFirst().getName().toLowerCase().endsWith(".litematic")) {
                        applyFile(files.getFirst().toPath());
                        event.dropComplete(true);
                    } else event.dropComplete(false);
                } catch (Exception error) { event.dropComplete(false); }
            }
        };
        add(canvas, BorderLayout.CENTER);

        add(buildToolbar(), BorderLayout.SOUTH);
    }

    /** 预览页内选择/拖入投影：自动启用预览并加载，同时同步路径给本地渲染页。 */
    private void chooseFile() {
        Path chosen = NativeFilePicker.chooseFile("选择投影文件", "Litematica 投影", "*.litematic");
        if (chosen != null) applyFile(chosen);
    }

    private void applyFile(Path file) {
        currentInput = file;
        try { onFileChosen.accept(file.toAbsolutePath().normalize()); } catch (Exception ignored) {}
        if (!enabled.isSelected()) enabled.setSelected(true);
        else loadAsync();
    }

    /** 本地渲染页换文件后同步到预览（不回写，避免循环）。 */
    void fileChanged(Path file) {
        if (file != null && file.equals(currentInput)) return;
        currentInput = file;
        if (enabled.isSelected()) loadAsync();
    }

    private JComponent buildToolbar() {
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT));
        tools.add(presetButton("正视（等轴测）", 135, 36));
        tools.add(presetButton("北", 0, 36));
        tools.add(presetButton("南", 180, 36));
        tools.add(presetButton("东", 90, 36));
        tools.add(presetButton("西", 270, 36));
        tools.add(presetButton("顶视", 0, 89.9));
        tools.add(presetButton("底视", 0, -89.9));
        JButton half = new JButton("旋转180°（空格）");
        half.addActionListener(rotate180Action());
        tools.add(half);
        exportButton.addActionListener((ActionEvent event) -> exportCurrentView());
        tools.add(exportButton);
        tools.add(cameraLabel);
        return tools;
    }

    private JButton presetButton(String name, double presetYaw, double presetPitch) {
        JButton button = new JButton(name);
        button.addActionListener(event -> { yaw = presetYaw; pitch = presetPitch; requestFrame(false); });
        return button;
    }

    private Action rotate180Action() {
        return new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { yaw = (yaw + 180) % 360; requestFrame(false); }
        };
    }

    private void loadAsync() {
        status.setText("正在加载投影...");
        worker.execute(() -> {
            Path input = currentInput;
            if (input == null || !Files.isRegularFile(input)) {
                SwingUtilities.invokeLater(() -> status.setText("投影文件不存在"));
                return;
            }
            try {
                Path clientJar = runtime.minecraftClientJar();
                if (!Files.isRegularFile(clientJar)) {
                    SwingUtilities.invokeLater(() -> status.setText("正在下载 Minecraft 运行时（首次需要）..."));
                    runtime.ensureInstalled();
                    clientJar = runtime.minecraftClientJar();
                }
                PreviewEngine loaded = PreviewEngine.load(input, clientJar, enabledResourcePacks());
                closeEngine();
                engine = loaded;
                currentInput = input;
                SwingUtilities.invokeLater(() -> status.setText("预览已加载：" + input.getFileName() + "（" + loaded.blockCount() + " 方块）"));
                requestFrame(false);
            } catch (Throwable error) {
                closeEngine();
                log.accept("预览加载失败：" + error);
                SwingUtilities.invokeLater(() -> { status.setText("预览加载失败"); showError.accept(error); });
            }
        });
    }

    private List<Path> enabledResourcePacks() {
        List<Path> paths = new ArrayList<>();
        for (AgentConfig.ResourcePackEntry entry : config.resourcePacks) {
            if (entry.enabled()) paths.add(Path.of(entry.path()));
        }
        return paths;
    }

    /**
     * 请求重绘一帧。帧任务在 worker 线程串行执行并合并：渲染期间到达的请求只标记
     * “需要再画一帧”，不排队堆积。交互（拖拽/滚轮）用 fast=true 以低分辨率保持响应。
     */
    private void requestFrame(boolean fast) {
        PreviewEngine current = engine;
        if (current == null) return;
        if (fast) fastRequested.set(true);
        cameraLabel.setText(String.format("yaw=%.0f°  pitch=%.0f°  缩放=%.2f", yaw, pitch, zoom));
        if (renderBusy.compareAndSet(false, true)) worker.execute(this::renderLoop);
    }

    private void renderLoop() {
        try {
            while (true) {
                if (!renderOneFrame(fastRequested.getAndSet(false))) break;
                if (!fastRequested.get()) break;
            }
        } finally {
            renderBusy.set(false);
        }
        if (fastRequested.get() && renderBusy.compareAndSet(false, true)) worker.execute(this::renderLoop);
    }

    /** 渲染一帧最新状态；返回 false 表示引擎已被替换或卸载，应退出循环。 */
    private boolean renderOneFrame(boolean fast) {
        PreviewEngine current = engine;
        if (current == null) return false;
        int limit = fast ? 360 : 800;
        int size = Math.max(256, Math.min(limit, Math.min(Math.max(canvas.getWidth(), 256), Math.max(canvas.getHeight(), 256))));
        double frameYaw = yaw, framePitch = pitch, frameZoom = zoom;
        try {
            BufferedImage frame = current.renderFrame(frameYaw, framePitch, frameZoom, size);
            SwingUtilities.invokeLater(() -> { if (engine == current) canvas.setFrame(frame); });
        } catch (Throwable error) {
            log.accept("预览渲染失败：" + error);
        }
        return true;
    }

    private void exportCurrentView() {
        PreviewEngine current = engine;
        Path input = currentInput;
        if (current == null || input == null) {
            JOptionPane.showMessageDialog(this, "请先启用预览并加载投影。", "无法导出", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // GPU zoom 与软件预览 fill 的换算：预览 fill=0.82 对应 GPU 取景基准 0.95
        double gpuZoom = Math.max(0.05, Math.min(20.0, PreviewEngine.DEFAULT_FILL * zoom / GPU_ZOOM_BASE));
        Path output = Path.of(outputPath.get()).resolve(AgentFrame.renderFolderName(input.getFileName().toString()));
        exportButton.setEnabled(false);
        worker.execute(() -> {
            try {
                byte[] bytes = Files.readAllBytes(input);
                var view = new RenderModels.View(EXPORT_VIEW_ID, "预览导出", yaw, pitch, gpuZoom, false, 2048, 2048, "#000000", false, 1);
                var request = new RenderModels.Request(2, UUID.randomUUID().toString(), input.getFileName().toString(), List.of(view), null);
                var result = renderer.submit(request, bytes, Duration.ofMillis(config.renderTimeoutMillis), "本地", output.toString()).join();
                Files.createDirectories(output);
                for (var image : result.images()) Files.copy(image.path(), output.resolve(image.name()), StandardCopyOption.REPLACE_EXISTING);
                log.accept("预览导出完成：" + output);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "已导出当前视角到 " + output, "导出成功", JOptionPane.INFORMATION_MESSAGE));
            } catch (Throwable error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                log.accept("预览导出失败：" + cause);
                SwingUtilities.invokeLater(() -> showError.accept(cause));
            } finally {
                SwingUtilities.invokeLater(() -> exportButton.setEnabled(true));
            }
        });
    }

    private void closeEngine() {
        PreviewEngine current = engine;
        engine = null;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) {}
        }
    }

    /** 程序退出时释放引擎并停止渲染线程。 */
    void closeForShutdown() {
        worker.shutdownNow();
        closeEngine();
    }

    private static final class PreviewCanvas extends JPanel {
        private volatile BufferedImage frame;

        void setFrame(BufferedImage image) { this.frame = image; repaint(); }
        void clear() { this.frame = null; repaint(); }

        PreviewCanvas() { setBackground(new Color(0x2B, 0x2B, 0x2B)); }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            BufferedImage image = frame;
            if (image == null) return;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                int side = Math.min(getWidth(), getHeight()) - 8;
                int x = (getWidth() - side) / 2, y = (getHeight() - side) / 2;
                g.drawImage(image, x, y, side, side, null);
            } finally { g.dispose(); }
        }
    }
}
