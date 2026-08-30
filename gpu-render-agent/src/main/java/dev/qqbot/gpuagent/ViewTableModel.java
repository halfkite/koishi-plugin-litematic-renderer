package dev.qqbot.gpuagent;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

final class ViewTableModel extends AbstractTableModel {
    private final String[] columns = {"ID", "名称", "Yaw", "Pitch", "缩放", "宽", "高", "超采样"};
    private final List<RenderModels.View> views = new ArrayList<>();
    private int nextViewIndex;
    private int defaultWidth = 2048;
    private int defaultHeight = 2048;
    private Runnable onChange;

    ViewTableModel() {
        views.add(new RenderModels.View("isometric", "正二轴测", 135, 36, 0.82, true, 2048, 2048, "#000000", false, 1));
        views.add(new RenderModels.View("isometric-reverse", "反向正二轴测", 315, 36, 0.82, true, 2048, 2048, "#000000", false, 1));
        nextViewIndex = views.size();
    }

    /** 视角表任何变化时的回调（用于持久化）。 */
    void setOnChange(Runnable callback) { this.onChange = callback; }
    private void changed() { if (this.onChange != null) this.onChange.run(); }

    /** 设置全局默认宽/高并应用到全部视角行。 */
    void applyResolutionToAll(int width, int height) {
        this.defaultWidth = Math.max(64, Math.min(4096, width));
        this.defaultHeight = Math.max(64, Math.min(4096, height));
        for (int row = 0; row < views.size(); row++) {
            var v = views.get(row);
            views.set(row, new RenderModels.View(v.id(), v.name(), v.yaw(), v.pitch(), v.zoom(), true, defaultWidth, defaultHeight, v.background(), v.transparentBackground(), v.supersampling()));
        }
        fireTableDataChanged();
        changed();
    }

    /** 用持久化的视角列表整体替换当前内容。 */
    void reset(List<RenderModels.View> loaded) {
        views.clear();
        views.addAll(loaded);
        nextViewIndex = views.size();
        fireTableDataChanged();
    }

    List<RenderModels.View> values() { return List.copyOf(views); }
    void addView() {
        String id;
        do { id = "view-" + (++nextViewIndex); } while (containsId(id));
        views.add(new RenderModels.View(id, "自定义视角", 0, 0, 1.0, true, defaultWidth, defaultHeight, "#000000", false, 1));
        fireTableRowsInserted(views.size()-1, views.size()-1);
        changed();
    }
    private boolean containsId(String id) { return views.stream().anyMatch(view -> view.id().equals(id)); }

    /** 应用预设视角（同时更新名称便于识别）。 */
    void applyPreset(int row, String name, double yaw, double pitch) {
        if (row < 0 || row >= views.size()) return;
        var v = views.get(row);
        views.set(row, new RenderModels.View(v.id(), name, yaw, pitch, v.zoom(), true, v.width(), v.height(), v.background(), v.transparentBackground(), v.supersampling()));
        fireTableRowsUpdated(row, row);
        changed();
    }
    void remove(int row) { if (row >= 0 && row < views.size()) { views.remove(row); fireTableRowsDeleted(row, row); changed(); } }
    @Override public int getRowCount() { return views.size(); }
    @Override public int getColumnCount() { return columns.length; }
    @Override public String getColumnName(int column) { return columns[column]; }
    @Override public boolean isCellEditable(int row, int column) { return true; }
    @Override public Object getValueAt(int row, int column) {
        var v = views.get(row);
        return switch (column) { case 0 -> v.id(); case 1 -> v.name(); case 2 -> v.yaw(); case 3 -> v.pitch(); case 4 -> v.zoom(); case 5 -> v.width(); case 6 -> v.height(); default -> v.supersampling(); };
    }
    @Override public void setValueAt(Object value, int row, int column) {
        var v = views.get(row);
        try {
            String id = column == 0 ? value.toString() : v.id(); String name = column == 1 ? value.toString() : v.name();
            double yaw = column == 2 ? Double.parseDouble(value.toString()) : v.yaw(); double pitch = column == 3 ? Double.parseDouble(value.toString()) : v.pitch();
            Double zoom = column == 4 ? Double.parseDouble(value.toString()) : v.zoom(); int width = column == 5 ? Integer.parseInt(value.toString()) : v.width();
            int height = column == 6 ? Integer.parseInt(value.toString()) : v.height(); int ss = column == 7 ? Integer.parseInt(value.toString()) : v.supersampling();
            views.set(row, new RenderModels.View(id, name, yaw, pitch, zoom, true, width, height, v.background(), v.transparentBackground(), ss));
        } catch (NumberFormatException ignored) {}
        fireTableRowsUpdated(row, row);
        changed();
    }
}
