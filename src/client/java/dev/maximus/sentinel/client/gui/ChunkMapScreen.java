package dev.maximus.sentinel.client.gui;

import dev.maximus.sentinel.gui.ChunkMapPackets;

import dev.maximus.sentinel.gui.ChunkRegionPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;

public final class ChunkMapScreen extends Screen {

    private static final int WIDTH = 700;
    private static final int HEIGHT = 380;

    private static final int MAP_LEFT = 20;
    private static final int MAP_TOP = 70;
    private static final int MAP_RIGHT = 680;
    private static final int MAP_BOTTOM = 315;

    private static final double MAP_CENTER_X = 350;
    private static final double MAP_CENTER_Y = 192.5;

    /**
     * Zoom range.
     *
     * The minimum is four times smaller than the
     * previous minimum of 4.5 pixels per chunk.
     */
    private static final double MIN_CHUNK_SIZE = 1.125;
    private static final double MAX_CHUNK_SIZE = 44.0;

    /**
     * Grouping thresholds.
     *
     * Every time the underlying chunk size halves,
     * the map doubles the grouping resolution.
     *
     * This keeps the displayed cells large enough
     * to remain visually distinguishable.
     */
    private static final double GROUP_2_THRESHOLD = 15.0;
    private static final double GROUP_4_THRESHOLD = 7.5;
    private static final double GROUP_8_THRESHOLD = 3.75;
    private static final double GROUP_16_THRESHOLD = 1.875;

    /**
     * Cache up to 2,048 complete Minecraft regions.
     *
     * Each region contains 1,024 chunk availability
     * bits, represented by a 128-byte BitSet.
     *
     * At maximum zoom-out, a typical viewport needs
     * considerably fewer regions than this limit.
     */
    private static final int MAX_CACHED_REGIONS = 2048;

    /**
     * Maximum number of region requests outstanding
     * at the same time.
     */
    private static final int MAX_PENDING_REGIONS = 96;

    /**
     * Region fetch scheduling.
     */
    private static final int REGION_REQUEST_INTERVAL = 5;
    private static final int REGION_REQUEST_TIMEOUT = 200;

