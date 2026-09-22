package packets.packetcapture.sniff.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;

/**
 * GUI class indicating that Npcap is missing.
 */
public class MissingNpcapGUI extends JFrame {
    private String description1 = "You are missing a program called Npcap.";
    private String description2 = "For legal reasons it needs to be installed separately.";
    private String description3 = "It is free and can be downloaded from:";

    private String str = "https://npcap.com/";
    private JLabel descript1 = new JLabel(description1);
    private JLabel descript2 = new JLabel(description2);
    private JLabel descript3 = new JLabel(description3);
    private JLabel link = new JLabel(str);
    private JButton close = new JButton("Continue browsing history");

    public MissingNpcapGUI() throws HeadlessException {
        super();
        realmshark.branding.AppIdentity.apply(this);
        setTitle("Missing Npcap");

        link.setForeground(Color.BLUE.darker());
        link.setCursor(new Cursor(Cursor.HAND_CURSOR));

        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    Desktop.getDesktop().browse(new URI("https://npcap.com/"));
                } catch (IOException | URISyntaxException e1) {
                    e1.printStackTrace();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                link.setText(str);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                link.setText(str);
            }
        });

        close.addActionListener(e -> dispose());
        BoxLayout box = new BoxLayout(getContentPane(), BoxLayout.Y_AXIS);

        setLayout(box);
        Panel panel0 = new Panel();
        FlowLayout f1 = new FlowLayout(FlowLayout.LEFT);
        f1.setVgap(3);
        panel0.setLayout(f1);
        Panel panel1 = new Panel();
        FlowLayout f2 = new FlowLayout(FlowLayout.LEFT);
        f2.setVgap(0);
        panel1.setLayout(f2);
        panel1.add(descript1);
        Panel panel2 = new Panel();
        panel2.setLayout(f2);
        panel2.add(descript2);
        Panel panel3 = new Panel();
        panel3.setLayout(f2);
        panel3.add(descript3);
        panel3.add(link);
        JButton open = new JButton("Open Npcap website");
        open.addActionListener(e -> {
            try { Desktop.getDesktop().browse(new URI(str)); }
            catch (Exception failure) { JOptionPane.showMessageDialog(this, "Open https://npcap.com/ in your browser to install or repair Npcap."); }
        });
        panel3.add(open);
        Panel panel4 = new Panel();
        panel4.setLayout(new FlowLayout(FlowLayout.CENTER));
        panel4.add(close);
        add(panel0);
        add(panel1);
        add(panel2);
        add(panel3);
        add(panel4);

        pack();
        setResizable(false);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        getRootPane().setDefaultButton(close);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setVisible(true);
    }
}
