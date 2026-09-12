package com.cuemymusic.client.ui;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Pure CPU-rasterized radial probability wheel widget.
 *
 * <p>Wedge angular sizes correspond to normalized track selection chances.
 * Visual output uses a standard Minecraft dynamic texture and vanilla GuiGraphicsExtractor blit,
 * keeping the implementation completely neutral of graphics backends.
 */
public final class RadialWeightWidget extends AbstractWidget implements AutoCloseable {

    public record Slice(String resourceId, String label, double chance, int color) {}

    static final double INNER_RADIUS_RATIO = 0.34;
    static final double OUTER_RADIUS_RATIO = 0.48;
    static final int MUTED_COLOR = 0xFF444444;

    private static final int[] PALETTE = {
            0xFF4E79A7, // Slate Blue
            0xFFF28E2B, // Orange
            0xFFE15759, // Coral Red
            0xFF76B7B2, // Teal
            0xFF59A14F, // Green
            0xFFEDC948, // Yellow
            0xFFB07AA1, // Purple
            0xFFFF9DA7, // Pink
            0xFF9C755F, // Brown
            0xFFBAB0AC, // Warm Gray
            0xFF5B84B1, // Steel Blue
            0xFF56B4E9, // Sky Blue
            0xFF009E73, // Bluish Green
            0xFFD55E00, // Vermillion
            0xFFCC79A7, // Reddish Purple
            0xFFF0E442  // Bright Yellow
    };

    private final Identifier textureId;
    private final Consumer<String> onSelect;
    private final int size;
    private List<Slice> slices = List.of();
    private String selectedResourceId;

    public RadialWeightWidget(int x, int y, int size, Consumer<String> onSelect) {
        super(x, y, clampSize(size), clampSize(size), Component.literal("Probability Wheel"));
        this.size = clampSize(size);
        this.onSelect = onSelect;
        this.textureId = Identifier.fromNamespaceAndPath("cue_my_music", "dynamic/radial_weights");
    }

    public static int clampSize(int size) {
        return Math.clamp(size, 16, 512);
    }

    public static int stableColor(String resourceId) {
        if (resourceId == null) {
            return PALETTE[0];
        }
        int index = Math.floorMod(resourceId.hashCode(), PALETTE.length);
        return PALETTE[index];
    }

    public static Optional<String> hitTest(List<Slice> slices, double x, double y, int size) {
        int clampedSize = clampSize(size);
        if (x < 0 || x > clampedSize || y < 0 || y > clampedSize) {
            return Optional.empty();
        }
        double cx = clampedSize / 2.0;
        double cy = clampedSize / 2.0;
        double dx = x - cx;
        double dy = y - cy;
        double radius = Math.hypot(dx, dy);
        double innerRadius = clampedSize * INNER_RADIUS_RATIO;
        double outerRadius = clampedSize * OUTER_RADIUS_RATIO;
        if (radius < innerRadius || radius > outerRadius) {
            return Optional.empty();
        }
        if (slices == null || slices.isEmpty()) {
            return Optional.empty();
        }

        List<Slice> positive = slices.stream()
                .filter(s -> s != null && s.chance() > 0.0)
                .toList();
        if (positive.isEmpty()) {
            return Optional.empty();
        }

        double totalChance = positive.stream().mapToDouble(Slice::chance).sum();
        if (totalChance <= 0.0) {
            return Optional.empty();
        }

        double angle = (Math.atan2(dx, -dy) + 2 * Math.PI) % (2 * Math.PI);
        double frac = angle / (2 * Math.PI);
        if (frac >= 1.0) {
            frac = 0.0;
        }

        double currentEnd = 0.0;
        for (Slice slice : positive) {
            double sliceFrac = slice.chance() / totalChance;
            double next = currentEnd + sliceFrac;
            if (frac >= currentEnd && (frac < next || slice == positive.getLast())) {
                return Optional.ofNullable(slice.resourceId());
            }
            currentEnd = next;
        }

        return Optional.ofNullable(positive.getLast().resourceId());
    }