    /**
     * Key: ChunkPos.asLong(regionX, regionZ)
     *
     * Value: complete 32x32 region availability.
     *
     * An empty BitSet is a valid, fully cached,
     * entirely unavailable region.
     */
    private final LinkedHashMap<Long, BitSet> regionCache =
            new LinkedHashMap<>(256, 0.75f, true) {

                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<Long, BitSet> eldest
                ) {
                    return size() > MAX_CACHED_REGIONS;
                }
            };

    /**
     * Tracks when each outstanding region request
     * was sent, measured in screen ticks.
     *
     * This prevents requesting the same region
     * repeatedly while its response is in flight.
     */
    private final Map<Long, Long> pendingRegions =
            new LinkedHashMap<>();

    private long mapTicks;

    /*
     * Request a new viewport only when its coverage
     * is missing. A one-second cooldown prevents
     * duplicate requests during slow disk reads.
     */
    private static final int VIEWPORT_RETRY_TICKS = 20;

    private static final int PLAYER_UPDATE_TICKS = 20;

    private static final int COLOR_PANEL = 0xE010141C;
    private static final int COLOR_HEADER = 0xFF253040;

    private static final int COLOR_AVAILABLE = 0xFF347C4A;
    private static final int COLOR_UNAVAILABLE = 0xFF873737;
    private static final int COLOR_SELECTED = 0xFFD7B33D;
    private static final int COLOR_STRIPE = 0xFFC34A4A;
    private static final int COLOR_UNKNOWN = 0xFF414650;

    private static final int COLOR_BORDER = 0xFF142017;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFFB6C1CE;

    private static final int COLOR_PLAYER = 0xFFFFD348;
    private static final int COLOR_ADMIN = 0xFF398BE4;
    private static final int COLOR_VIEWER = 0xFFB56DFF;

    private static final int COLOR_BUTTON = 0xFF344255;
    private static final int COLOR_BUTTON_HOVER = 0xFF4C617C;
    private static final int COLOR_DONE = 0xFF276E46;
    private static final int COLOR_CANCEL = 0xFF8B3030;

    private final String snapshot;
    private final Screen previous;
    private final String dimension;

    private List<ChunkMapPackets.PlayerMarker> players =
            List.of();

    private double viewerX;
    private double viewerZ;

    private final Set<ChunkMapPackets.ChunkCoordinate> selected =
            new LinkedHashSet<>();

    private double centerX;
    private double centerZ;
    private double chunkSize = 24.0;

    private double scale = 1.0;
    private double originX;
    private double originY;

    private boolean selectingMultiple;
    private boolean dragging;
    private boolean dragged;

    private double pressedX;
    private double pressedY;

    private int playerTicks;

    private String status = "";

    private record ChunkArea(
            int minX,
            int minZ,
            int size
    ) {
        int maxX() {
            return minX + size - 1;
        }

        int maxZ() {
            return minZ + size - 1;
        }
    }

    private record AreaState(
            int available,
            int unavailable,
            int unknown,
            int selected
    ) {
        boolean fullySelected() {
            return available > 0 && available == selected;
        }
    }

    public ChunkMapScreen(
            String snapshot,
            Screen previous,
            ChunkMapPackets.MapData initialData
    ) {
        super(Component.literal("Sentinel Chunk Restoration"));

        this.snapshot = snapshot;
        this.previous = previous;
        this.dimension = initialData.dimension();

        this.viewerX = initialData.viewerX();
        this.viewerZ = initialData.viewerZ();

        this.centerX = viewerX / 16.0;
        this.centerZ = viewerZ / 16.0;

        updateMap(initialData);
    }

    /**
     * Initial map data and compatibility with the
     * existing map-opening packet.
     *
     * Terrain availability is now obtained through
     * the dedicated complete-region protocol.
     */
    public void updateMap(
            ChunkMapPackets.MapData updated
    ) {

        if (!updated.snapshot().equals(snapshot)
                || !updated.dimension().equals(dimension)) {
            return;
        }

        players = updated.players();

        viewerX = updated.viewerX();
        viewerZ = updated.viewerZ();
    }

    /**
     * Merge complete regions received from the server.
     *
     * Only the requested snapshot and dimension
     * can update this screen's availability cache.
     */
    public void updateRegions(
            ChunkRegionPackets.Data packet
    ) {

        if (!packet.snapshot().equals(snapshot)
                || !packet.dimension().equals(dimension)) {
            return;
        }

        for (ChunkRegionPackets.RegionEntry region :
                packet.regions()) {

            long key =
                    net.minecraft.world.level.ChunkPos.asLong(
                            region.regionX(),
                            region.regionZ()
                    );

            /*
             * A complete region is authoritative.
             *
             * Clone the bits so nothing outside this
             * screen can modify cached availability.
             */
            regionCache.put(
                    key,
                    (BitSet) region.available().clone()
            );

            pendingRegions.remove(key);
        }
    }

    /**
     * Lightweight update from the server.
     *
     * No chunk availability is transferred.
     */
    public void updatePlayers(
            ChunkMapPackets.PlayersData updated
    ) {

        if (!updated.dimension().equals(dimension)) {
            return;
        }

        players = updated.players();
        viewerX = updated.viewerX();
        viewerZ = updated.viewerZ();
    }

    @Override
    protected void init() {
        calculateScale();
    }

    private void calculateScale() {

        scale = Math.max(
                0.01,
                Math.min(
                        1.0,
                        Math.min(
                                Math.max(1.0, width - 20.0) / WIDTH,
                                Math.max(1.0, height - 20.0) / HEIGHT
                        )
                )
        );

        originX = (width - WIDTH * scale) / 2.0;
        originY = (height - HEIGHT * scale) / 2.0;
    }

    private double virtualX(double x) {
        return (x - originX) / scale;
    }

    private double virtualY(double y) {
        return (y - originY) / scale;
    }

    private static boolean inside(
            double x, double y,
            double left, double top,
            double right, double bottom
    ) {
        return x >= left && x < right
                && y >= top && y < bottom;
    }

    private boolean insideMap(double x, double y) {
        return inside(
                x, y,
                MAP_LEFT, MAP_TOP,
                MAP_RIGHT, MAP_BOTTOM
        );
    }

    private double mapX(double chunkX) {
        return MAP_CENTER_X + (chunkX - centerX) * chunkSize;
    }

    private double mapY(double chunkZ) {
        return MAP_CENTER_Y + (chunkZ - centerZ) * chunkSize;
    }

    private double chunkAtX(double x) {
        return centerX + (x - MAP_CENTER_X) / chunkSize;
    }

    private double chunkAtZ(double y) {
        return centerZ + (y - MAP_CENTER_Y) / chunkSize;
    }

    /**
     * Returns the grouping resolution for the
     * current zoom level.
     *
     * Each level represents progressively larger
     * areas of the world.
     *
     * All boundaries remain aligned to chunk
     * coordinates, including negative coordinates.
     */
    private int groupSize() {

        if (chunkSize < GROUP_16_THRESHOLD) {
            return 16;
        }

        if (chunkSize < GROUP_8_THRESHOLD) {
            return 8;
        }

        if (chunkSize < GROUP_4_THRESHOLD) {
            return 4;
        }

        if (chunkSize < GROUP_2_THRESHOLD) {
            return 2;
        }

        return 1;
    }

    private ChunkArea areaAt(double x, double y) {

        int chunkX = (int) Math.floor(chunkAtX(x));
        int chunkZ = (int) Math.floor(chunkAtZ(y));

        int size = groupSize();

        return new ChunkArea(
                Math.floorDiv(chunkX, size) * size,
                Math.floorDiv(chunkZ, size) * size,
                size
        );
    }

    /**
     * Look up the availability of an individual chunk
     * through its containing 32x32 region.
     *
     * Returns:
     *
     * null  = region not cached
     * true  = chunk exists in snapshot
     * false = chunk does not exist in snapshot
     */
    private Boolean cached(int chunkX, int chunkZ) {

        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);

        long key =
                net.minecraft.world.level.ChunkPos.asLong(
                        regionX,
                        regionZ
                );

        BitSet region = regionCache.get(key);

        if (region == null) {
            return null;
        }

        int localX = Math.floorMod(chunkX, 32);
        int localZ = Math.floorMod(chunkZ, 32);

        return region.get(localZ * 32 + localX);
    }

    private AreaState stateOf(ChunkArea area) {

        int valid = 0;
        int invalid = 0;
        int unknown = 0;
        int selectedCount = 0;

        for (int z = area.minZ(); z <= area.maxZ(); z++) {
            for (int x = area.minX(); x <= area.maxX(); x++) {

                Boolean exists = cached(x, z);

                if (exists == null) {
                    unknown++;
                } else if (exists) {

                    valid++;

                    if (selected.contains(
                            new ChunkMapPackets.ChunkCoordinate(x, z)
                    )) {
                        selectedCount++;
                    }
                } else {
                    invalid++;
                }
            }
        }

        return new AreaState(
                valid, invalid, unknown, selectedCount
        );
    }

    private List<ChunkMapPackets.ChunkCoordinate> validChunks(
            ChunkArea area
    ) {

        List<ChunkMapPackets.ChunkCoordinate> result =
                new ArrayList<>(area.size() * area.size());

        for (int z = area.minZ(); z <= area.maxZ(); z++) {
            for (int x = area.minX(); x <= area.maxX(); x++) {

                if (Boolean.TRUE.equals(cached(x, z))) {
                    result.add(
                            new ChunkMapPackets.ChunkCoordinate(x, z)
                    );
                }
            }
        }

        return result;
    }

    @Override
    public void tick() {

        mapTicks++;

        /*
         * Refresh player markers once per second.
         *
         * This never requests terrain availability.
         */
        if (++playerTicks >= PLAYER_UPDATE_TICKS) {

            playerTicks = 0;

            ClientPlayNetworking.send(
                    new ChunkMapPackets.PlayersRequest()
            );
        }

        /*
         * Check for uncached regions every five ticks.
         *
         * No request is sent if all visible regions
         * are already cached.
         */
        if (mapTicks % REGION_REQUEST_INTERVAL == 0) {

            requestViewportIfNeeded();
        }
    }

    /**
     * Returns the inclusive world-region boundaries
     * needed to cover the viewport.
     *
     * A small margin is included to avoid requesting
     * data every time the map moves a few pixels.
     */
    private int[] visibleRegionBounds() {

        double left = chunkAtX(MAP_LEFT);
        double right = chunkAtX(MAP_RIGHT);

        double top = chunkAtZ(MAP_TOP);
        double bottom = chunkAtZ(MAP_BOTTOM);

        /*
         * Include one extra 16x16 section around
         * the viewport at maximum compression.
         */
        int margin = Math.max(4, groupSize());

        int minChunkX = (int) Math.floor(left) - margin;
        int maxChunkX = (int) Math.ceil(right) + margin;

        int minChunkZ = (int) Math.floor(top) - margin;
        int maxChunkZ = (int) Math.ceil(bottom) + margin;

        return new int[] {
                Math.floorDiv(minChunkX, 32),
                Math.floorDiv(maxChunkX, 32),
                Math.floorDiv(minChunkZ, 32),
                Math.floorDiv(maxChunkZ, 32)
        };
    }

    /**
     * Check whether every region covering the
     * visible map has already been received.
     *
     * This is now a region-level operation:
     * typically hundreds of lookups rather
     * than hundreds of thousands.
     */
    private boolean viewportCached() {

        int[] bounds = visibleRegionBounds();

        for (int regionZ = bounds[2];
             regionZ <= bounds[3];
             regionZ++) {

            for (int regionX = bounds[0];
                 regionX <= bounds[1];
                 regionX++) {

                long key =
                        net.minecraft.world.level.ChunkPos.asLong(
                                regionX,
                                regionZ
                        );

                if (!regionCache.containsKey(key)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Request complete 32x32 regions missing
     * from the local cache.
     *
     * Requests are:
     *
     * - Batched into groups of 32 regions
     * - Deduplicated while pending
     * - Bounded to 96 outstanding regions
     * - Retried after a timeout
     */
    private void requestViewportIfNeeded() {

        /*
         * Expire requests which have not received
         * a response within the timeout.
         */
        Iterator<Map.Entry<Long, Long>> iterator =
                pendingRegions.entrySet().iterator();

        while (iterator.hasNext()) {

            Map.Entry<Long, Long> pending =
                    iterator.next();

            if (mapTicks - pending.getValue()
                    >= REGION_REQUEST_TIMEOUT) {

                iterator.remove();
            }
        }

        if (viewportCached()) {
            return;
        }

        if (pendingRegions.size() >= MAX_PENDING_REGIONS) {
            return;
        }

        int[] bounds = visibleRegionBounds();

        List<ChunkRegionPackets.RegionCoordinate> missing =
                new ArrayList<>(
                        ChunkRegionPackets.MAX_REGIONS_PER_REQUEST
                );

        /*
         * Iterate visible regions, not individual
         * chunks. Empty regions are cached too.
         */
        outer:
        for (int regionZ = bounds[2];
             regionZ <= bounds[3];
             regionZ++) {

            for (int regionX = bounds[0];
                 regionX <= bounds[1];
                 regionX++) {

                long key =
                        net.minecraft.world.level.ChunkPos.asLong(
                                regionX,
                                regionZ
                        );

                if (regionCache.containsKey(key)
                        || pendingRegions.containsKey(key)) {

                    continue;
                }

                missing.add(
                        new ChunkRegionPackets.RegionCoordinate(
                                regionX,
                                regionZ
                        )
                );

                pendingRegions.put(key, mapTicks);

                if (missing.size()
                        >= ChunkRegionPackets.MAX_REGIONS_PER_REQUEST
                        || pendingRegions.size()
                        >= MAX_PENDING_REGIONS) {

                    break outer;
                }
            }
        }

        if (missing.isEmpty()) {
            return;
        }

        ClientPlayNetworking.send(
                new ChunkRegionPackets.Request(
                        snapshot,
                        dimension,
                        List.copyOf(missing)
                )
        );
    }

    @Override
    public void render(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {

        renderBackground(
                graphics,
                mouseX,
                mouseY,
                partialTick
        );

        calculateScale();

        double mx = virtualX(mouseX);
        double my = virtualY(mouseY);

        graphics.pose().pushPose();

        graphics.pose().translate(originX, originY, 0);
        graphics.pose().scale(
                (float) scale, (float) scale, 1.0F
        );

        renderPanel(graphics);
        renderMap(graphics, mx, my);
        renderFooter(graphics, mx, my);

        graphics.pose().popPose();
    }

    private void renderPanel(GuiGraphics graphics) {

        graphics.fill(
                0, 0, WIDTH, HEIGHT, COLOR_PANEL
        );

        graphics.fill(
                0, 0, WIDTH, 60, COLOR_HEADER
        );

        graphics.drawCenteredString(
                font,
                "Sentinel - Chunk Restoration",
                WIDTH / 2, 12, COLOR_TEXT
        );

        graphics.drawCenteredString(
                font,
                "Snapshot: " + snapshot,
                WIDTH / 2, 28, COLOR_MUTED
        );

        graphics.drawString(
                font, "You", 45, 49, COLOR_VIEWER, false
        );

        graphics.drawString(
                font, "Admins", 115, 49, COLOR_ADMIN, false
        );

        graphics.drawString(
                font, "Players", 205, 49, COLOR_PLAYER, false
        );

        graphics.drawString(
                font,
                "Grid: " + groupSize() + "x" + groupSize(),
                580, 49, COLOR_MUTED, false
        );
    }

    private void renderMap(
            GuiGraphics graphics,
            double mouseX,
            double mouseY
    ) {

        graphics.fill(
                MAP_LEFT, MAP_TOP,
                MAP_RIGHT, MAP_BOTTOM,
                0xFF171B22
        );

        int size = groupSize();

        int minX = (int) Math.floor(chunkAtX(MAP_LEFT));
        int maxX = (int) Math.ceil(chunkAtX(MAP_RIGHT));

        int minZ = (int) Math.floor(chunkAtZ(MAP_TOP));
        int maxZ = (int) Math.ceil(chunkAtZ(MAP_BOTTOM));

        int startX = Math.floorDiv(minX, size) * size;
        int startZ = Math.floorDiv(minZ, size) * size;

        for (int z = startZ; z <= maxZ; z += size) {
            for (int x = startX; x <= maxX; x += size) {

                drawArea(
                        graphics,
                        new ChunkArea(x, z, size)
                );
            }
        }

        renderPlayers(graphics);

        if (insideMap(mouseX, mouseY)) {

            ChunkArea area = areaAt(mouseX, mouseY);
            AreaState state = stateOf(area);

            String text;

            if (area.size() == 1) {

                text = "Chunk: "
                        + area.minX() + ", " + area.minZ();

            } else {

                text = "Chunks: ("
                        + area.minX() + "-"
                        + area.maxX() + ", "
                        + area.minZ() + "-"
                        + area.maxZ() + ")";
            }

            text += " | " + state.available()
                    + "/" + (area.size() * area.size())
                    + " available";

            int tooltipWidth = font.width(text) + 12;

            int tx = (int) Math.max(
                    MAP_LEFT + 3,
                    Math.min(
                            mouseX + 10,
                            MAP_RIGHT - tooltipWidth - 4
                    )
            );

            int ty = (int) Math.min(
                    mouseY + 10,
                    MAP_BOTTOM - 17
            );

            graphics.fill(
                    tx, ty,
                    tx + tooltipWidth, ty + 16,
                    0xE9000000
            );

            graphics.drawString(
                    font, text,
                    tx + 6, ty + 4,
                    COLOR_TEXT, false
            );
        }
    }

    private void drawArea(
            GuiGraphics graphics,
            ChunkArea area
    ) {

        int left = (int) Math.floor(mapX(area.minX()));
        int top = (int) Math.floor(mapY(area.minZ()));

        int right = (int) Math.ceil(
                mapX((double) area.minX() + area.size())
        );

        int bottom = (int) Math.ceil(
                mapY((double) area.minZ() + area.size())
        );

        int x1 = Math.max(MAP_LEFT, left);
        int y1 = Math.max(MAP_TOP, top);

        int x2 = Math.min(MAP_RIGHT, right);
        int y2 = Math.min(MAP_BOTTOM, bottom);

        if (x1 >= x2 || y1 >= y2) {
            return;
        }

        AreaState state = stateOf(area);

        int color;

        if (state.unknown() > 0) {
            color = COLOR_UNKNOWN;
        } else if (state.available() == 0) {
            color = COLOR_UNAVAILABLE;
        } else if (state.fullySelected()) {
            color = COLOR_SELECTED;
        } else {
            color = COLOR_AVAILABLE;
        }

        graphics.fill(x1, y1, x2, y2, color);

        if (state.available() > 0
                && state.unavailable() > 0
                && state.unknown() == 0) {

            drawStripes(
                    graphics,
                    left, top,
                    x1, y1, x2, y2
            );
        }

        if (state.selected() > 0
                && !state.fullySelected()) {

            graphics.fill(
                    x1, y1,
                    Math.min(x1 + 4, x2), y2,
                    COLOR_SELECTED
            );
        }

        if (left >= MAP_LEFT && left < MAP_RIGHT) {
            graphics.fill(
                    left, y1,
                    Math.min(left + 1, x2), y2,
                    COLOR_BORDER
            );
        }

        if (top >= MAP_TOP && top < MAP_BOTTOM) {
            graphics.fill(
                    x1, top,
                    x2, Math.min(top + 1, y2),
                    COLOR_BORDER
            );
        }
    }

    private void drawStripes(
            GuiGraphics graphics,
            int originLeft,
            int originTop,
            int left,
            int top,
            int right,
            int bottom
    ) {

        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {

                int phase = Math.floorMod(
                        (x - originLeft) + (y - originTop),
                        9
                );

                if (phase < 3) {
                    graphics.fill(
                            x, y, x + 1, y + 1,
                            COLOR_STRIPE
                    );
                }
            }
        }
    }

    private void renderPlayers(GuiGraphics graphics) {

        for (ChunkMapPackets.PlayerMarker player : players) {

            int x = (int) Math.round(mapX(player.x() / 16.0));
            int y = (int) Math.round(mapY(player.z() / 16.0));

            if (x < MAP_LEFT + 4 || x >= MAP_RIGHT - 4
                    || y < MAP_TOP + 4 || y >= MAP_BOTTOM - 4) {
                continue;
            }

            int color = switch (player.role()) {
                case 2 -> COLOR_VIEWER;
                case 1 -> COLOR_ADMIN;
                default -> COLOR_PLAYER;
            };

            graphics.fill(
                    x - 4, y - 4,
                    x + 5, y + 5,
                    0xFF101010
            );

            graphics.fill(
                    x - 3, y - 3,
                    x + 4, y + 4,
                    color
            );
        }
    }

    private void renderFooter(
            GuiGraphics graphics,
            double mouseX,
            double mouseY
    ) {

        graphics.drawString(
                font,
                selected.size() + " chunks selected",
                22, 324, COLOR_MUTED, false
        );

        graphics.drawString(
                font,
                "Scroll to zoom - Drag to pan",
                425, 324, COLOR_MUTED, false
        );

        drawButton(
                graphics,
                30, 345, 90, 22,
                "Back", COLOR_BUTTON,
                mouseX, mouseY
        );

        if (selectingMultiple) {

            drawButton(
                    graphics,
                    270, 345, 76, 22,
                    "Cancel", COLOR_CANCEL,
                    mouseX, mouseY
            );

            drawButton(
                    graphics,
                    354, 345, 76, 22,
                    "Done", COLOR_DONE,
                    mouseX, mouseY
            );

        } else {

            drawButton(
                    graphics,
                    270, 345, 160, 22,
                    "Select Multiple", COLOR_BUTTON,
                    mouseX, mouseY
            );
        }

        drawButton(
                graphics,
                580, 345, 90, 22,
                "Recenter", COLOR_BUTTON,
                mouseX, mouseY
        );

        if (!status.isEmpty()) {

            graphics.drawCenteredString(
                    font, status,
                    WIDTH / 2, 334, COLOR_MUTED
            );
        }
    }

    private void drawButton(
            GuiGraphics graphics,
            int x, int y,
            int width, int height,
            String label,
            int color,
            double mouseX, double mouseY
    ) {

        boolean hovered = inside(
                mouseX, mouseY,
                x, y, x + width, y + height
        );

        graphics.fill(
                x, y, x + width, y + height,
                hovered ? COLOR_BUTTON_HOVER : color
        );

        graphics.drawCenteredString(
                font, label,
                x + width / 2,
                y + 7,
                COLOR_TEXT
        );
    }

    @Override
    public boolean mouseClicked(
            double mouseX,
            double mouseY,
            int button
    ) {

        if (button != 0) {
            return super.mouseClicked(
                    mouseX, mouseY, button
            );
        }

        calculateScale();

        double x = virtualX(mouseX);
        double y = virtualY(mouseY);

        if (insideMap(x, y)) {

            dragging = true;
            dragged = false;

            pressedX = x;
            pressedY = y;

            return true;
        }

        if (inside(x, y, 30, 345, 120, 367)) {
            onClose();
            return true;
        }

        if (inside(x, y, 580, 345, 670, 367)) {
            recenter();
            return true;
        }

        if (!selectingMultiple) {

            if (inside(x, y, 270, 345, 430, 367)) {
                selectingMultiple = true;
                selected.clear();
                status = "";
                return true;
            }

        } else {

            if (inside(x, y, 270, 345, 346, 367)) {
                selected.clear();
                selectingMultiple = false;
                status = "";
                return true;
            }

            if (inside(x, y, 354, 345, 430, 367)) {
                restoreSelection();
                return true;
            }
        }

        return super.mouseClicked(
                mouseX, mouseY, button
        );
    }

    @Override
    public boolean mouseDragged(
            double mouseX,
            double mouseY,
            int button,
            double dragX,
            double dragY
    ) {

        if (button != 0 || !dragging) {
            return super.mouseDragged(
                    mouseX, mouseY,
                    button, dragX, dragY
            );
        }

        calculateScale();

        double x = virtualX(mouseX);
        double y = virtualY(mouseY);

        if (Math.hypot(
                x - pressedX,
                y - pressedY
        ) > 3.0) {
            dragged = true;
        }

        if (dragged) {

            centerX -= (dragX / scale) / chunkSize;
            centerZ -= (dragY / scale) / chunkSize;
        }

        return true;
    }

    @Override
    public boolean mouseReleased(
            double mouseX,
            double mouseY,
            int button
    ) {

        if (button == 0 && dragging) {

            dragging = false;

            if (!dragged) {

                double x = virtualX(mouseX);
                double y = virtualY(mouseY);

                if (insideMap(x, y)) {
                    selectArea(areaAt(x, y));
                }
            }

            /*
             * Fetch missing data after the drag,
             * rather than on every drag event.
             */
            requestViewportIfNeeded();

            return true;
        }

        return super.mouseReleased(
                mouseX, mouseY, button
        );
    }

    private void selectArea(ChunkArea area) {

        AreaState state = stateOf(area);

        if (state.unknown() > 0) {
            status = "Loading chunk availability...";
            requestViewportIfNeeded();
            return;
        }

        List<ChunkMapPackets.ChunkCoordinate> valid =
                validChunks(area);

        if (valid.isEmpty()) {
            return;
        }

        if (selectingMultiple) {

            if (selected.containsAll(valid)) {

                selected.removeAll(valid);

            } else {

                int additional = 0;

                for (var chunk : valid) {
                    if (!selected.contains(chunk)) {
                        additional++;
                    }
                }

                if (selected.size() + additional
                        > ChunkMapPackets.MAX_BATCH) {

                    status = "Maximum "
                            + ChunkMapPackets.MAX_BATCH
                            + " chunks per batch.";

                    return;
                }

                selected.addAll(valid);
            }

            status = selected.size() + " chunks selected.";
            return;
        }

        if (selected.size() == valid.size()
                && selected.containsAll(valid)) {

            restoreSelection();

        } else {

            selected.clear();
            selected.addAll(valid);

            status = "Click again to restore "
                    + valid.size() + " chunk(s).";
        }
    }

    private void restoreSelection() {

        if (selected.isEmpty()) {
            status = "No chunks selected.";
            return;
        }

        if (selected.size() > ChunkMapPackets.MAX_BATCH) {
            status = "Too many chunks selected.";
            return;
        }

        ClientPlayNetworking.send(
                new ChunkMapPackets.Restore(
                        snapshot,
                        dimension,
                        new ArrayList<>(selected)
                )
        );

        status = "Queued "
                + selected.size()
                + " chunk(s) for restoration.";

        selected.clear();
        selectingMultiple = false;
    }

    private void recenter() {

        centerX = viewerX / 16.0;
        centerZ = viewerZ / 16.0;

        requestViewportIfNeeded();
    }

    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double scrollX,
            double scrollY
    ) {

        calculateScale();

        double x = virtualX(mouseX);
        double y = virtualY(mouseY);

        if (!insideMap(x, y)) {
            return super.mouseScrolled(
                    mouseX, mouseY,
                    scrollX, scrollY
            );
        }

        double beforeX = chunkAtX(x);
        double beforeZ = chunkAtZ(y);

        chunkSize *= Math.pow(1.15, scrollY);

        chunkSize = Math.max(
                MIN_CHUNK_SIZE,
                Math.min(MAX_CHUNK_SIZE, chunkSize)
        );

        centerX += beforeX - chunkAtX(x);
        centerZ += beforeZ - chunkAtZ(y);

        requestViewportIfNeeded();

        return true;
    }

    @Override
    public void onClose() {

        if (minecraft != null) {
            minecraft.setScreen(previous);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}