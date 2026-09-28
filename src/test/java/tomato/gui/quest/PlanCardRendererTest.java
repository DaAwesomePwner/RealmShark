package tomato.gui.quest;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.planning.PlanData;
import tomato.planning.PlanData.*;
import static org.junit.Assert.*;
import static tomato.gui.quest.QuestPlanningTest.entry;

/** The painted plan card: its lines, at most four requirement rows with "+N more", unknown lists and stock said as such, and a11y. */
public class PlanCardRendererTest {
    private Locale format;
    private Font font;

    @Before public void usFormatAndFont13() throws Exception {
        format = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        SwingUtilities.invokeAndWait(() -> { font = ContentStyle.body(); ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 13)); });
    }
    @After public void restore() throws Exception {
        Locale.setDefault(Locale.Category.FORMAT, format);
        SwingUtilities.invokeAndWait(() -> ContentStyle.setBodyFont(font));
    }

    /** "big" needs one each of items 1–6: 1 and 2 are covered by held stock, 3 is a confirmed zero, 4–6 have no held entry. */
    private static PlanCardModel big() {
        AccountPlan p = new AccountPlan();
        p.quests.put("big", entry("big", 1, 2, 3, 4, 5, 6));
        QuestPlanning.held(p, 1, 5, "", false, 1); QuestPlanning.held(p, 2, 1, "", false, 1); QuestPlanning.held(p, 3, 0, "", false, 1);
        QuestPlanning.reserve(p, "big", 1, 1);
        PlanData.validate("account", p);
        return PlanCardModel.of(p, p.quests.get("big"), id -> "Item " + id);
    }

    private static PlanCardModel alone(QuestPlanEntry entry) {
        AccountPlan p = new AccountPlan(); p.quests.put(entry.entryId, entry);
        return PlanCardModel.of(p, entry, id -> "Item " + id);
    }

    @Test public void aCardPaintsItsPlanAndItsFirstFourRequirementRowsThenPlusMore() {
        PlanCardModel card = big();
        PlanCardRenderer.Lines lines = PlanCardRenderer.lines(card);
        assertEquals("big", lines.title());
        assertEquals("Repeats: 1", lines.repeats());
        assertEquals("Saved requirements; verify server", lines.status());
        assertEquals(card.readiness(), lines.readiness());
        assertEquals("Four rows fit a fixed card", 4, lines.rows().size());
        assertEquals("+2 more", lines.note());

        PlanCardRenderer.RowLine reserved = lines.rows().get(0);
        assertEquals(1, reserved.itemId()); assertEquals("Item 1", reserved.name()); assertEquals("need 1", reserved.need());
        assertEquals("covered", reserved.state()); assertTrue("Known stock draws its bar", reserved.bar());
        assertEquals(1, reserved.reserved()); assertEquals(Long.valueOf(0), reserved.covered()); assertEquals(Long.valueOf(0), reserved.missing());
        assertEquals("covered", lines.rows().get(1).state());
        PlanCardRenderer.RowLine zero = lines.rows().get(2);
        assertEquals("A confirmed zero is short", "missing 1", zero.state()); assertTrue(zero.bar());
        PlanCardRenderer.RowLine unknown = lines.rows().get(3);
        assertEquals("Stock unconfirmed", unknown.state());
        assertFalse("Unknown stock draws no bar (an empty track reads as 0%)", unknown.bar());
    }

    @Test public void unknownAndEmptyRequirementsAreSaidNeverAnEmptyList() {
        QuestPlanEntry unknown = entry("unknown"); unknown.requirementsKnown = false;
        PlanCardRenderer.Lines uncaptured = PlanCardRenderer.lines(alone(unknown));
        assertTrue(uncaptured.rows().isEmpty());
        assertEquals("Requirements not captured", uncaptured.note());
        assertEquals("Requirements unknown", uncaptured.status());
        PlanCardRenderer.Lines empty = PlanCardRenderer.lines(alone(entry("empty")));
        assertTrue(empty.rows().isEmpty());
        assertEquals("No items required (observed empty)", empty.note());
        PlanCardRenderer.Lines four = PlanCardRenderer.lines(alone(entry("four", 1, 2, 3, 4)));
        assertEquals(4, four.rows().size());
        assertEquals("Exactly four rows need no \"+N more\"", "", four.note());
    }

    /** A card's details (its tooltip and accessible description) name an item without an asset name once: "Unknown item #9999". */
    @Test public void detailsNameAnUnnamedItemOnceAndANamedItemWithItsId() {
        AccountPlan p = new AccountPlan(); p.quests.put("odd", entry("odd", 1, 9999));
        PlanCardModel card = PlanCardModel.of(p, p.quests.get("odd"), id -> id == 1 ? "Item 1" : "Unknown item #" + id);
        assertEquals("Repeats: 1 · Item 1 (#1): need 1 · Stock unconfirmed · Unknown item #9999: need 1 · Stock unconfirmed"
            + " · Available stock is manually confirmed held stock only", PlanCardRenderer.details(card));
    }

    @Test public void oneComponentPaintsEveryCellAndNamesTheCard() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PlanCardRenderer renderer = new PlanCardRenderer();
            JList<PlanCardModel> list = new JList<>();
            PlanCardModel card = big();
            Component first = renderer.getListCellRendererComponent(list, card, 0, true, true);
            Component second = renderer.getListCellRendererComponent(list, alone(entry("other", 1)), 1, false, false);
            assertSame("One reusable component, no per-card trees (spec §9)", first, second);
            renderer.getListCellRendererComponent(list, card, 0, true, false);
            assertEquals("big: Saved requirements; verify server; " + card.readiness(), PlanCardRenderer.accessibleName(card));
            assertEquals(PlanCardRenderer.accessibleName(card), renderer.getAccessibleContext().getAccessibleName());
            assertEquals(AccessibleRole.LIST_ITEM, renderer.getAccessibleContext().getAccessibleRole());
            String tooltip = renderer.getToolTipText();
            assertTrue("The tooltip lists every row, beyond the four painted: " + tooltip, tooltip.contains("Item 6 (#6): need 1 · Stock unconfirmed"));
            assertTrue(tooltip, tooltip.contains("Item 1 (#1): need 1 · reserved 1 · covered 0 · missing 0"));

            assertEquals("The cell is the renderer's preferred size (TileList)", renderer.cellSize(), renderer.getPreferredSize());
            Dimension cell = renderer.cellSize();
            renderer.setSize(cell);
            BufferedImage image = new BufferedImage(cell.width, cell.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            renderer.paint(g);
            g.dispose();
            int x = PlanCardRenderer.GAP / 2 + 5, y = cell.height - PlanCardRenderer.GAP / 2 - 5;
            assertEquals("A selected card is washed with the accent", Tokens.color(Tokens.Role.ACCENT_WASH), new Color(image.getRGB(x, y), true));
            renderer.getListCellRendererComponent(list, card, 0, false, false);
            image = new BufferedImage(cell.width, cell.height, BufferedImage.TYPE_INT_ARGB);
            g = image.createGraphics();
            renderer.paint(g);
            g.dispose();
            assertEquals("Unselected: the raised surface", Tokens.color(Tokens.Role.RAISED), new Color(image.getRGB(x, y), true));

            ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 18));
            Dimension larger = renderer.cellSize();
            assertTrue("The card grows with the font: " + cell + " → " + larger, larger.width > cell.width && larger.height > cell.height);
        });
    }
}
