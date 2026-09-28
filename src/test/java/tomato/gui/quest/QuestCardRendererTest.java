package tomato.gui.quest;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import org.junit.Test;
import packets.data.QuestData;
import tomato.gui.kit.TileList;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.gui.quest.QuestCardRenderer.Lines;
import tomato.gui.quest.QuestCardRenderer.Slot;
import static org.junit.Assert.*;
import static tomato.gui.quest.QuestFixtures.*;

/**
 * One painted quest card: the pin star, name, badges and the user's type chip; "You get" or "Pick 1 of N" with at most four reward
 * slots and "+N"; "Bring" with at most four requirement slots, each with its count, and "+N"; "not captured" for unknown lists and
 * "None listed by the server" for captured empty ones (unknown is never none). The cell is fixed: its size never depends on the card.
 */
public class QuestCardRendererTest {
    /** Pinned, repeatable, done, a choice of six distinct rewards (one twice) and five distinct requirements. */
    private static QuestCardModel full() {
        QuestData q = data("Festival exchange", 8, new int[] {FORGOTTEN_KING, FORGOTTEN_KING, FORGOTTEN_KING, FORGOTTEN_KING, FORGOTTEN_KING,
                FORGOTTEN_KING, FORGOTTEN_KING, FORGOTTEN_KING, FORGOTTEN_KING, FORGOTTEN_KING, MALUS, MALUS, FESTIVAL_TOKEN, FESTIVAL_TOKEN,
                FESTIVAL_TOKEN, UNKNOWN_ITEM, 20},
            MIGHTY_CHEST, ROYAL_EPIC_CHEST, ROYAL_EPIC_CHEST, CULTISH_EPIC_CHEST, BEGINNER_CHEST, GOLDEN_CHEST, STANDARD_CHEST);
        q.itemOfChoice = true; q.repeatable = true; q.completed = true;
        return card(q, true, "Event");
    }

    private static QuestCardModel uncaptured() {
        QuestData q = data("Unknown loot", 9, null); q.rewards = null; q.itemOfChoice = true;
        return card(q, false, "");
    }

    @Test public void linesShowBadgesChipChoiceTitleFourSlotsAndOverflow() {
        Lines lines = QuestCardRenderer.lines(full());
        assertEquals("★", lines.pin());
        assertEquals("Festival exchange", lines.title());
        assertEquals(List.of("↻ Repeatable", "✓ Done"), lines.badges());
        assertEquals("The user's own type label", "Event", lines.chip());
        assertEquals("Pick 1 of 6", lines.rewardsTitle());
        assertEquals("Four reward slots in first-seen order; a repeated reward shows its count",
            List.of(new Slot(MIGHTY_CHEST, ""), new Slot(ROYAL_EPIC_CHEST, "×2"), new Slot(CULTISH_EPIC_CHEST, ""), new Slot(BEGINNER_CHEST, "")),
            lines.rewards());
        assertEquals("+2", lines.rewardsMore());
        assertEquals("", lines.rewardsNote());
        assertEquals("Bring", lines.requirementsTitle());
        assertEquals("Every requirement slot shows its count",
            List.of(new Slot(FORGOTTEN_KING, "×10"), new Slot(MALUS, "×2"), new Slot(FESTIVAL_TOKEN, "×3"), new Slot(UNKNOWN_ITEM, "×1")),
            lines.requirements());
        assertEquals("+1", lines.requirementsMore());
        assertEquals("", lines.requirementsNote());

        Lines plain = QuestCardRenderer.lines(card("Cultist tribute", CULTISH_EPIC_CHEST));
        assertEquals("No star when not pinned", "", plain.pin());
        assertEquals(List.of("One-time"), plain.badges());
        assertEquals("No chip without the user's label", "", plain.chip());
        assertEquals("You get", plain.rewardsTitle());
        assertEquals(List.of(new Slot(CULTISH_EPIC_CHEST, "")), plain.rewards());
        assertEquals("", plain.rewardsMore());
        assertEquals(List.of(new Slot(MALUS, "×1")), plain.requirements());
    }

    @Test public void unknownListsSayNotCapturedAndCapturedEmptyListsSayNoneListed() {
        Lines unknown = QuestCardRenderer.lines(uncaptured());
        assertEquals("Rewards not captured", unknown.rewardsNote());
        assertEquals("Requirements not captured", unknown.requirementsNote());
        assertTrue(unknown.rewards().isEmpty()); assertTrue(unknown.requirements().isEmpty());
        assertEquals("", unknown.rewardsMore()); assertEquals("", unknown.requirementsMore());
        assertEquals("Pick 1", unknown.rewardsTitle());

        Lines empty = QuestCardRenderer.lines(card(data("Nothing asked", 5, new int[0]), false, ""));
        assertEquals("None listed by the server", empty.rewardsNote());
        assertEquals("None listed by the server", empty.requirementsNote());
        assertFalse(empty.rewardsNote().contains("not captured"));
    }

