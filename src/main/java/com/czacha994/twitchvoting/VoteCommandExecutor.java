package com.czacha994.twitchvoting;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Handles the /vote command and manages the active Twitch vote session.
 */
public class VoteCommandExecutor implements CommandExecutor {
    private final TwitchVotingPlugin plugin;
    private TwitchVoteSession currentSession = null;
    private BukkitTask stopTask = null;
    private BukkitTask updateTask = null;
    private UUID voteStarterUuid = null;
    private String voteWorldName = null;
    private List<String> voteOptions = null;
    private int totalSeconds = 0;
    private int remainingSeconds = 0;
    private BukkitTask countdownTask = null;

    // Track result display tasks separately
    private List<BukkitTask> resultDisplayTasks = new ArrayList<>();
    private boolean showingResults = false;

    /**
     * Creates a new vote command executor.
     *
     * @param plugin The plugin instance
     */
    public VoteCommandExecutor(TwitchVotingPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§cUsage: /vote <start|stop|help>");
            return true;
        }

        boolean isCommandBlock = plugin.isCommandBlockWithPermission(sender);

        switch (args[0].toLowerCase()) {
            case "start":
                return handleStartCommand(sender, args, isCommandBlock);
            case "stop":
                return handleStopCommand(sender, isCommandBlock);
            case "reload":
                return handleReloadCommand(sender);
            case "togglemode":
                return handleToggleModeCommand(sender);
            case "togglevote":
                return handleToggleVoteCommand(sender);
            case "help":
                showHelpMessage(sender);
                return true;
            default:
                sender.sendMessage("§cUnknown subcommand. Use /vote help");
                return true;
        }
    }

    /**
     * Handles the /vote start command.
     */
    private boolean handleStartCommand(CommandSender sender, String[] args, boolean isCommandBlock) {
        if (!sender.hasPermission("voting.manage") && !isCommandBlock) {
            sender.sendMessage("§cYou do not have permission to start a vote.");
            return true;
        }

        // Get world context based on sender type
        String worldName = null;
        UUID starterUuid = null;

        if (sender instanceof Player) {
            Player player = (Player) sender;
            worldName = player.getWorld().getName();
            starterUuid = player.getUniqueId();
        } else if (isCommandBlock) {
            // For command blocks, use the world containing the command block
            BlockCommandSender blockSender = (BlockCommandSender) sender;
            worldName = blockSender.getBlock().getWorld().getName();
        } else {
            sender.sendMessage("§cOnly players or command blocks can start a vote.");
            return true;
        }

        if (currentSession != null) {
            sender.sendMessage("§cA voting session is already running.");
            return true;
        }

        if (args.length < 4) {
            sender.sendMessage("§cUsage: /vote start <seconds> <streamer> <option1> <option2> ...");
            return true;
        }

        int seconds;
        try {
            seconds = Integer.parseInt(args[1]);
            if (seconds < 5 || seconds > 3600) {
                sender.sendMessage("§cVoting duration must be between 5 and 3600 seconds.");
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid number for seconds: " + args[1]);
            return true;
        }

        String streamer = args[2];
        List<String> options = Arrays.asList(Arrays.copyOfRange(args, 3, args.length));

        if (options.size() < 1) {
            sender.sendMessage("§cYou must provide at least one voting option.");
            return true;
        }

        if (options.size() > 20) {
            sender.sendMessage("§cMaximum 20 voting options allowed.");
            return true;
        }

        // If showing results from a previous vote, clean those up first
        if (showingResults) {
            cleanupResultTasks();
            // Also make sure scoreboard results are removed
            plugin.getVoteScoreboard().hideAllScoreboards();
        }

        // Ensure any previous timers are fully cancelled
        cleanupTasks();

        // Store vote session data
        this.voteStarterUuid = starterUuid;  // May be null for command blocks
        this.voteWorldName = worldName;
        this.voteOptions = options;
        this.totalSeconds = seconds;
        this.remainingSeconds = seconds;

        // Connect to Twitch asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TwitchVoteSession session = new TwitchVoteSession(plugin, streamer, options.size());
            session.start();
            this.currentSession = session;

            // Back to main thread to schedule tasks and send messages
            Bukkit.getScheduler().runTask(plugin, () -> {
                sender.sendMessage("§aVoting started for " + seconds + " seconds in Twitch channel: " + streamer);

                sendMessageToWorld("§eA vote has started! Use Twitch chat to vote.");
                sendMessageToWorld("§eVote in Twitch chat: twitch.tv/" + streamer);

                // Inform about current vote mode
                String voteMode = plugin.isSingleVoteMode() ? "last vote only" : "multiple votes";
                sendMessageToWorld("§eVote mode: §6" + voteMode);

                // Start timer for countdown on main thread
                startCountdownTimer();

                // Setup display based on current mode
                if (plugin.isUsingScoreboard()) {
                    // Get players in world and show scoreboard
                    List<Player> worldPlayers = getPlayersInWorld(getWorld());
                    plugin.getVoteScoreboard().showVoting(voteOptions, currentSession, worldPlayers, seconds);
                } else {
                    // If chat mode, start updates
                    startChatUpdates();
                }

                // Set up automatic vote ending
                if (stopTask != null) {
                    stopTask.cancel();
                }
                stopTask = Bukkit.getScheduler().runTaskLater(plugin, this::stopVote, seconds * 20L);
            });
        });

        return true;
    }

    /**
     * Cleans up all tasks associated with vote timing
     */
    private void cleanupTasks() {
        // Cancel any existing countdown task
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }

        // Cancel any existing stop task
        if (stopTask != null) {
            stopTask.cancel();
            stopTask = null;
        }

        // Cancel any existing update task
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
    }

    /**
     * Cleans up result display tasks
     */
    private void cleanupResultTasks() {
        // Cancel any result display tasks
        for (BukkitTask task : resultDisplayTasks) {
            try {
                task.cancel();
            } catch (Exception e) {
                // Ignore errors
            }
        }
        resultDisplayTasks.clear();
        showingResults = false;
    }

    /**
     * Handles the /vote stop command.
     */
    private boolean handleStopCommand(CommandSender sender, boolean isCommandBlock) {
        if (!sender.hasPermission("voting.manage") && !isCommandBlock) {
            sender.sendMessage("§cYou do not have permission to stop a vote.");
            return true;
        }

        if (currentSession == null) {
            sender.sendMessage("§cNo voting session is currently running.");
            return true;
        }

        // First inform the user that we're stopping
        sender.sendMessage("§eStopping the vote...");

        // Run on main thread to ensure proper synchronization
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Stop the vote and clean up
            stopVote();
            // Notify the sender once complete
            sender.sendMessage("§aVoting session stopped.");
        });

        return true;
    }

    /**
     * Handles the /vote reload command.
     */
    private boolean handleReloadCommand(CommandSender sender) {
        if (!sender.hasPermission("voting.admin")) {
            sender.sendMessage("§cYou do not have permission to reload the configuration.");
            return true;
        }

        // Check if a vote is currently running
        boolean voteWasActive = false;
        int remainingTimeBackup = 0;

        if (currentSession != null) {
            voteWasActive = true;
            remainingTimeBackup = remainingSeconds;
            sender.sendMessage("§eWARNING: A vote is in progress. Stopping it before reload.");

            // Stop the vote - run synchronously since we're in the command thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                stopVote();
                sender.sendMessage("§eVote stopped for configuration reload.");
            });
        }

        // Reload the plugin's configuration
        plugin.reloadConfig();
        sender.sendMessage("§aConfiguration reloaded successfully.");

        // If a vote was active, inform the user it was stopped
        if (voteWasActive) {
            sender.sendMessage("§cThe active vote was stopped during config reload.");
            sender.sendMessage("§eIt had " + remainingTimeBackup + " seconds remaining.");
            sender.sendMessage("§eYou may start a new vote if needed.");
        }

        return true;
    }

    /**
     * Handles the /vote togglemode command.
     */
    private boolean handleToggleModeCommand(CommandSender sender) {
        if (!sender.hasPermission("voting.manage")) {
            sender.sendMessage("§cYou do not have permission to toggle display mode.");
            return true;
        }

        boolean newMode = !plugin.isUsingScoreboard();
        plugin.setUsingScoreboard(newMode);
        sender.sendMessage("§aDisplay mode set to: " + (newMode ? "Scoreboard" : "Chat"));

        // If there's an active vote, update the display
        if (currentSession != null) {
            // Refresh the display with the new mode
            if (newMode) {
                // Show scoreboard for current vote
                List<Player> worldPlayers = getPlayersInWorld(getWorld());
                plugin.getVoteScoreboard().showVoting(voteOptions, currentSession, worldPlayers, remainingSeconds);
            } else {
                // Hide scoreboards and show chat display instead
                plugin.getVoteScoreboard().hideAllScoreboards();
                sendVotingTable(false);
            }
        }

        return true;
    }

    /**
     * Handles the /vote togglevote command to switch between single and multiple vote modes.
     */
    private boolean handleToggleVoteCommand(CommandSender sender) {
        if (!sender.hasPermission("voting.manage")) {
            sender.sendMessage("§cYou do not have permission to toggle vote mode.");
            return true;
        }

        boolean newMode = !plugin.isSingleVoteMode();
        plugin.setSingleVoteMode(newMode);

        String modeDescription = newMode ?
            "Single vote mode (only last vote counts)" :
            "Multiple vote mode (all votes count)";

        sender.sendMessage("§aVote mode set to: " + modeDescription);

        // If there's an active vote, let users know about the mode change
        if (currentSession != null) {
            sendMessageToWorld("§eVote mode changed to: §6" + modeDescription);
        }

        return true;
    }

    /**
     * Displays the help message for the /vote command.
     */
    private void showHelpMessage(CommandSender sender) {
        sender.sendMessage("§e/vote start <seconds> <streamer> <option1> <option2> ...");
        sender.sendMessage("§e/vote stop");
        sender.sendMessage("§e/vote togglemode - Switch between scoreboard and chat display");
        sender.sendMessage("§e/vote togglevote - Switch between single vote and multiple votes mode");
        sender.sendMessage("§e/vote reload - Reload plugin configuration");
        sender.sendMessage("§e/vote help");
    }

    /**
     * Stops the current voting session and displays results.
     */
    private void stopVote() {
        if (currentSession != null) {
            // Create a local reference to prevent race conditions
            TwitchVoteSession sessionToStop = currentSession;

            // First mark session as stopping to prevent new votes
            synchronized (sessionToStop) {
                // Capture the final results before stopping the session
                final int[] finalResults = sessionToStop.getVoteCounts();
                final List<String> finalOptions = new ArrayList<>(voteOptions);
                final String finalWorldName = voteWorldName;

                // Disconnect from Twitch in async thread (we're already in async context)
                sessionToStop.stop();

                // Send results table and schedule cleanup on main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // Cancel all scheduled tasks
                    cleanupTasks();

                    // Clean up any previous result tasks just in case
                    cleanupResultTasks();

                    // Mark that we're showing results
                    showingResults = true;

                    // Display results based on current mode
                    if (plugin.isUsingScoreboard()) {
                        // Show scoreboard results
                        List<Player> worldPlayers = getPlayersInWorld(getWorld());
                        plugin.getVoteScoreboard().showResults(finalOptions, finalResults, worldPlayers);

                        // Schedule scoreboards to be hidden after configured display time
                        int displayTime = plugin.getConfig().getInt("display.results_display_time", 60);
                        BukkitTask hideTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            plugin.getVoteScoreboard().hideAllScoreboards();
                            sendMessageToWorld("§eThe vote has ended.");
                            showingResults = false;
                            resultDisplayTasks.clear();
                        }, displayTime * 20L);
                        resultDisplayTasks.add(hideTask);
                    } else {
                        // Use chat display with the captured results
                        displayChatResults(finalOptions, finalResults, finalWorldName);

                        // Remove table after configured display time
                        int displayTime = plugin.getConfig().getInt("display.results_display_time", 60);
                        BukkitTask hideTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            clearVotingTable();
                            sendMessageToWorld("§eThe vote has ended.");
                            showingResults = false;
                            resultDisplayTasks.clear();
                        }, displayTime * 20L);
                        resultDisplayTasks.add(hideTask);
                    }

                    // Delay the final notification to show it after results are displayed
                    // For scoreboard mode, show immediately, for chat mode delay by a bit
                    if (plugin.isUsingScoreboard()) {
                        sendMessageToWorld("§6§lThe vote has ended! Results are displayed.");
                    } else {
                        // For chat mode, delay to ensure results are shown first
                        // Delay is based on number of options (options + header + footer = options.size() + 5)
                        BukkitTask messageTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            sendMessageToWorld("§6§lThe vote has ended! Results are displayed.");
                        }, finalOptions.size() + 6);
                        resultDisplayTasks.add(messageTask);
                    }
                });
            }

            currentSession = null;
            voteStarterUuid = null;
            voteWorldName = null;
            voteOptions = null;
            remainingSeconds = 0; // Reset the timer to prevent lingering timer state
            totalSeconds = 0; // Also reset total seconds
        } else {
            // Handle case where tasks need to be cancelled but session is already null
            Bukkit.getScheduler().runTask(plugin, () -> {
                cleanupTasks();
            });
        }
    }

    /**
     * Displays the vote results in chat format.
     */
    private void displayChatResults(List<String> options, int[] counts, String worldName) {
        if (options == null || worldName == null) return;
        if (counts == null) {
            plugin.getLogger().warning("Vote counts array is null when displaying results");
            return;
        }

        // Ensure counts array is at least as long as options list
        if (counts.length < options.size()) {
            plugin.getLogger().warning("Vote counts array length mismatch: " + counts.length +
                                      " counts for " + options.size() + " options");
            // Create a new array with the correct length
            int[] safeCounts = new int[options.size()];
            // Copy existing values
            System.arraycopy(counts, 0, safeCounts, 0, counts.length);
            // Use the safe array instead
            counts = safeCounts;
        }

        // Create final copies of parameters to use in lambda
        final List<String> optionsFinal = new ArrayList<>(options);
        final int[] countsFinal = counts.clone();
        final String worldNameFinal = worldName;

        // Run on main thread to ensure proper message delivery
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Clear any existing display first
            clearVotingTable();

            // Find highest vote count
            int maxVotes = 0;
            for (int count : countsFinal) {
                if (count > maxVotes) {
                    maxVotes = count;
                }
            }

            final int highestVote = maxVotes;

            // Collect all messages first
            List<String> messages = new ArrayList<>();

            // Header messages
            messages.add("§6§l==== VOTE RESULTS ====");
            messages.add("§e#  Option    Votes");
            messages.add(" ");  // Add empty line for better visibility

            // Option results with winner highlighted
            for (int i = 0; i < optionsFinal.size(); i++) {
                String line;
                if (countsFinal[i] == highestVote && highestVote > 0) {
                    // Highlight winning option(s) in purple with gold vote count
                    line = "§d§l" + (i + 1) + ". §d§l" + optionsFinal.get(i) + "    §6" + countsFinal[i];
                } else {
                    line = "§b" + (i + 1) + ". §f" + optionsFinal.get(i) + "    §a" + countsFinal[i];
                }
                messages.add(line);
            }

            // Add footer
            messages.add(" ");
            messages.add("§6§l===================");

            // Send messages with a small delay to ensure correct order
            World world = Bukkit.getWorld(worldNameFinal);
            if (world != null) {
                for (int i = 0; i < messages.size(); i++) {
                    final int index = i;
                    // Small delay between messages (1 tick per message)
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        forEachPlayerInWorld(world, player -> {
                            player.sendMessage(messages.get(index));
                        });
                    }, i);
                }
            }
        });
    }

    /**
     * Sends the current voting table as chat messages.
     */
    private void sendVotingTable(boolean showResults) {
        if (voteOptions == null || voteWorldName == null || plugin.isUsingScoreboard()) return;

        // When used for real-time updates (not final results)
        if (!showResults) {
            // Get vote counts
            final int[] counts;
            if (currentSession != null) {
                counts = currentSession.getVoteCounts();
            } else {
                counts = new int[voteOptions.size()];
            }

            // Calculate highest vote
            int maxVotes = 0;
            for (int c : counts) if (c > maxVotes) maxVotes = c;
            final int highestVote = maxVotes;

            // Format time remaining for header
            final String timeDisplay;
            if (remainingSeconds > 60) {
                int minutes = remainingSeconds / 60;
                int seconds = remainingSeconds % 60;
                timeDisplay = minutes + "m " + seconds + "s";
            } else {
                timeDisplay = remainingSeconds + "s";
            }

            // Collect messages first
            final ConcurrentHashMap<Integer, String> messages = new ConcurrentHashMap<>();
            messages.put(0, "§6§lVote Now! §e(" + timeDisplay + " left)");
            messages.put(1, "§e#  Option    Votes");

            for (int i = 0; i < voteOptions.size(); i++) {
                // During voting, don't highlight winning options - display all options in same format
                String line = "§b" + (i + 1) + ". §f" + voteOptions.get(i) + "    §a" + counts[i];
                messages.put(i + 2, line);
            }

            // Send all messages on the main thread to avoid ConcurrentModificationException
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (int i = 0; i < messages.size(); i++) {
                    sendMessageToWorld(messages.get(i));
                }
            });
        }
        // Final results are now handled by displayChatResults method
    }

    /**
     * Clears the voting table from chat by sending empty lines.
     */
    private void clearVotingTable() {
        // Clear previous messages visually by sending multiple empty messages with a delay
        if (voteWorldName != null) {
            World world = getWorld();
            if (world == null) return;

            // Store final reference for use in lambda
            final World worldFinal = world;

            // Send multiple empty lines with small delays to ensure proper visual separation
            for (int i = 0; i < 5; i++) {
                final int index = i;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    forEachPlayerInWorld(worldFinal, player -> {
                        player.sendMessage(" ");
                    });
                }, index);
            }
        }
    }

    /**
     * Sends a message to all players in the vote's world.
     */
    private void sendMessageToWorld(String message) {
        if (voteWorldName == null) return;

        World world = getWorld();
        if (world == null) return;

        // Collect players first, then send messages
        forEachPlayerInWorld(world, player -> {
            player.sendMessage(message);
        });
    }

    /**
     * Gets the world where the vote is taking place.
     */
    private World getWorld() {
        return voteWorldName != null ? Bukkit.getWorld(voteWorldName) : null;
    }

    /**
     * Gets all players in the specified world.
     */
    private List<Player> getPlayersInWorld(World world) {
        List<Player> players = new ArrayList<>();
        if (world == null) return players;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(world)) {
                players.add(player);
            }
        }
        return players;
    }

    /**
     * Helper method to process players in a world with proper thread safety.
     */
    private void forEachPlayerInWorld(World world, Consumer<Player> action) {
        if (world == null) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(world)) {
                action.accept(player);
            }
        }
    }

    /**
     * Shuts down the vote executor and cleans up resources.
     * Called when the plugin is disabled.
     */
    public void shutdown() {
        plugin.getLogger().info("Shutting down vote executor...");

        // Clean up result display tasks
        cleanupResultTasks();

        // Hide all scoreboards if active
        plugin.getVoteScoreboard().hideAllScoreboards();

        // During shutdown, we need to clean up synchronously instead of using async tasks
        // which can fail during server shutdown
        if (currentSession != null) {
            try {
                // Stop Twitch session directly
                currentSession.stop();
                currentSession = null;

                // Clean up all tasks
                cleanupTasks();

                // Clean up references
                voteStarterUuid = null;
                voteWorldName = null;
                voteOptions = null;
                totalSeconds = 0;
                remainingSeconds = 0;

                plugin.getLogger().info("Vote session stopped during shutdown.");
            } catch (Exception e) {
                plugin.getLogger().warning("Error stopping vote session during shutdown: " + e.getMessage());
            }
        }
    }

    /**
     * Starts the countdown timer for the vote duration.
     */
    private void startCountdownTimer() {
        plugin.getLogger().info("Starting countdown timer for " + totalSeconds + " seconds");

        // Always cancel any existing countdown task first
        if (countdownTask != null) {
            try {
                countdownTask.cancel();
            } catch (Exception e) {
                plugin.getLogger().warning("Error cancelling countdown task: " + e.getMessage());
            }
            countdownTask = null;
        }

        // Ensure a fresh timer by explicitly resetting
        remainingSeconds = totalSeconds;

        // Create the runnable first
        Runnable countdownRunnable = new Runnable() {
            @Override
            public void run() {
                // First check if we still have a valid session
                if (currentSession == null) {
                    // Vote has been stopped - cancel the timer
                    if (countdownTask != null) {
                        countdownTask.cancel();
                        countdownTask = null;
                    }
                    return;
                }

                remainingSeconds--;

                // Debug log to track time
                if (remainingSeconds % 10 == 0 || remainingSeconds <= 5) {
                    plugin.getLogger().info("Vote countdown: " + remainingSeconds + " seconds remaining");
                }

                // Update scoreboards with new time if using scoreboard mode
                if (plugin.isUsingScoreboard() && currentSession != null) {
                    List<Player> worldPlayers = getPlayersInWorld(getWorld());
                    for (Player p : worldPlayers) {
                        plugin.getVoteScoreboard().updateRemainingTime(p, remainingSeconds);
                    }
                }

                if (remainingSeconds <= 0) {
                    plugin.getLogger().info("Vote countdown reached zero - ending vote");

                    // Cancel task
                    if (countdownTask != null) {
                        countdownTask.cancel();
                        countdownTask = null;
                    }

                    // Auto end the vote if we reach zero
                    if (currentSession != null) {
                        // Run stopVote on the main thread
                        Bukkit.getScheduler().runTask(plugin, () -> stopVote());
                    }
                }
            }
        };

        // Then schedule the task and store the reference
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, countdownRunnable, 20L, 20L); // Run every second
    }

    /**
     * Starts the periodic updates for chat display mode.
     */
    private void startChatUpdates() {
        if (updateTask != null) {
            updateTask.cancel();
        }

        // Display initial table
        sendVotingTable(false);

        // Schedule regular updates
        updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (currentSession != null && remainingSeconds > 5) {  // Stop updates when 5 seconds or less remain
                sendVotingTable(false);
            } else {
                // If session becomes null while task is running, cancel it
                if (updateTask != null) {
                    updateTask.cancel();
                }
            }
        }, 100L, 100L); // Update every 5 seconds (100 ticks)
    }

    /**
     * Checks if a vote is currently active.
     *
     * @return true if a vote is running, false otherwise
     */
    public boolean isVoteActive() {
        return currentSession != null;
    }

    /**
     * Gets the name of the world where the vote is taking place.
     *
     * @return The world name or null if no vote is active
     */
    public String getVoteWorldName() {
        return voteWorldName;
    }

    /**
     * Shows the current vote scoreboard to a specific player.
     * Used when players join or change worlds during an active vote.
     *
     * @param player The player to show the scoreboard to
     */
    public void showScoreboardToPlayer(Player player) {
        if (currentSession == null || voteOptions == null || !plugin.isUsingScoreboard()) {
            return;
        }

        // Create a list with just this player
        List<Player> singlePlayer = new ArrayList<>();
        singlePlayer.add(player);

        // Show the scoreboard with current state
        plugin.getVoteScoreboard().showVoting(voteOptions, currentSession, singlePlayer, remainingSeconds);
    }
}