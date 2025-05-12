package com.czacha994.twitchvoting;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the display of voting options and results as in-game scoreboards.
 */
public class VoteScoreboard {
    private final TwitchVotingPlugin plugin;
    private final String objectiveName = "twitchvote";
    private final Map<UUID, Scoreboard> playerBoards = new HashMap<>();
    private final Map<UUID, Integer> playerUpdateTasks = new HashMap<>();
    private final Map<UUID, Integer> remainingTimeMap = new ConcurrentHashMap<>();
    private final AtomicBoolean votingEnded = new AtomicBoolean(false);

    /**
     * Creates a new vote scoreboard manager.
     *
     * @param plugin The plugin instance
     */
    public VoteScoreboard(TwitchVotingPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Shows the voting scoreboard to the specified players.
     *
     * @param options The voting options to display
     * @param session The active vote session
     * @param players The players to show the scoreboard to
     * @param seconds The initial duration in seconds
     */
    public void showVoting(List<String> options, TwitchVoteSession session, List<Player> players, int seconds) {
        // Reset state for new vote
        votingEnded.set(false);

        // Initialize timers for all players
        for (Player player : players) {
            remainingTimeMap.put(player.getUniqueId(), seconds);
            createScoreboard(player, options, session, false);
        }
    }

    /**
     * Shows the voting results scoreboard to the specified players.
     *
     * @param options The voting options
     * @param results The vote counts for each option
     * @param players The players to show the scoreboard to
     */
    public void showResults(List<String> options, int[] results, List<Player> players) {
        // Mark that voting has ended to show winner highlighting
        votingEnded.set(true);

        int maxVotes = 0;
        for (int count : results) {
            if (count > maxVotes) {
                maxVotes = count;
            }
        }

        final int highestVote = maxVotes;

        for (Player player : players) {
            createResultScoreboard(player, options, results, highestVote);
        }
    }

    /**
     * Updates the remaining time display for a player's scoreboard.
     *
     * @param player The player whose scoreboard should be updated
     * @param seconds The remaining seconds to display
     */
    public void updateRemainingTime(Player player, int seconds) {
        remainingTimeMap.put(player.getUniqueId(), seconds);

        // Update the scoreboard with new time if we have active board
        UUID playerId = player.getUniqueId();
        if (playerBoards.containsKey(playerId)) {
            Scoreboard board = playerBoards.get(playerId);
            Objective objective = board.getObjective(objectiveName);
            if (objective != null) {
                // Update time display
                updateTimeDisplay(board, objective, seconds);
            }
        }
    }

    /**
     * Updates the time display on a scoreboard.
     */
    private void updateTimeDisplay(Scoreboard board, Objective objective, int seconds) {
        // Clear existing time display
        for (String entry : board.getEntries()) {
            if (entry.contains("Time remaining:")) {
                board.resetScores(entry);
            }
        }

        // Format time display nicely
        String timeText;
        if (seconds > 60) {
            int minutes = seconds / 60;
            int remainingSecs = seconds % 60;
            timeText = ChatColor.YELLOW + "Time remaining: " +
                      ChatColor.WHITE + minutes + "m " + remainingSecs + "s";
        } else {
            timeText = ChatColor.YELLOW + "Time remaining: " +
                      (seconds <= 10 ? ChatColor.RED : ChatColor.WHITE) + seconds + "s";
        }

        // Set the new time
        Score timeScore = objective.getScore(timeText);
        timeScore.setScore(1000); // High score to put at top
    }

    /**
     * Creates and displays a scoreboard for a player.
     */
    private void createScoreboard(Player player, List<String> options, TwitchVoteSession session, boolean isResult) {
        UUID playerId = player.getUniqueId();

        // Cancel existing update task if present
        if (playerUpdateTasks.containsKey(playerId)) {
            Bukkit.getScheduler().cancelTask(playerUpdateTasks.get(playerId));
            playerUpdateTasks.remove(playerId);
        }

        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;

        Scoreboard board = manager.getNewScoreboard();
        Objective objective = board.registerNewObjective(objectiveName, "dummy",
                ChatColor.GOLD + "" + ChatColor.BOLD + "TWITCH VOTE");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        playerBoards.put(playerId, board);

        // Get current time for this player
        int timeRemaining = remainingTimeMap.getOrDefault(playerId, 0);
        updateTimeDisplay(board, objective, timeRemaining);

        // Schedule updates for live voting
        if (!isResult && session != null) {
            int taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                updateScoreboard(player, options, session.getVoteCounts());
            }, 20L, 20L).getTaskId(); // Update every second
            playerUpdateTasks.put(playerId, taskId);
        }

        // Initial display
        if (session != null) {
            updateScoreboard(player, options, session.getVoteCounts());
        } else {
            // Just set empty scores for first display
            setScores(board, objective, options, new int[options.size()], 0);
        }

