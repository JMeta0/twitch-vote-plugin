package com.czacha994.twitchvoting;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.TwitchClientBuilder;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages a Twitch chat voting session, connecting to a specified channel
 * and collecting votes from chat messages.
 */
public class TwitchVoteSession {
    private final JavaPlugin plugin;
    private final String channel;
    private final int optionCount;
    private TwitchClient twitchClient;
    private final ConcurrentHashMap<String, Set<Integer>> votes = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Pattern numberPattern = Pattern.compile("\\b\\d+\\b");
    private final ReentrantReadWriteLock clientLock = new ReentrantReadWriteLock();

    /**
     * Creates a new Twitch voting session.
     *
     * @param plugin The JavaPlugin instance
     * @param channel The Twitch channel to connect to
     * @param optionCount The number of voting options available
     */
    public TwitchVoteSession(JavaPlugin plugin, String channel, int optionCount) {
        this.plugin = plugin;
        this.channel = channel.toLowerCase();
        this.optionCount = optionCount;
    }

    /**
     * Starts the Twitch chat connection and begins listening for votes.
     * Should be called from an async thread.
     */
    public void start() {
        if (running.getAndSet(true)) return;

        clientLock.writeLock().lock();
        try {
            this.twitchClient = TwitchClientBuilder.builder()
                    .withEnableChat(true)
                    .withChatAccount(null) // null = anonymous (justinfan)
                    .build();

            twitchClient.getEventManager().onEvent(ChannelMessageEvent.class, event -> {
                if (!event.getChannel().getName().equalsIgnoreCase(channel)) return;

                String user = event.getUser().getName().toLowerCase();
                Set<Integer> userVotes = votes.computeIfAbsent(user, k -> new CopyOnWriteArraySet<>());

                Matcher matcher = numberPattern.matcher(event.getMessage());
                while (matcher.find()) {
                    try {
                        int num = Integer.parseInt(matcher.group());
                        if (num >= 1 && num <= optionCount) {
                            // In single vote mode, clear previous votes before adding the new one
                            if (plugin instanceof TwitchVotingPlugin &&
                                ((TwitchVotingPlugin) plugin).isSingleVoteMode()) {
                                userVotes.clear();
                            }
                            userVotes.add(num);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            });

            try {
                twitchClient.getChat().joinChannel(channel);
                plugin.getLogger().info("Connected to Twitch channel: " + channel);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to join Twitch channel: " + channel);
                plugin.getLogger().severe("Error: " + e.getMessage());
            }
        } finally {
            clientLock.writeLock().unlock();
        }
    }

    /**
     * Stops the Twitch chat connection and cleans up resources.
     * Thread-safe method that can be called from any thread.
     */
    public void stop() {
        if (!running.getAndSet(false)) return;

        clientLock.writeLock().lock();
        try {
            if (twitchClient != null) {
                try {
                    // First attempt to leave channel
                    try {
                        twitchClient.getChat().leaveChannel(channel);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error leaving Twitch channel: " + e.getMessage());
                        // Continue with cleanup even if leaving fails
                    }

                    // Then close the client
                    try {
                        twitchClient.close();
                    } catch (Exception e) {
                        plugin.getLogger().severe("Error closing Twitch client: " + e.getMessage());
                        // Continue with cleanup even if close fails
                    }

                    plugin.getLogger().info("Disconnected from Twitch channel: " + channel);
                } finally {
                    // Ensure client is nulled out even if exceptions occur
                    twitchClient = null;
                }
            }
        } finally {
            // Always unlock, even if any exceptions occur during cleanup
            clientLock.writeLock().unlock();
        }

        // Clear votes after connection is closed
        clearVotes();
    }

    /**
     * @return The current votes map (username → set of voted option numbers)
     */
    public ConcurrentHashMap<String, Set<Integer>> getVotes() {
        return votes;
    }

    /**
     * Clears all recorded votes.
     */
    public void clearVotes() {
        votes.clear();
    }

    /**
     * Calculates the current vote count for each option.
     *
     * @return An array of vote counts where index 0 corresponds to option 1
     */
    public int[] getVoteCounts() {
        int[] counts = new int[optionCount];

        // Thread-safe read of votes
        for (Set<Integer> userVotes : votes.values()) {
            for (int num : userVotes) {
                if (num >= 1 && num <= optionCount) {
                    counts[num - 1]++;
                }
            }
        }

        return counts;
    }

    /**
     * @return Whether the voting session is currently running
     */
    public boolean isRunning() {
        return running.get();
    }
}