    static int[] rasterize(int size, List<Slice> slices, String selectedResourceId) {
        int clampedSize = clampSize(size);
        int[] pixels = new int[clampedSize * clampedSize];
        double cx = clampedSize / 2.0;
        double cy = clampedSize / 2.0;
        double innerRadius = clampedSize * INNER_RADIUS_RATIO;
        double outerRadius = clampedSize * OUTER_RADIUS_RATIO;

        List<Slice> positive = slices != null
                ? slices.stream().filter(s -> s != null && s.chance() > 0.0).toList()
                : List.of();
        double totalChance = positive.stream().mapToDouble(Slice::chance).sum();

        for (int py = 0; py < clampedSize; py++) {
            for (int px = 0; px < clampedSize; px++) {
                double dx = (px + 0.5) - cx;
                double dy = (py + 0.5) - cy;
                double radius = Math.hypot(dx, dy);

                if (radius < innerRadius || radius > outerRadius) {
                    pixels[py * clampedSize + px] = 0;
                    continue;
                }

                if (positive.isEmpty() || totalChance <= 0.0) {
                    pixels[py * clampedSize + px] = MUTED_COLOR;
                    continue;
                }

                double angle = (Math.atan2(dx, -dy) + 2 * Math.PI) % (2 * Math.PI);
                double frac = angle / (2 * Math.PI);
                if (frac >= 1.0) {
                    frac = 0.0;
                }

                Slice matched = null;
                double matchedStart = 0.0;
                double matchedEnd = 1.0;
                double currentEnd = 0.0;

                for (Slice slice : positive) {
                    double sliceFrac = slice.chance() / totalChance;
                    double next = currentEnd + sliceFrac;
                    if (frac >= currentEnd && (frac < next || slice == positive.getLast())) {
                        matched = slice;
                        matchedStart = currentEnd;
                        matchedEnd = Math.min(1.0, next);
                        break;
                    }
                    currentEnd = next;
                }

                if (matched == null) {
                    matched = positive.getLast();
                    matchedStart = 1.0 - (matched.chance() / totalChance);
                    matchedEnd = 1.0;
                }

                boolean isSelected = selectedResourceId != null && selectedResourceId.equals(matched.resourceId());
                int color = matched.color();
                if (isSelected) {
                    double distToInner = Math.abs(radius - innerRadius);
                    double distToOuter = Math.abs(radius - outerRadius);
                    boolean nearBoundary = distToInner <= 2.0 || distToOuter <= 2.0;
                    if (!nearBoundary && positive.size() > 1) {
                        double startAngle = matchedStart * 2 * Math.PI;
                        double endAngle = matchedEnd * 2 * Math.PI;
                        double dAngleStart = angleDiff(angle, startAngle);
                        double dAngleEnd = angleDiff(angle, endAngle);
                        boolean nearStart = dAngleStart <= Math.PI / 2.0 && (radius * Math.sin(dAngleStart) <= 2.0);
                        boolean nearEnd = dAngleEnd <= Math.PI / 2.0 && (radius * Math.sin(dAngleEnd) <= 2.0);
                        nearBoundary = nearStart || nearEnd;
                    }
                    if (nearBoundary) {
                        color = brighten(color);
                    }
                }

                pixels[py * clampedSize + px] = color;
            }
        }

        return pixels;
    }

    static double angleDiff(double a, double b) {
        double diff = Math.abs(a - b) % (2 * Math.PI);
        return diff > Math.PI ? 2 * Math.PI - diff : diff;
    }

    static int brighten(int color) {
        int a = (color >>> 24) & 0xFF;
        int r = (color >>> 16) & 0xFF;
        int g = (color >>> 8) & 0xFF;
        int b = color & 0xFF;
        r = Math.min(255, (int) (r * 1.35) + 35);
        g = Math.min(255, (int) (g * 1.35) + 35);
        b = Math.min(255, (int) (b * 1.35) + 35);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public void setModel(List<Slice> slices, String selectedResourceId) {
        this.slices = slices != null ? List.copyOf(slices) : List.of();
        this.selectedResourceId = selectedResourceId;
        updateTexture();
    }

    private void updateTexture() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getTextureManager() == null) {
            return;
        }
        mc.getTextureManager().release(textureId);
        int[] pixels = rasterize(size, slices, selectedResourceId);
        NativeImage image = new NativeImage(size, size, true);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setPixel(x, y, pixels[y * size + x]);
            }
        }
        DynamicTexture dynamicTexture = new DynamicTexture(() -> "Cue My Music radial weights", image);
        mc.getTextureManager().register(textureId, dynamicTexture);
    }

    @Override
    public void close() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getTextureManager() != null) {
            mc.getTextureManager().release(textureId);
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickProgress) {
        extractor.blit(textureId, getX(), getY(), getX() + size, getY() + size, 0.0F, 1.0F, 0.0F, 1.0F);

        double relX = mouseX - getX();
        double relY = mouseY - getY();
        Optional<String> hit = hitTest(slices, relX, relY, size);
        if (hit.isPresent()) {
            String hitId = hit.get();
            for (Slice slice : slices) {
                if (slice.resourceId().equals(hitId)) {
                    String tooltipText = slice.label() + " (" + TrackWeightScreen.formatPercent(slice.chance()) + "%)";
                    extractor.setTooltipForNextFrame(Component.literal(tooltipText), mouseX, mouseY);
                    break;
                }
            }
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        double relX = event.x() - getX();
        double relY = event.y() - getY();
        Optional<String> hit = hitTest(slices, relX, relY, size);
        if (hit.isPresent() && onSelect != null) {
            onSelect.accept(hit.get());
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal("Probability Wheel"));
    }

    public List<Slice> slices() {
        return slices;
    }

    public String selectedResourceId() {
        return selectedResourceId;
    }
}
