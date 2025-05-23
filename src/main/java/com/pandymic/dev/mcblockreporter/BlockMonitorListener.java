package com.pandymic.dev.mcblockreporter;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;

public class BlockMonitorListener implements Listener {

    private final McBlockReporterPlugin plugin;

    public BlockMonitorListener(McBlockReporterPlugin plugin) {
        this.plugin = plugin;
    }

    // Helper to reduce redundancy
    private void processBlockChange(Block block) {
        if (block == null) return;
        Location loc = block.getLocation(); // Get location once

        // Optimization: Check if the block is monitored before scheduling a task.
        // This is especially useful for high-frequency events like BlockPhysicsEvent.
        if (!plugin.isBlockMonitored(loc)) { // Use the new helper method
            return;
        }

        // Check if an update for this location is already on cooldown (i.e., scheduled this tick)
        if (plugin.isLocationOnUpdateCooldown(loc)) {
            return;
        }
        plugin.addLocationToUpdateCooldown(loc); // Add to cooldown before scheduling

        // Schedule the task to run on the next server tick
        // This can help avoid issues with getting block state during an event
        // and ensures operations are main-thread safe if they involve Bukkit API for world modification (though we are just reading)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            plugin.handleMonitoredBlockUpdate(block);
            // Schedule removal from cooldown for the next tick.
            // This ensures that any other events in the *current* tick for this same block
            // won't trigger another update, but it will be eligible again next tick.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.removeLocationFromUpdateCooldown(loc), 1L);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        processBlockChange(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        processBlockChange(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        // BlockPhysicsEvent can be very frequent.
        // The check plugin.getMonitoredLocations().contains(event.getBlock().getLocation())
        // is done within handleMonitoredBlockUpdate.
        processBlockChange(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        // Handles events like falling sand/gravel, endermen placing/taking blocks
        processBlockChange(event.getBlock());
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) { // e.g. ice melting, fire burning out
        processBlockChange(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) { // e.g. crops, trees
        processBlockChange(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) { // e.g. concrete solidifying, snow forming
        processBlockChange(event.getBlock());
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) { // e.g. fire, vine, mushroom spread
        processBlockChange(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        processBlockChange(event.getBlock());
    }

    // Piston events are more complex as they move blocks.
    // The BlockPhysicsEvent on the affected locations should generally cover changes.
    // If more direct piston tracking is needed, you'd handle these:
    /*
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        // The block the piston itself occupies
        processBlockChange(event.getBlock());
        // Blocks moved by the piston
        for (Block movedBlock : event.getBlocks()) {
            // The original location of the moved block will change (e.g. to AIR or piston arm)
            processBlockChange(movedBlock); 
            // The new location where the block moved TO will also change
            // This requires calculating the destination: movedBlock.getRelative(event.getDirection())
            processBlockChange(movedBlock.getRelative(event.getDirection()));
        }
        // The block that was previously in front of the piston head
        processBlockChange(event.getBlock().getRelative(event.getDirection()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        processBlockChange(event.getBlock());
        for (Block movedBlock : event.getBlocks()) {
            // Original location of the block that was pulled
            processBlockChange(movedBlock);
            // New location of the pulled block (now occupied by it)
            processBlockChange(movedBlock.getRelative(event.getDirection().getOppositeFace()));
        }
    }
    */
}
