package dev.maximus.sentinel.client.gui;

import dev.maximus.sentinel.gui.ChunkMapPackets;
import dev.maximus.sentinel.gui.SnapshotGuiPackets;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;

import java.util.List;
import java.util.Locale;

public final class SnapshotGuiScreen extends Screen {

    /*
     * All positions below are defined in a virtual
     * coordinate system.
     *
     * The complete interface is uniformly scaled
     * to fit the available Minecraft window.
     */
    private static final int DESIGN_WIDTH = 700;
    private static final int DESIGN_HEIGHT = 380;

    private static final int PADDING = 14;

    private static final int HEADER_HEIGHT = 78;
    private static final int FOOTER_HEIGHT = 54;

    private static final int ROW_HEIGHT = 28;
    private static final int BUTTON_HEIGHT = 20;

    /*
     * Column positions.
     */
    private static final int DATE_X = 25;
    private static final int AGE_X = 163;
    private static final int REASON_X = 264;

    /*
     * Action buttons.
     */
    private static final int RESTORE_X = 365;
    private static final int RESTORE_WIDTH = 61;

    private static final int CHUNKS_X = 431;
    private static final int CHUNKS_WIDTH = 59;

    private static final int LOCK_X = 495;
    private static final int LOCK_WIDTH = 78;

    private static final int DELETE_X = 578;
    private static final int DELETE_WIDTH = 61;

    /*
     * Footer.
     */
    private static final int FOOTER_BUTTON_WIDTH = 94;
    private static final int FOOTER_BUTTON_HEIGHT = 22;

    private static final int REFRESH_X = 251;
    private static final int CLOSE_X = 355;

    /*
     * Colours.
     */
    private static final int COLOR_PANEL = 0xE010141C;
    private static final int COLOR_HEADER = 0xFF253040;

    private static final int COLOR_ROW = 0xFF202833;
    private static final int COLOR_ROW_ALT = 0xFF252E3B;
    private static final int COLOR_ROW_HOVER = 0xFF344356;

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFFB3BFCA;

    private static final int COLOR_RESTORE = 0xFF276E46;
    private static final int COLOR_RESTORE_HOVER = 0xFF34955E;

    private static final int COLOR_CHUNKS = 0xFF315A86;
    private static final int COLOR_CHUNKS_HOVER = 0xFF4278AE;

    private static final int COLOR_UNLOCKED = 0xFF327449;
    private static final int COLOR_UNLOCKED_HOVER = 0xFF439E64;

    private static final int COLOR_LOCKED = 0xFF986D24;
    private static final int COLOR_LOCKED_HOVER = 0xFFC28D35;

    private static final int COLOR_DELETE = 0xFF8B3030;
    private static final int COLOR_DELETE_HOVER = 0xFFC24040;

    private static final int COLOR_DISABLED = 0xFF555B63;
    private static final int COLOR_CONFIRM = 0xFFB57B20;

    private static final int COLOR_FOOTER_BUTTON = 0xFF242B37;
    private static final int COLOR_FOOTER_HOVER = 0xFF364659;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern(
                    "MMM d HH:mm:ss",
                    Locale.ENGLISH
            );

    private List<SnapshotGuiPackets.SnapshotEntry> snapshots;

    /*
     * Virtual-to-screen transformation.
     */
    private double scale = 1.0;
    private double originX;
    private double originY;

    /*
     * Scrolling uses a row index instead of pixels.
     *
     * This ensures rows are always fully visible
     * and avoids partial-row clipping.
     */
    private int firstVisibleRow;

    private String confirmationId;

    private SnapshotGuiPackets.SnapshotAction.Action confirmationAction;

    private String status = "";

    public SnapshotGuiScreen(
            List<SnapshotGuiPackets.SnapshotEntry> snapshots
    ) {

        super(Component.literal("Sentinel Snapshots"));

        this.snapshots = List.copyOf(snapshots);
    }

