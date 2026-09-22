package tomato.backend.data;

import org.junit.Test;
import static org.junit.Assert.*;

public class PetFeedingTest {
    @Test public void missingPointsAndCapNeverBecomeFullyFedOrZeroCost() {
        PetFeeding.Estimate missing = PetFeeding.estimate(0, 30, null, 30, 500);
        assertFalse(missing.fullyFed); assertNull(missing.maxItems); assertTrue(missing.reason.contains("points not captured"));
        assertNull(PetFeeding.estimate(0, 1, 0, null, 500).maxItems);
        PetFeeding.Estimate zero = PetFeeding.estimate(0, 1, 0, 30, 500);
        assertTrue(zero.maxItems > 0); assertEquals(Long.valueOf(1), zero.nextItems); assertEquals(Long.valueOf(15), zero.nextFame);
    }
    @Test public void supportedExampleAndManualScenarioUseCeilingAndDoNotChangeObservations() {
        PetFeeding.Estimate fiveHundred = PetFeeding.estimate(0, 1, 100, 30, 500);
        assertEquals(Long.valueOf(4), fiveHundred.maxItems); assertEquals(Long.valueOf(60), fiveHundred.maxFame);
        PetFeeding.Estimate thousand = PetFeeding.estimate(0, 1, 100, 30, 1000);
        assertEquals(Long.valueOf(2), thousand.maxItems); assertEquals(Long.valueOf(30), thousand.maxFame);
    }
    @Test public void unsupportedCostTierPreservesIndependentItemEstimate() {
        PetFeeding.Estimate estimate = PetFeeding.estimate(0, 1, 0, 65, 500);
        assertTrue(estimate.maxItems > 0); assertNull(estimate.maxFame); assertNull(estimate.nextFame);
        assertTrue(estimate.reason.contains("unsupported cost tier 65"));
    }
    @Test public void locksInvalidInputsMaxedAndLargeValuesAreExplicit() {
        assertTrue(PetFeeding.estimate(2, null, null, 70, 500).locked);
        assertNull(PetFeeding.estimate(0, 90, 0, 30, 500).maxItems);
        assertNull(PetFeeding.estimate(0, 1, -1, 30, 500).maxItems);
        assertNull(PetFeeding.estimate(0, 1, 0, 101, 500).maxItems);
        assertNull(PetFeeding.estimate(0, 1, 0, 30, 0).maxItems);
        assertNull(PetFeeding.estimate(0, 1, Integer.MAX_VALUE, 100, 1).maxItems);
        PetFeeding.Estimate large = PetFeeding.estimate(2, 1, 0, 100, 1);
        assertTrue(large.maxFame > 0); assertTrue(large.maxItems > 0);
        assertEquals(Long.valueOf(1), PetFeeding.estimate(2, 1, 0, 100, Integer.MAX_VALUE).maxItems);
        PetFeeding.Estimate max = PetFeeding.estimate(0, 30, 0, 30, 500);
        assertTrue(max.fullyFed); assertEquals(Long.valueOf(0), max.maxFame);
    }
    @Test @SuppressWarnings("removal") public void maximumLevelDoesNotDependOnBoxedIntegerIdentity() {
        PetFeeding.Estimate max = PetFeeding.estimate(0, new Integer(30), new Integer(0), new Integer(30), 500);
        assertTrue(max.fullyFed); assertEquals(Long.valueOf(0), max.maxItems); assertEquals(Long.valueOf(0), max.nextFame);
    }
}
