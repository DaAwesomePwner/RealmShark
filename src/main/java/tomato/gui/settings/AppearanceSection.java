package tomato.gui.settings;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import tomato.gui.kit.DisplayModeModel;
import tomato.gui.kit.Motion;
import tomato.gui.kit.SectionHeader;
import tomato.gui.kit.SegmentedControl;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.Themes;
import tomato.gui.modern.VioletLightTheme;
import util.PropertiesManager;

/**
 * Settings › Appearance. Every control applies and saves at once: the theme variant and Increase
 * contrast through Themes.select, Reduce motion as ui.reduceMotion, and the display mode shared with
 * the header switch. The controls follow the installed theme, so Edit › Theme changes show here too.
 */
public final class AppearanceSection extends JPanel {
    private final Consumer<Themes.Choice> selectTheme;
    private final Supplier<Themes.Choice> installedTheme;
    private final Function<String, String> read;
    private final Runnable afterThemeChange;
    private final List<JTextArea> notes = new ArrayList<>();
    private final SegmentedControl theme = new SegmentedControl("settings-theme", "Violet Dark", "Violet Light");
    private final JCheckBox contrast = new JCheckBox("Increase contrast");
    private final JCheckBox reduceMotion = new JCheckBox("Reduce motion");
    private final SegmentedControl display = new SegmentedControl("settings-display-mode", "Simple", "Analyst");

    /** @param afterThemeChange refreshes fonts and shell colors after a new look and feel is installed */
    public AppearanceSection(Runnable afterThemeChange) {
        this(Themes::select, AppearanceSection::installed, PropertiesManager::getProperty, PropertiesManager::setProperties,
            DisplayModeModel.application(), afterThemeChange);
    }

    AppearanceSection(Consumer<Themes.Choice> selectTheme, Supplier<Themes.Choice> installedTheme,
                      Function<String, String> read, BiConsumer<String, String> write,
                      DisplayModeModel mode, Runnable afterThemeChange) {
        super(new BorderLayout());
        this.selectTheme = selectTheme;
        this.installedTheme = installedTheme;
        this.read = read;
        this.afterThemeChange = afterThemeChange;
        setName("settings-appearance");
        setOpaque(false);
        contrast.setName("settings-increase-contrast");
        reduceMotion.setName("settings-reduce-motion");
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(group("Theme", theme, null));
        body.add(group(null, contrast, "Stronger outlines, dividers and secondary text."));
        body.add(group("Motion", reduceMotion,
            "Turns off the short expand and collapse animations. They are also off when Windows animations are turned off."));
        body.add(group("Display mode", display,
            "Simple hides provenance, IDs and diagnostic tabs; Analyst shows them. This is the header switch (Ctrl+Shift+A), and the last choice is used at the next start."));
        theme.onChange(index -> applyTheme());
        contrast.addActionListener(e -> applyTheme());
        reduceMotion.addActionListener(e -> write.accept(Motion.REDUCE_KEY, Boolean.toString(reduceMotion.isSelected())));
        display.onChange(index -> mode.set(index == 1 ? DisplayModeModel.Mode.ANALYST : DisplayModeModel.Mode.SIMPLE));
        mode.bind(this, value -> display.setSelected(value == DisplayModeModel.Mode.ANALYST ? 1 : 0));
        add(ContentStyle.page(null, body, null));
        sync();
    }

    @Override public void updateUI() {
        super.updateUI();
        // Edit › Theme reinstalls the look and feel as well; the controls follow what is installed.
        if (display != null) sync();
    }

    private JComponent group(String title, JComponent control, String help) {
        JPanel group = new JPanel(new BorderLayout(0, Tokens.XS)) {
            @Override public Dimension getMaximumSize() {
                // Keep controls beside their help text instead of stretching each group down a tall page.
                Dimension size = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, size.height);
            }
        };
        group.setOpaque(false);
        group.setAlignmentX(LEFT_ALIGNMENT);
        group.setBorder(new EmptyBorder(title == null ? 0 : Tokens.M, 0, Tokens.S, 0));
        if (title != null) group.add(new SectionHeader(title), BorderLayout.NORTH);
        JPanel row = ContentStyle.controls();
        row.setOpaque(false);
        row.add(control);
        group.add(row, BorderLayout.CENTER);
        if (help != null) {
            JTextArea note = ContentStyle.wrappingText(help);
            notes.add(note);
            group.add(note, BorderLayout.SOUTH);
        }
        return group;
    }

    private void applyTheme() {
        selectTheme.accept(new Themes.Choice(theme.selected() == 1 ? Themes.Variant.LIGHT : Themes.Variant.DARK, contrast.isSelected()));
        afterThemeChange.run();
    }

    private void sync() {
        Themes.Choice current = installedTheme.get();
        theme.setSelected(current.variant == Themes.Variant.LIGHT ? 1 : 0);
        contrast.setSelected(current.increaseContrast);
        reduceMotion.setSelected("true".equals(read.apply(Motion.REDUCE_KEY)));
        for (JTextArea note : notes) note.setForeground(Tokens.color(Tokens.Role.TEXT_MUTED));
    }

    /** What is installed now; Themes.select installs before it saves, so this is current during updateUI. */
    static Themes.Choice installed() {
        return new Themes.Choice(UIManager.getLookAndFeel() instanceof VioletLightTheme ? Themes.Variant.LIGHT : Themes.Variant.DARK,
            Themes.increaseContrast());
    }
}
