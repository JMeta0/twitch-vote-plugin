package com.czacha994.twitchvoting;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import java.io.File;

/**
 * Main plugin class for TwitchVoting, handles configuration and initialization.
 */
public class TwitchVotingPlugin extends JavaPlugin implements Listener {
    private VoteCommandExecutor voteExecutor;
    private VoteScoreboard voteScoreboard;
    private boolean useScoreboard = true; // Default value
    private boolean singleVoteMode = false; // Default value

    @Override
    public void onEnable() {
        // Create config.yml with defaults if it doesn't exist
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }

            File configFile = new File(getDataFolder(), "config.yml");
            if (!configFile.exists()) {
                saveDefaultConfig();
                getLogger().info("Created default config.yml");
            }
        } catch (Exception e) {
            getLogger().severe("Failed to create config file: " + e.getMessage());
        }

        // Load configuration
        reloadConfig();

        // Validate and apply defaults if needed
        validateConfig();

        // Load display mode from config
        useScoreboard = getConfig().getBoolean("display.use_scoreboard", true);

        // Load single vote mode from config
        singleVoteMode = getConfig().getBoolean("voting.single_vote_mode", false);

        // Initialize scoreboard manager
        this.voteScoreboard = new VoteScoreboard(this);

        // Register the /vote command
        PluginCommand voteCommand = this.getCommand("vote");
        if (voteCommand != null) {
            this.voteExecutor = new VoteCommandExecutor(this);
            voteCommand.setExecutor(this.voteExecutor);
        } else {
            getLogger().severe("Failed to register /vote command!");
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Register event listeners
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("TwitchVoting enabled.");
    }

    // Event handlers for player tracking
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up scoreboard when player disconnects
        if (voteScoreboard != null) {
            voteScoreboard.hideScoreboard(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        // Handle player changing worlds - if the vote is in a specific world
        if (voteExecutor != null && voteExecutor.isVoteActive()) {
            Player player = event.getPlayer();
            String voteWorld = voteExecutor.getVoteWorldName();

            // If the player is leaving the vote world, hide scoreboard
            if (voteWorld != null && event.getFrom().getName().equals(voteWorld)) {
                voteScoreboard.hideScoreboard(player);
            }
            // If the player is entering the vote world, show scoreboard
            else if (voteWorld != null && player.getWorld().getName().equals(voteWorld)) {
                voteExecutor.showScoreboardToPlayer(player);
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Show scoreboard to newly joined players if vote is active in their world
        if (voteExecutor != null && voteExecutor.isVoteActive()) {
            Player player = event.getPlayer();
            String voteWorld = voteExecutor.getVoteWorldName();

            if (voteWorld != null && player.getWorld().getName().equals(voteWorld)) {
                voteExecutor.showScoreboardToPlayer(player);
            }
        }
    }

    @Override
    public void onDisable() {
        // Shutdown logic: Stop any active vote and clean up resources
        if (this.voteExecutor != null) {
            this.voteExecutor.shutdown();
        }

        // Hide all scoreboards
        if (this.voteScoreboard != null) {
            this.voteScoreboard.hideAllScoreboards();
        }

        getLogger().info("TwitchVoting disabled.");
    }

    /**
     * @return The scoreboard manager for this plugin
     */
    public VoteScoreboard getVoteScoreboard() {
        return voteScoreboard;
    }

    /**
     * Get the current display mode preference
     * @return true if scoreboard mode should be used, false for chat mode
     */
    public boolean isUsingScoreboard() {
        return useScoreboard;
    }

    /**
     * Update the display mode setting and save to config
     * @param useScoreboard true for scoreboard mode, false for chat mode
     */
    public void setUsingScoreboard(boolean useScoreboard) {
        this.useScoreboard = useScoreboard;
        getConfig().set("display.use_scoreboard", useScoreboard);
        saveConfig();
        getLogger().info("Display mode set to: " + (useScoreboard ? "Scoreboard" : "Chat"));
    }

    /**
     * Get the single vote mode setting
     * @return true if only counting the last vote from each user, false if counting all votes
     */
    public boolean isSingleVoteMode() {
        return singleVoteMode;
    }

    /**
     * Update the single vote mode setting and save to config
     * @param singleVoteMode true to count only the last vote per user, false to count all votes
     */
    public void setSingleVoteMode(boolean singleVoteMode) {
        this.singleVoteMode = singleVoteMode;
        getConfig().set("voting.single_vote_mode", singleVoteMode);
        saveConfig();
        getLogger().info("Single vote mode set to: " + (singleVoteMode ? "Enabled" : "Disabled"));
    }

    /**
     * Checks if a command sender is a command block with permissions
     * @param sender The command sender to check
     * @return true if the sender is a command block with voting.commandblock permission
     */
    public boolean isCommandBlockWithPermission(Object sender) {
        return (sender instanceof BlockCommandSender) &&
               ((BlockCommandSender)sender).hasPermission("voting.commandblock");
    }

    /**
     * Validates the configuration file and adds any missing default values
     */
    private void validateConfig() {
        // Check and set defaults for any missing values
        if (!getConfig().isSet("display.use_scoreboard")) {
            getConfig().set("display.use_scoreboard", true);
        }

        if (!getConfig().isSet("display.results_display_time")) {
            getConfig().set("display.results_display_time", 60);
        }

        if (!getConfig().isSet("defaults.max_options")) {
            getConfig().set("defaults.max_options", 20);
        }

        if (!getConfig().isSet("defaults.min_duration")) {
            getConfig().set("defaults.min_duration", 5);
        }

        if (!getConfig().isSet("defaults.max_duration")) {
            getConfig().set("defaults.max_duration", 3600);
        }

        if (!getConfig().isSet("voting.single_vote_mode")) {
            getConfig().set("voting.single_vote_mode", false);
        }

        // Save any changes made
        saveConfig();
    }

    /**
     * Reload the plugin configuration and update current settings
     */
    @Override
    public void reloadConfig() {
        super.reloadConfig();

        // Validate and fix the config after reload
        validateConfig();

        // Update current settings from reloaded config
        useScoreboard = getConfig().getBoolean("display.use_scoreboard", true);
        singleVoteMode = getConfig().getBoolean("voting.single_vote_mode", false);

        getLogger().info("Configuration reloaded.");
    }
}