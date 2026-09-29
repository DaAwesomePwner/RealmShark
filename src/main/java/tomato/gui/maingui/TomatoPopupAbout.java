package tomato.gui.maingui;

import realmshark.branding.AppIdentity;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/** Product identity and upstream credits ({@link AboutPanel}), presented in an owned, modeless dialog. */
class TomatoPopupAbout {
    public JDialog addPopup(JFrame frame) {
        JDialog dialog = new JDialog(frame, "About " + AppIdentity.NAME, false);
        AppIdentity.apply(dialog);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        content.add(new AboutPanel(), BorderLayout.CENTER);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.add(close);
        content.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(close);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        return dialog;
    }
}