    /**
     * Updates the currently open GUI without
     * recreating the screen.
     */
    public void updateSnapshots(
            List<SnapshotGuiPackets.SnapshotEntry> updated
    ) {

        this.snapshots = List.copyOf(updated);

        firstVisibleRow = Math.max(
                0,
                Math.min(
                        firstVisibleRow,
                        maximumFirstRow()
                )
        );

        confirmationId = null;
        confirmationAction = null;

        status = "";
    }

    @Override
    protected void init() {

        recalculateLayout();

        firstVisibleRow = Math.max(
                0,
                Math.min(
                        firstVisibleRow,
                        maximumFirstRow()
                )
        );

        confirmationId = null;
        confirmationAction = null;
    }

    /**
     * Uniformly scales the entire interface.
     */
    private void recalculateLayout() {

        double availableWidth =
                Math.max(1, width - 20);

        double availableHeight =
                Math.max(1, height - 20);

        double scaleX =
                availableWidth / DESIGN_WIDTH;

        double scaleY =
                availableHeight / DESIGN_HEIGHT;

        scale = Math.min(
                1.0,
                Math.min(scaleX, scaleY)
        );

        scale = Math.max(
                0.01,
                scale
        );

        originX =
                (width - DESIGN_WIDTH * scale) / 2.0;

        originY =
                (height - DESIGN_HEIGHT * scale) / 2.0;
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

        recalculateLayout();

        double virtualMouseX =
                (mouseX - originX) / scale;

        double virtualMouseY =
                (mouseY - originY) / scale;

        graphics.pose().pushPose();

        graphics.pose().translate(
                originX,
                originY,
                0
        );

        graphics.pose().scale(
                (float) scale,
                (float) scale,
                1.0F
        );

        renderPanel(graphics);
        renderHeaders(graphics);

        renderRows(
                graphics,
                virtualMouseX,
                virtualMouseY
        );

        renderFooter(
                graphics,
                virtualMouseX,
                virtualMouseY
        );

        graphics.pose().popPose();
    }

    private void renderPanel(
            GuiGraphics graphics
    ) {

        graphics.fill(
                0,
                0,
                DESIGN_WIDTH,
                DESIGN_HEIGHT,
                COLOR_PANEL
        );

        graphics.fill(
                0,
                0,
                DESIGN_WIDTH,
                HEADER_HEIGHT,
                COLOR_HEADER
        );

        graphics.drawCenteredString(
                font,
                "Sentinel Snapshots",
                DESIGN_WIDTH / 2,
                17,
                COLOR_TEXT
        );

        graphics.drawCenteredString(
                font,
                snapshots.size() + " snapshots",
                DESIGN_WIDTH / 2,
                35,
                COLOR_MUTED
        );
    }

    private void renderHeaders(
            GuiGraphics graphics
    ) {

        int y = 66;

        graphics.drawString(
                font,
                "Snapshot",
                DATE_X,
                y,
                COLOR_MUTED,
                false
        );

        graphics.drawString(
                font,
                "Age",
                AGE_X,
                y,
                COLOR_MUTED,
                false
        );

        graphics.drawString(
                font,
                "Reason",
                REASON_X,
                y,
                COLOR_MUTED,
                false
        );

        graphics.drawString(
                font,
                "Actions",
                RESTORE_X,
                y,
                COLOR_MUTED,
                false
        );
    }

    private int listTop() {
        return HEADER_HEIGHT + 6;
    }

    private int listBottom() {
        return DESIGN_HEIGHT - FOOTER_HEIGHT;
    }

    private int listHeight() {
        return listBottom() - listTop();
    }

    /**
     * Number of complete rows that fit within
     * the snapshot list.
     */
    private int visibleRowCount() {

        return Math.max(
                1,
                listHeight() / ROW_HEIGHT
        );
    }

    /**
     * Maximum first-visible-row index.
     */
    private int maximumFirstRow() {

        return Math.max(
                0,
                snapshots.size() - visibleRowCount()
        );
    }

