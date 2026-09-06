package dev.qqbot.gpuagent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewTableModelTest {
    @Test
    void bottomPresetUsesTheCurrent150PercentBrightnessAs100Percent() {
        ViewTableModel model = new ViewTableModel();

        model.applyPreset(0, "底视图", 0, -89.9);
        assertEquals(100L, model.getValueAt(0, 5));
        assertEquals(100L, model.getValueAt(1, 5));
        assertEquals(1.5, model.values().getFirst().brightness());

        model.setValueAt(165, 0, 5);
        assertEquals(165L, model.getValueAt(0, 5));
        assertEquals(100L, model.getValueAt(1, 5));
        assertEquals(2.475, model.values().getFirst().brightness(), 0.0001);
    }
}
