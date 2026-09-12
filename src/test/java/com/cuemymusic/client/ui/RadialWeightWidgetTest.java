package com.cuemymusic.client.ui;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RadialWeightWidgetTest {

    private static RadialWeightWidget.Slice slice(String id, double chance) {
        return new RadialWeightWidget.Slice(id, id, chance, RadialWeightWidget.stableColor(id));
    }

    @Test void hitTestSelectsWedgeByAngleAndRejectsCenterHole() {
        List<RadialWeightWidget.Slice> slices = List.of(slice("a", .25), slice("b", .75));
        assertEquals("a", RadialWeightWidget.hitTest(slices, 50, 10, 100).orElseThrow());
        assertEquals("b", RadialWeightWidget.hitTest(slices, 90, 50, 100).orElseThrow());
        assertTrue(RadialWeightWidget.hitTest(slices, 50, 50, 100).isEmpty());
        // Outside outer radius
        assertTrue(RadialWeightWidget.hitTest(slices, 50, 0, 100).isEmpty());
        assertTrue(RadialWeightWidget.hitTest(slices, -10, 50, 100).isEmpty());
        assertTrue(RadialWeightWidget.hitTest(slices, 110, 50, 100).isEmpty());
    }

    @Test void rasterIsDeterministicAndMutedPoolDrawsOnlyRing() {
        assertArrayEquals(RadialWeightWidget.rasterize(64, List.of(), null),
                RadialWeightWidget.rasterize(64, List.of(), null));
        int[] mutedRaster = RadialWeightWidget.rasterize(64, List.of(), null);
        assertTrue(Arrays.stream(mutedRaster)
                .noneMatch(pixel -> pixel == RadialWeightWidget.stableColor("a")));

        // Center hole must be transparent
        int centerPixel = mutedRaster[32 * 64 + 32];
        assertEquals(0, centerPixel, "Center hole pixel must be transparent");

        // Annulus pixel at top (x=32, y=6) distance from center (32, 32) is 26, radius is within [.34*64, .48*64] = [21.76, 30.72]
        int annulusPixel = mutedRaster[6 * 64 + 32];
        assertNotEquals(0, annulusPixel, "Annulus ring pixel must be non-zero");
    }

    @Test void stableColorIsDeterministicAndPaletteDriven() {
        int colorA1 = RadialWeightWidget.stableColor("track_a");
        int colorA2 = RadialWeightWidget.stableColor("track_a");
        assertEquals(colorA1, colorA2, "stableColor must be deterministic for the same identifier");

        // Alpha channel must be full 0xFF
        assertEquals(0xFF, (colorA1 >>> 24) & 0xFF, "Color must have full alpha");

        // Null identifier returns fallback non-zero color
        assertNotEquals(0, RadialWeightWidget.stableColor(null));
    }

    @Test void normalizedWedgeBoundariesAndChances() {
        // Unnormalized chances 10 and 30 map to 25% and 75%
        List<RadialWeightWidget.Slice> slices = List.of(slice("first", 10.0), slice("second", 30.0));
        assertEquals("first", RadialWeightWidget.hitTest(slices, 50, 10, 100).orElseThrow());
        assertEquals("second", RadialWeightWidget.hitTest(slices, 90, 50, 100).orElseThrow());

        // Zero chance slice is not selectable
        List<RadialWeightWidget.Slice> zeroSlices = List.of(slice("zero", 0.0), slice("active", 1.0));
        // Even at 0 angle, "zero" has no positive wedge; "active" owns the circle
        assertEquals("active", RadialWeightWidget.hitTest(zeroSlices, 50, 10, 100).orElseThrow());

        // Empty slices or all-zero returns empty hit test
        List<RadialWeightWidget.Slice> allZero = List.of(slice("z1", 0.0), slice("z2", 0.0));
        assertTrue(RadialWeightWidget.hitTest(allZero, 50, 10, 100).isEmpty());
        assertTrue(RadialWeightWidget.hitTest(List.of(), 50, 10, 100).isEmpty());
    }

    @Test void selectedHighlightBrightensBoundaryPixels() {
        List<RadialWeightWidget.Slice> slices = List.of(slice("a", 0.5), slice("b", 0.5));
        int size = 100;
        int[] unselected = RadialWeightWidget.rasterize(size, slices, null);
        int[] selected = RadialWeightWidget.rasterize(size, slices, "a");

        // Slice 'a' spans [0, 0.5) top-to-bottom right side.
        // Near inner radius (radius ~ 35), boundary pixel should be brightened
        // Center is (50, 50). Point (50, 15) has dy = -35, radius = 35. Inner radius is 34.
        // Distance to inner radius is 35 - 34 = 1.0 <= 2.0.
        int boundaryIdx = 15 * size + 50;
        assertNotEquals(unselected[boundaryIdx], selected[boundaryIdx],
                "Boundary pixel of selected wedge must be brightened");

        // Far from boundary (interior of slice b on left side, e.g. x=10, y=50, radius=40, angle=3pi/2)
        // Slice b is not selected so its pixels should be unchanged
        int sliceBIdx = 50 * size + 10;
        assertEquals(unselected[sliceBIdx], selected[sliceBIdx],
                "Unselected slice pixel must remain identical");
    }

    @Test void dimensionClampingEnsuresSafeAllocation() {
        assertEquals(16, RadialWeightWidget.clampSize(-5));
        assertEquals(16, RadialWeightWidget.clampSize(0));
        assertEquals(16, RadialWeightWidget.clampSize(15));
        assertEquals(64, RadialWeightWidget.clampSize(64));
        assertEquals(100, RadialWeightWidget.clampSize(100));
        assertEquals(512, RadialWeightWidget.clampSize(512));
        assertEquals(512, RadialWeightWidget.clampSize(1000));
    }

    @Test void widgetLifecycleAndClickCallback() {
        java.util.concurrent.atomic.AtomicReference<String> selected = new java.util.concurrent.atomic.AtomicReference<>();
        RadialWeightWidget widget = new RadialWeightWidget(10, 20, 100, selected::set);
        assertEquals(10, widget.getX());
        assertEquals(20, widget.getY());
        assertEquals(100, widget.getWidth());
        assertEquals(100, widget.getHeight());

        List<RadialWeightWidget.Slice> slices = List.of(slice("a", 0.5), slice("b", 0.5));
        widget.setModel(slices, "a");
        assertEquals(slices, widget.slices());
        assertEquals("a", widget.selectedResourceId());

        // Simulate click at relative (10, 50) -> slice b (left side, angle 3pi/2, frac 0.75)
        // Absolute click at widget.getX() + 10, widget.getY() + 50
        net.minecraft.client.input.MouseButtonInfo btnInfo = new net.minecraft.client.input.MouseButtonInfo(0, 0);
        net.minecraft.client.input.MouseButtonEvent event =
                new net.minecraft.client.input.MouseButtonEvent(10 + 10, 20 + 50, btnInfo);
        widget.onClick(event, false);
        assertEquals("b", selected.get(), "onClick must invoke selection callback with hit resourceId");

        // Click on center hole must not invoke callback
        selected.set(null);
        net.minecraft.client.input.MouseButtonEvent centerEvent =
                new net.minecraft.client.input.MouseButtonEvent(10 + 50, 20 + 50, btnInfo);
        widget.onClick(centerEvent, false);
        assertNull(selected.get(), "Click on center hole must not invoke selection callback");

        // Closing widget is idempotent and safe without running Minecraft instance
        assertDoesNotThrow(widget::close);
    }

    @Test void singleSliceFillsCircle() {
        List<RadialWeightWidget.Slice> single = List.of(slice("solo", 1.0));
        assertEquals("solo", RadialWeightWidget.hitTest(single, 50, 10, 100).orElseThrow());
        assertEquals("solo", RadialWeightWidget.hitTest(single, 90, 50, 100).orElseThrow());
        assertEquals("solo", RadialWeightWidget.hitTest(single, 50, 90, 100).orElseThrow());
        assertEquals("solo", RadialWeightWidget.hitTest(single, 10, 50, 100).orElseThrow());

        int[] raster = RadialWeightWidget.rasterize(64, single, "solo");
        int color = RadialWeightWidget.stableColor("solo");
        assertTrue(Arrays.stream(raster).anyMatch(p -> p == color || p == RadialWeightWidget.brighten(color)));
    }

    @Test void selectedSliceOver180DegreesDoesNotHighlightOppositeRay() {
        List<RadialWeightWidget.Slice> slices = List.of(slice("big", 0.75), slice("small", 0.25));
        int size = 100;
        int[] raster = RadialWeightWidget.rasterize(size, slices, "big");
        int baseColor = RadialWeightWidget.stableColor("big");
        int highlightColor = RadialWeightWidget.brighten(baseColor);

        // Actual start ray is at 12 o'clock (angle 0, dy < 0). Point (51, 10) has dx=1, dy=-40, radius=40.
        int actualRayPixel = raster[10 * size + 51];
        assertEquals(highlightColor, actualRayPixel, "Actual boundary ray must be highlighted");

        // Opposite start ray is at 6 o'clock (angle pi, dy > 0). Point (50, 90) has dx=0, dy=40, radius=40.
        // This is in the interior of the 75% slice (which spans 0 to 1.5pi) and far from radial boundaries.
        int oppositeRayPixel = raster[90 * size + 50];
        assertEquals(baseColor, oppositeRayPixel,
                "Interior opposite to boundary ray must remain base color without phantom highlight");
    }

    @Test void blitParametersPassExtentsAndNonCollapsedUV() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/client/java/com/cuemymusic/client/ui/RadialWeightWidget.java"));
        assertTrue(source.contains("getX() + size") && source.contains("getY() + size"),
                "blit must pass x1 = getX() + size and y1 = getY() + size");
        assertTrue(source.contains("0.0F, 1.0F, 0.0F, 1.0F") || source.contains("0F, 1F, 0F, 1F"),
                "blit must pass u0=0, u1=1, v0=0, v1=1 to avoid collapsed UVs");
    }
}