    /**
     * Render only complete rows.
     *
     * No scissor is used here, which avoids
     * inconsistent clipping when the GUI pose
     * is transformed.
     */
    private void renderRows(
            GuiGraphics graphics,
            double mouseX,
            double mouseY
    ) {

        if (snapshots.isEmpty()) {

            graphics.drawCenteredString(
                    font,
                    "No snapshots available for this world.",
                    DESIGN_WIDTH / 2,
                    listTop() + listHeight() / 2,
                    COLOR_MUTED
            );

            return;
        }

        int visibleCount = visibleRowCount();

        int endIndex = Math.min(
                snapshots.size(),
                firstVisibleRow + visibleCount
        );

        for (int index = firstVisibleRow;
             index < endIndex;
             index++) {

            int visibleIndex =
                    index - firstVisibleRow;

            int y =
                    listTop()
                            + visibleIndex * ROW_HEIGHT;

            SnapshotGuiPackets.SnapshotEntry entry =
                    snapshots.get(index);

            renderRow(
                    graphics,
                    entry,
                    index,
                    y,
                    mouseX,
                    mouseY
            );
        }

        renderScrollbar(graphics);
    }

    private void renderRow(
            GuiGraphics graphics,
            SnapshotGuiPackets.SnapshotEntry entry,
            int index,
            int y,
            double mouseX,
            double mouseY
    ) {

        boolean hovered =
                inside(
                        mouseX,
                        mouseY,
                        PADDING + 4,
                        y,
                        DESIGN_WIDTH - PADDING - 4,
                        y + ROW_HEIGHT
                );

        int background =
                hovered
                        ? COLOR_ROW_HOVER
                        : index % 2 == 0
                        ? COLOR_ROW
                        : COLOR_ROW_ALT;

        graphics.fill(
                PADDING + 4,
                y,
                DESIGN_WIDTH - PADDING - 4,
                y + ROW_HEIGHT - 2,
                background
        );

        String date =
                formatTimestamp(
                        entry.createdAtEpochMillis()
                );

        String age =
                formatAge(
                        entry.createdAtEpochMillis()
                );

        int textY = y + 9;

        graphics.drawString(
                font,
                date,
                DATE_X,
                textY,
                COLOR_TEXT,
                false
        );

        graphics.drawString(
                font,
                age,
                AGE_X,
                textY,
                COLOR_MUTED,
                false
        );

        graphics.drawString(
                font,
                entry.reason(),
                REASON_X,
                textY,
                COLOR_MUTED,
                false
        );

        boolean confirmingRestore =
                isConfirming(
                        entry.id(),
                        SnapshotGuiPackets.SnapshotAction.Action.RESTORE
                );

        boolean confirmingDelete =
                isConfirming(
                        entry.id(),
                        SnapshotGuiPackets.SnapshotAction.Action.DELETE
                );

        int buttonY = y + 3;

        drawButton(
                graphics,
                RESTORE_X,
                buttonY,
                RESTORE_WIDTH,
                BUTTON_HEIGHT,
                confirmingRestore
                        ? "Confirm"
                        : "Restore",
                confirmingRestore
                        ? COLOR_CONFIRM
                        : COLOR_RESTORE,
                confirmingRestore
                        ? COLOR_CONFIRM
                        : COLOR_RESTORE_HOVER,
                true,
                mouseX,
                mouseY
        );

        drawButton(
                graphics,
                CHUNKS_X,
                buttonY,
                CHUNKS_WIDTH,
                BUTTON_HEIGHT,
                "Chunks",
                COLOR_CHUNKS,
                COLOR_CHUNKS_HOVER,
                true,
                mouseX,
                mouseY
        );

        drawButton(
                graphics,
                LOCK_X,
                buttonY,
                LOCK_WIDTH,
                BUTTON_HEIGHT,
                entry.locked()
                        ? "Locked"
                        : "Unlocked",
                entry.locked()
                        ? COLOR_LOCKED
                        : COLOR_UNLOCKED,
                entry.locked()
                        ? COLOR_LOCKED_HOVER
                        : COLOR_UNLOCKED_HOVER,
                true,
                mouseX,
                mouseY
        );

        drawButton(
                graphics,
                DELETE_X,
                buttonY,
                DELETE_WIDTH,
                BUTTON_HEIGHT,
                confirmingDelete
                        ? "Confirm"
                        : "Delete",
                confirmingDelete
                        ? COLOR_CONFIRM
                        : COLOR_DELETE,
                confirmingDelete
                        ? COLOR_CONFIRM
                        : COLOR_DELETE_HOVER,
                !entry.locked(),
                mouseX,
                mouseY
        );
    }

