package vn.edu.fs.gui;

import javax.swing.SwingUtilities;

public final class GuiMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
    }
}