# TwitchVoting Plugin

A Minecraft Paper plugin (1.21+) that allows players to vote on options using Twitch chat integration. Display options and voting results directly in-game using either a scoreboard or chat interface.

## Features

* **Twitch Chat Integration:** Listens to a specified Twitch channel for votes during a voting session
* **Dual Display Modes:** Choose between scoreboard (default) or chat-based display
* **Real-time Updates:** Show live vote counts as they happen
* **Vote Mechanics:**
  * Twitch viewers vote by typing option numbers (e.g., `1`)
  * Multiple votes per message are supported (e.g., `1 3`)
  * Each viewer can vote for multiple options
* **Results Display:** Shows final results with winning option(s) highlighted

## Commands

* `/vote start <seconds> <streamer> <option1> <option2> ...`
  * Starts a new vote with specified duration, Twitch channel, and options
  * Duration must be between 5-3600 seconds (configurable)
  * Maximum 20 options (configurable)
* `/vote stop`
  * Manually stops the current vote and displays results
* `/vote togglemode`
  * Switch between scoreboard and chat display modes
* `/vote reload`
  * Reload the plugin configuration
* `/vote help`
  * Shows command usage information

## Permissions

* `voting.manage`
  * Allows starting and stopping votes, and toggling display mode
  * Default: op
* `voting.admin`
  * Allows administrative actions like reloading the config
  * Default: op
* `voting.commandblock`
  * Allows command blocks to use voting commands
  * Default: op

## Configuration

The plugin uses a config.yml file with the following settings:

```yaml
# Display settings
display:
  # Use scoreboard (true) or chat (false) for vote display
  use_scoreboard: true
  # How long to show results after voting ends (in seconds)
  results_display_time: 60
  # Visual settings
  highlight_color: LIGHT_PURPLE
  winner_color: GOLD

# Default settings for voting sessions
defaults:
  # Maximum number of options allowed
  max_options: 20
  # Minimum voting time (in seconds)
  min_duration: 5
  # Maximum voting time (in seconds)
  max_duration: 3600
```

## How It Works

1. An operator starts a vote with `/vote start <seconds> <streamer> <option1> <option2> ...`
2. The plugin connects to the specified Twitch channel anonymously
3. Players in the same world see the voting options via scoreboard or chat
4. Twitch viewers vote by typing the option number in chat
5. When time runs out, results are displayed in-game with the winner highlighted
6. Results remain visible for a configurable duration

## Prerequisites

* Java 21 or later
* Paper or compatible server 1.21+
* Apache Maven (for building)

## Building the Plugin

1. **Clone the repository:**
   ```bash
   git clone <your-repository-url>
   cd twitch-voting-plugin
   ```
2. **Build with Maven:**
   ```bash
   mvn clean package
   ```
3. **Find the JAR:** The compiled plugin JAR file will be located in the `target/` directory.

## Installation

1. Copy the generated JAR file into your Paper server's `plugins/` folder
2. Restart your server
3. The plugin will generate a default config.yml in the plugins/TwitchVoting/ directory
4. Configure as needed and use `/vote reload` to apply changes

## Dependencies

The plugin uses the following libraries (shaded into the final JAR):
* Twitch4J for Twitch chat integration
* Paper API for Minecraft server integration