    private void drawButton(
            GuiGraphics graphics,
            int x,
            int y,
            int buttonWidth,
            int buttonHeight,
            String label,
            int normalColor,
            int hoverColor,
            boolean enabled,
            double mouseX,
            double mouseY
    ) {

        boolean hovered =
                inside(
                        mouseX,
                        mouseY,
                        x,
                        y,
                        x + buttonWidth,
                        y + buttonHeight
                );

        int color =
                !enabled
                        ? COLOR_DISABLED
                        : hovered
                        ? hoverColor
                        : normalColor;

        graphics.fill(
                x,
                y,
                x + buttonWidth,
                y + buttonHeight,
                color
        );

        graphics.drawCenteredString(
                font,
                label,
                x + buttonWidth / 2,
                y + 6,
                enabled
                        ? COLOR_TEXT
                        : COLOR_MUTED
        );
    }

    /**
     * Scrollbar using row-based scrolling.
     */
    private void renderScrollbar(
            GuiGraphics graphics
    ) {

        if (snapshots.size() <= visibleRowCount()) {
            return;
        }

        int scrollbarX =
                DESIGN_WIDTH - 13;

        graphics.fill(
                scrollbarX,
                listTop(),
                scrollbarX + 4,
                listBottom(),
                0xFF202833
        );

        int thumbHeight = Math.max(
                16,
                listHeight()
                        * visibleRowCount()
                        / snapshots.size()
        );

        int maxFirstRow =
                maximumFirstRow();

        int thumbOffset =
                maxFirstRow == 0
                        ? 0
                        : (int) (
                        (double) firstVisibleRow
                                / maxFirstRow
                                * (listHeight() - thumbHeight)
                );

        graphics.fill(
                scrollbarX,
                listTop() + thumbOffset,
                scrollbarX + 4,
                listTop() + thumbOffset + thumbHeight,
                0xFF8295AA
        );
    }

    private void renderFooter(
            GuiGraphics graphics,
            double mouseX,
            double mouseY
    ) {

        int separatorY =
                DESIGN_HEIGHT - 49;

        graphics.fill(
                PADDING,
                separatorY,
                DESIGN_WIDTH - PADDING,
                separatorY + 1,
                0xFF465260
        );

        if (!status.isEmpty()) {

            graphics.drawCenteredString(
                    font,
                    status,
                    DESIGN_WIDTH / 2,
                    separatorY - 15,
                    COLOR_MUTED
            );
        }

        int buttonY =
                DESIGN_HEIGHT - 36;

        drawButton(
                graphics,
                REFRESH_X,
                buttonY,
                FOOTER_BUTTON_WIDTH,
                FOOTER_BUTTON_HEIGHT,
                "Refresh",
                COLOR_FOOTER_BUTTON,
                COLOR_FOOTER_HOVER,
                true,
                mouseX,
                mouseY
        );

        drawButton(
                graphics,
                CLOSE_X,
                buttonY,
                FOOTER_BUTTON_WIDTH,
                FOOTER_BUTTON_HEIGHT,
                "Close",
                COLOR_FOOTER_BUTTON,
                COLOR_FOOTER_HOVER,
                true,
                mouseX,
                mouseY
        );
    }

    private static boolean inside(
            double mouseX,
            double mouseY,
            double left,
            double top,
            double right,
            double bottom
    ) {

        return mouseX >= left
                && mouseX < right
                && mouseY >= top
                && mouseY < bottom;
    }

    private double virtualMouseX(
            double mouseX
    ) {

        return (mouseX - originX) / scale;
    }

