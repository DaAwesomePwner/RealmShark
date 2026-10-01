package tomato.gui.kit;

import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Where you are, left to right ("Collection › Synthetic Seal"): every crumb but the last is a link back to that place; the last
 * names the place shown. Separators are "›" and are not announced. Parts are named {@code <name>-<index>}. EDT only.
 */
public final class Breadcrumb extends JPanel {
    /** One place: its label and what opening it runs (null: plain text). */
    public record Crumb(String label, Runnable open) {
        public Crumb { Objects.requireNonNull(label, "label"); }
    }

    private final List<String> labels = new ArrayList<>();
    private List<Crumb> crumbs = List.of();

    public Breadcrumb(String name) {
        super(new FlowLayout(FlowLayout.LEADING, Tokens.XS, 0));
        setName(Objects.requireNonNull(name, "name"));
        setOpaque(false);
        getAccessibleContext().setAccessibleName("You are here");
    }

    /** Shows {@code path}, keeping the components (and focus) when only their actions change. */
    public void setPath(List<Crumb> path) {
        boolean same = path.size() == crumbs.size();
        for (int i = 0; same && i < path.size(); i++) {
            same = path.get(i).label().equals(crumbs.get(i).label())
                && (i == path.size() - 1 || (path.get(i).open() != null) == (crumbs.get(i).open() != null));
        }
        crumbs = List.copyOf(path);
        if (same) return;
        removeAll();
        labels.clear();
        for (int i = 0; i < path.size(); i++) {
            Crumb crumb = path.get(i);
            boolean last = i == path.size() - 1;
            if (i > 0) {
                KitText separator = KitText.caption("›");
                separator.getAccessibleContext().setAccessibleName("");
                add(separator);
            }
            JComponent part;
            if (last || crumb.open() == null) part = last ? KitText.emphasis(crumb.label()) : KitText.body(crumb.label());
            else {
                KitButton link = KitButton.ghost(crumb.label());
                int index = i;
                link.addActionListener(e -> crumbs.get(index).open().run());
                part = link;
            }
            part.setName(getName() + "-" + i);
            add(part);
            labels.add(crumb.label());
        }
        revalidate();
        repaint();
    }

    /** The shown labels, in order. */
    public List<String> labels() { return List.copyOf(labels); }
}