        player.setScoreboard(board);
    }

    /**
     * Creates and displays a result scoreboard for a player.
     */
    private void createResultScoreboard(Player player, List<String> options, int[] results, int highestVote) {
        UUID playerId = player.getUniqueId();

        // Cancel existing update task if present
        if (playerUpdateTasks.containsKey(playerId)) {
            Bukkit.getScheduler().cancelTask(playerUpdateTasks.get(playerId));
            playerUpdateTasks.remove(playerId);
        }

        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;

        Scoreboard board = manager.getNewScoreboard();
        Objective objective = board.registerNewObjective(objectiveName, "dummy",
                ChatColor.GOLD + "" + ChatColor.BOLD + "VOTE RESULTS");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Add final results message
        Score timeScore = objective.getScore(ChatColor.GREEN + "Voting has ended!");
        timeScore.setScore(1000);

        playerBoards.put(playerId, board);
        setScores(board, objective, options, results, highestVote);
        player.setScoreboard(board);
    }

    /**
     * Updates a player's scoreboard with current vote counts.
     */
    private void updateScoreboard(Player player, List<String> options, int[] counts) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            UUID playerId = player.getUniqueId();
            if (!playerBoards.containsKey(playerId)) return;

            Scoreboard board = playerBoards.get(playerId);
            Objective objective = board.getObjective(objectiveName);
            if (objective == null) return;

            // Find highest vote count for highlighting
            int maxVotes = 0;
            for (int count : counts) {
                if (count > maxVotes) {
                    maxVotes = count;
                }
            }

            // Only highlight winners if voting has ended
            setScores(board, objective, options, counts, votingEnded.get() ? maxVotes : 0);
        });
    }

    /**
     * Sets the option scores on a scoreboard.
     */
    private void setScores(Scoreboard board, Objective objective, List<String> options, int[] counts, int highestVote) {
        // Clear any existing option scores (but leave time display)
        for (String entry : new ArrayList<>(board.getEntries())) {
            if (!entry.contains("Time remaining:") && !entry.contains("Voting has ended!")) {
                board.resetScores(entry);
            }
        }

        // Add separator line
        Score separator = objective.getScore(ChatColor.DARK_GRAY + "--------------------");
        separator.setScore(options.size() + 2);

        // Add instruction line
        Score instruction = objective.getScore(ChatColor.YELLOW + "Type number in Twitch chat");
        instruction.setScore(options.size() + 1);

        // Check if we need to display options side by side (more than 10 options)
        boolean useCompactLayout = options.size() > 10;

        if (!useCompactLayout) {
            // Original layout - one option per line
            AtomicInteger position = new AtomicInteger(options.size());
            for (int i = 0; i < options.size(); i++) {
                String displayText = formatOptionText(i, options.get(i), counts[i], highestVote);

                // Ensure entries are unique by appending spaces if needed
                while (board.getEntries().contains(displayText)) {
                    displayText += " ";
                }

                Score optionScore = objective.getScore(displayText);
                optionScore.setScore(position.getAndDecrement());
            }
        } else {
            // Compact layout - two options per line
            AtomicInteger position = new AtomicInteger((options.size() + 1) / 2); // Ceiling division

            for (int i = 0; i < options.size(); i += 2) {
                StringBuilder displayText = new StringBuilder();

                // Add first option
                displayText.append(formatOptionText(i, options.get(i), counts[i], highestVote, true));

                // Add second option if available
                if (i + 1 < options.size()) {
                    displayText.append(" | ");
                    displayText.append(formatOptionText(i + 1, options.get(i + 1), counts[i + 1], highestVote, true));
                }

                // Ensure entries are unique
                String finalText = displayText.toString();
                while (board.getEntries().contains(finalText)) {
                    finalText += " ";
                }

                Score optionScore = objective.getScore(finalText);
                optionScore.setScore(position.getAndDecrement());
            }
        }
    }

    /**
     * Formats the text for a voting option.
     */
    private String formatOptionText(int index, String option, int count, int highestVote) {
        return formatOptionText(index, option, count, highestVote, false);
    }

    /**
     * Formats the text for a voting option with compact option.
     */
    private String formatOptionText(int index, String option, int count, int highestVote, boolean compact) {
        if (count == highestVote && highestVote > 0) {
            // Highlight winning option(s)
            if (compact) {
                // Use shorter format for compact display
                return ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + (index + 1) + "." +
                       option + ChatColor.GOLD + "[" + count + "]";
            } else {
                return ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + (index + 1) + ". " +
                       option + " " + ChatColor.GOLD + "[" + count + "]";
            }
        } else {
            if (compact) {
                // Use shorter format for compact display
                return ChatColor.AQUA + "" + (index + 1) + "." +
                       ChatColor.WHITE + option + " " +
                       ChatColor.GREEN + "[" + count + "]";
            } else {
                return ChatColor.AQUA + "" + (index + 1) + ". " +
                       ChatColor.WHITE + option + " " +
                       ChatColor.GREEN + "[" + count + "]";
            }
        }
    }

    /**
     * Hides a scoreboard for a specific player.
     */
    public void hideScoreboard(Player player) {
        UUID playerId = player.getUniqueId();

        // Cancel update task
        if (playerUpdateTasks.containsKey(playerId)) {
            Bukkit.getScheduler().cancelTask(playerUpdateTasks.get(playerId));
            playerUpdateTasks.remove(playerId);
        }

        // Remove from time tracking
        remainingTimeMap.remove(playerId);

        // Reset to main scoreboard
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null) {
            player.setScoreboard(manager.getMainScoreboard());
        }

        playerBoards.remove(playerId);
    }

    /**
     * Hides all active scoreboards for all players.
     */
    public void hideAllScoreboards() {
        // Mark voting as ended
        votingEnded.set(true);

        // Get a copy of keys to avoid concurrent modification
        List<UUID> playerIds = new ArrayList<>(playerBoards.keySet());

        for (UUID playerId : playerIds) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                hideScoreboard(player);
            }
        }

        // Cancel all update tasks
        for (Integer taskId : playerUpdateTasks.values()) {
            Bukkit.getScheduler().cancelTask(taskId);
        }

        playerUpdateTasks.clear();
        playerBoards.clear();
        remainingTimeMap.clear();
    }
}