    private double virtualMouseY(
            double mouseY
    ) {

        return (mouseY - originY) / scale;
    }

    @Override
    public boolean mouseClicked(
            double mouseX,
            double mouseY,
            int button
    ) {

        if (button != 0) {

            return super.mouseClicked(
                    mouseX,
                    mouseY,
                    button
            );
        }

        recalculateLayout();

        double x =
                virtualMouseX(mouseX);

        double y =
                virtualMouseY(mouseY);

        /*
         * Footer buttons.
         */
        int footerY =
                DESIGN_HEIGHT - 36;

        if (inside(
                x,
                y,
                REFRESH_X,
                footerY,
                REFRESH_X + FOOTER_BUTTON_WIDTH,
                footerY + FOOTER_BUTTON_HEIGHT
        )) {

            refresh();

            return true;
        }

        if (inside(
                x,
                y,
                CLOSE_X,
                footerY,
                CLOSE_X + FOOTER_BUTTON_WIDTH,
                footerY + FOOTER_BUTTON_HEIGHT
        )) {

            onClose();

            return true;
        }

        /*
         * Ignore clicks outside the list.
         */
        if (y < listTop()
                || y >= listBottom()) {

            return super.mouseClicked(
                    mouseX,
                    mouseY,
                    button
            );
        }

        /*
         * Which complete visible row was clicked?
         */
        int visibleIndex =
                (int) (
                        (y - listTop())
                                / ROW_HEIGHT
                );

        int index =
                firstVisibleRow + visibleIndex;

        if (visibleIndex < 0
                || visibleIndex >= visibleRowCount()
                || index >= snapshots.size()) {

            return true;
        }

        SnapshotGuiPackets.SnapshotEntry entry =
                snapshots.get(index);

        int rowY =
                listTop()
                        + visibleIndex * ROW_HEIGHT;

        int buttonY =
                rowY + 3;

        /*
         * Restore.
         */
        if (inside(
                x,
                y,
                RESTORE_X,
                buttonY,
                RESTORE_X + RESTORE_WIDTH,
                buttonY + BUTTON_HEIGHT
        )) {

            requestAction(
                    entry,
                    SnapshotGuiPackets.SnapshotAction.Action.RESTORE
            );

            return true;
        }

        /*
         * Open the interactive chunk map for
         * the selected snapshot.
         */
        if (inside(
                x,
                y,
                CHUNKS_X,
                buttonY,
                CHUNKS_X + CHUNKS_WIDTH,
                buttonY + BUTTON_HEIGHT
        )) {

            if (minecraft == null || minecraft.player == null) {
                return true;
            }

            int centerX = minecraft.player.chunkPosition().x;
            int centerZ = minecraft.player.chunkPosition().z;

            status = "Loading chunk map...";

            ClientPlayNetworking.send(
                    new ChunkMapPackets.Request(
                            entry.id(),
                            centerX,
                            centerZ,
                            1,
                            true
                    )
            );

            return true;
        }

        /*
         * Lock / unlock.
         */
        if (inside(
                x,
                y,
                LOCK_X,
                buttonY,
                LOCK_X + LOCK_WIDTH,
                buttonY + BUTTON_HEIGHT
        )) {

            toggleLock(entry);

            return true;
        }

        /*
         * Delete.
         */
        if (inside(
                x,
                y,
                DELETE_X,
                buttonY,
                DELETE_X + DELETE_WIDTH,
                buttonY + BUTTON_HEIGHT
        )) {

            if (entry.locked()) {

                status =
                        "Unlock this snapshot before deleting.";

            } else {

                requestAction(
                        entry,
                        SnapshotGuiPackets.SnapshotAction.Action.DELETE
                );
            }

            return true;
        }

        confirmationId = null;
        confirmationAction = null;

        return true;
    }

    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double scrollX,
            double scrollY
    ) {

        recalculateLayout();

        double x =
                virtualMouseX(mouseX);

        double y =
                virtualMouseY(mouseY);

        if (!inside(
                x,
                y,
                PADDING,
                listTop(),
                DESIGN_WIDTH - PADDING,
                listBottom()
        )) {

            return super.mouseScrolled(
                    mouseX,
                    mouseY,
                    scrollX,
                    scrollY
            );
        }

        /*
         * Move by complete rows.
         *
         * This avoids partially clipping text
         * or action buttons.
         */
        int rows =
                Math.max(
                        1,
                        (int) Math.ceil(
                                Math.abs(scrollY)
                        )
                );

        if (scrollY > 0) {

            firstVisibleRow -= rows;

        } else if (scrollY < 0) {

            firstVisibleRow += rows;
        }

        firstVisibleRow = Math.max(
                0,
                Math.min(
                        firstVisibleRow,
                        maximumFirstRow()
                )
        );

        return true;
    }

    private void toggleLock(
            SnapshotGuiPackets.SnapshotEntry entry
    ) {

        confirmationId = null;
        confirmationAction = null;

        status = entry.locked()
                ? "Unlocking snapshot..."
                : "Locking snapshot...";

        ClientPlayNetworking.send(
                new SnapshotGuiPackets.SnapshotAction(
                        SnapshotGuiPackets.SnapshotAction.Action.TOGGLE_LOCK,
                        entry.id()
                )
        );
    }

    private void requestAction(
            SnapshotGuiPackets.SnapshotEntry entry,
            SnapshotGuiPackets.SnapshotAction.Action action
    ) {

        if (!isConfirming(
                entry.id(),
                action
        )) {

            confirmationId = entry.id();
            confirmationAction = action;

            status =
                    action
                            == SnapshotGuiPackets.SnapshotAction.Action.RESTORE
                            ? "Click Confirm again to restore this world."
                            : "Click Confirm again to permanently delete.";

            return;
        }

        confirmationId = null;
        confirmationAction = null;

        ClientPlayNetworking.send(
                new SnapshotGuiPackets.SnapshotAction(
                        action,
                        entry.id()
                )
        );

        status =
                action
                        == SnapshotGuiPackets.SnapshotAction.Action.RESTORE
                        ? "World restoration requested..."
                        : "Deleting snapshot...";
    }

    private boolean isConfirming(
            String id,
            SnapshotGuiPackets.SnapshotAction.Action action
    ) {

        return id.equals(confirmationId)
                && action == confirmationAction;
    }

    private void refresh() {

        confirmationId = null;
        confirmationAction = null;

        status = "Refreshing snapshots...";

        ClientPlayNetworking.send(
                new SnapshotGuiPackets.SnapshotAction(
                        SnapshotGuiPackets.SnapshotAction.Action.REFRESH,
                        ""
                )
        );
    }

    private static String formatTimestamp(
            long timestamp
    ) {

        if (timestamp <= 0) {
            return "Unknown";
        }

        return TIME_FORMAT.format(
                Instant.ofEpochMilli(timestamp)
                        .atZone(ZoneId.systemDefault())
        );
    }

    private static String formatAge(
            long timestamp
    ) {

        if (timestamp <= 0) {
            return "Unknown";
        }

        LocalDate snapshotDate =
                Instant.ofEpochMilli(timestamp)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate();

        LocalDate today =
                LocalDate.now();

        if (!snapshotDate.isBefore(today)) {
            return "Today";
        }

        long days =
                ChronoUnit.DAYS.between(
                        snapshotDate,
                        today
                );

        if (days == 1) {
            return "Yesterday";
        }

        if (days < 30) {
            return days + " days ago";
        }

        long months =
                ChronoUnit.MONTHS.between(
                        snapshotDate,
                        today
                );

        if (months < 1) {
            return "1 month ago";
        }

        if (months < 12) {

            return months == 1
                    ? "1 month ago"
                    : months + " months ago";
        }

        long years =
                ChronoUnit.YEARS.between(
                        snapshotDate,
                        today
                );

        if (years < 1) {
            return "1 year ago";
        }

        return years == 1
                ? "1 year ago"
                : years + " years ago";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}