    @Test public void accessibleNameReadsTheWholeCard() {
        assertEquals("Festival exchange, pinned, repeatable, done, Event; pick 1 of 6: Mighty Quest Chest, 2 Royal Epic Quest Chest, "
                + "Cultish Epic Quest Chest, Beginner Quest Chest and 2 more; bring: 10 Mark of the Forgotten King, 2 Mark of Malus, "
                + "3 Festival Token, 1 Unknown item #9999 and 1 more",
            QuestCardRenderer.accessibleName(full()));
        assertEquals("Unknown loot, one-time; rewards not captured; requirements not captured", QuestCardRenderer.accessibleName(uncaptured()));
        assertEquals("Nothing asked, one-time; no rewards listed; nothing listed to bring",
            QuestCardRenderer.accessibleName(card(data("Nothing asked", 5, new int[0]), false, "")));
    }

    @Test public void theRendererShowsTheCardItWasGivenAndAnnouncesIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            QuestCardRenderer renderer = new QuestCardRenderer((id, size) -> null);
            JList<QuestCardModel> list = new JList<>();
            assertSame(renderer, renderer.getListCellRendererComponent(list, full(), 0, true, true));
            assertEquals(QuestCardRenderer.lines(full()), renderer.shown());
            assertEquals(QuestCardRenderer.accessibleName(full()), renderer.getAccessibleContext().getAccessibleName());
            assertEquals(AccessibleRole.LIST_ITEM, renderer.getAccessibleContext().getAccessibleRole());
            TileList<QuestCardModel> tiles = new TileList<>("quest-cards-test", renderer, QuestBoard::key, QuestCardRenderer::accessibleName);
            tiles.setItems(List.of(full(), uncaptured()));
            assertEquals("Each tile announces its card", QuestCardRenderer.accessibleName(uncaptured()),
                tiles.getAccessibleContext().getAccessibleChild(1).getAccessibleContext().getAccessibleName());
        });
    }

    @Test public void theCellIsFixedFitsFourSlotsOfEachListAndFollowsTheFont() throws Exception {
        Font old = ContentStyle.body();
        try {
            SwingUtilities.invokeAndWait(() -> {
                QuestCardRenderer renderer = new QuestCardRenderer((id, size) -> null);
                JList<QuestCardModel> list = new JList<>();
                Dimension cell = renderer.cellSize();
                assertEquals("The TileList cell is the renderer's preferred size", cell, renderer.getPreferredSize());
                renderer.getListCellRendererComponent(list, full(), 0, false, false);
                assertEquals("Value-independent", cell, renderer.getPreferredSize());
                renderer.getListCellRendererComponent(list, uncaptured(), 0, false, false);
                assertEquals(cell, renderer.getPreferredSize());
                assertTrue("Four reward slots and \"+N\" fit", cell.width - QuestCardRenderer.GAP >= 2 * Tokens.M + renderer.rewardRowWidth());
                assertTrue("Four requirement slots with counts and \"+N\" fit",
                    cell.width - QuestCardRenderer.GAP >= 2 * Tokens.M + renderer.requirementRowWidth());
                ContentStyle.setBodyFont(new Font(ContentStyle.FONT_FAMILY, Font.PLAIN, 24));
                Dimension large = renderer.cellSize();
                assertTrue("A larger font makes a larger cell", large.width > cell.width && large.height > cell.height);
                assertTrue(large.width - QuestCardRenderer.GAP >= 2 * Tokens.M + renderer.requirementRowWidth());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> ContentStyle.setBodyFont(old));
        }
    }

    @Test public void paintsFullAndUnknownCardsWithMissingOrFailingOrOversizedSprites() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Icon big = new ImageIcon(new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB));
            QuestCardRenderer renderer = new QuestCardRenderer((id, size) -> {
                if (id == UNKNOWN_ITEM) throw new IllegalStateException("Unavailable icon");
                return id == MIGHTY_CHEST ? big : null;
            });
            JList<QuestCardModel> list = new JList<>();
            for (QuestCardModel card : List.of(full(), uncaptured(), card("Plain", STANDARD_CHEST))) {
                renderer.getListCellRendererComponent(list, card, 0, true, true);
                Dimension cell = renderer.cellSize();
                renderer.setSize(cell);
                BufferedImage image = new BufferedImage(cell.width, cell.height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                try { renderer.paint(g); } finally { g.dispose(); }
                assertNotEquals("The card surface is painted", 0, image.getRGB(cell.width / 2, cell.height - QuestCardRenderer.GAP) >>> 24);
            }
        });
    }
}
