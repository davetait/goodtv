# Live previews in the guide — design

Status: draft for Dave's approval, 2026-10-30.

## Why

Dave flips channels in the Live TV guide. He stops briefly on a channel to see **and hear** what's on, and decides whether to watch it. Today the guide only shows the current show's name, summary and a thumbnail (the channel logo) in the details panel at the bottom. He wants that panel to play the channel live.

## What Dave decided

1. **A Live TV section in Settings.** Its first setting is **Live previews**, an on/off switch.
2. **With Live previews on**, the details panel at the bottom of the guide is **twice its current height**. Its thumbnail **plays the highlighted channel live, with sound**, so he can tell whether he wants it before going full screen.
3. **The channel selector stays easy to see and use.** The design is approached for the best experience while flipping channels.
4. **Selecting the previewed channel grows the playing picture into full screen** with an animation.
5. Dave approved the behaviour in the next section as played back to him ("Everything looks good"), including the items marked as Claude's guesses. Mockup: `.superpowers/brainstorm/94530-1793335018/content/live-preview.html`.

## How it works

- **Setting**
  - Settings gets a third row, **Live TV**, after Sources and Integrations. It opens a Live TV settings screen with one switch, **Live previews**.
  - The switch is **off by default** (Claude's guess, approved). The Settings row's status line reads "Live previews on" or "Live previews off".
  - The value is stored with the app's other settings (DataStore). It applies at once, including to a guide that's already open.
- **Panel**
  - With previews on, the details panel is **twice its current height** the whole time the guide is open, not only while a preview plays.
  - It keeps a steady height so the rows never jump while moving (Claude's guess, approved).
  - The picture area becomes a **16:9 video** at the panel's new height, where the logo thumbnail is today. The show name, time, summary and the next show sit beside it.
  - The guide shows fewer rows (about 3 instead of 5). The highlighted row always stays in view.
- **When a preview plays**
  - **Start:** a preview starts after resting on a channel row for **about 1 second**. Holding a direction to move fast starts nothing.
  - **Stop:** the previous preview stops the moment focus moves on.
  - **Why the delay:** each preview opens a connection to the provider. Starting one per channel while skimming could hit the account's connection limit or get it blocked for a while (Claude's guess, approved).
  - **Sound:** the preview plays with sound at the normal volume.
  - **Loading:** while a channel loads, the picture area shows the channel logo with a small spinner, as the thumbnail does today. If the stream fails, the logo stays with a short "Preview unavailable" note. The guide itself is unaffected.
  - **Which channels:** previews play only for live channel rows that are playable now. Event slots that aren't on yet, and rows with no feed, show the logo as today.
  - **Several copies:** for a row that merges several copies of a channel, the preview plays the **first copy**. Selecting still opens the copy picker as today (Claude's guess, approved).
    - "First" means the copy listed first in the copy picker (`ChannelOrder.sort(row.feeds).first()`), so picking the top copy continues the preview.
    - The preview resolves that copy's stream exactly as the full-screen player would, so a hand-off needs no restart (Claude's call).
  - **A preview belongs to the channel, not the focused cell** (Claude's call).
    - It starts when focus rests on any cell of a channel's row.
    - It keeps playing while focus moves left or right along the same row; the text beside it follows the focused programme as today.
    - It stops only when focus moves to another row or leaves the grid.
  - **Guide downloads pause while a preview plays,** as they do for the full-screen player (`playback.enter()`), so a guide refresh never competes with the stream on the onn box (Claude's call).
- **Going full screen** (Dave's addition)
  - **Seamless:** selecting the channel while its preview plays continues the **same stream**, without restarting or buffering again.
  - **Animation:** the picture **animates from its place in the panel to full screen**, with the guide fading behind it. The player's controls then appear as they do today.
  - **No preview yet:** selecting a channel whose preview isn't playing (moved too fast, or still loading) opens the player as today.
  - **Several copies:** when the copy picker appears, the animation happens after the copy is chosen, from the panel if the chosen copy is the one previewing. A different copy opens normally.
  - **Back** (Claude's call): Back from a full-screen channel that came from a preview returns to the guide on the same row.
    - The **same stream keeps playing in the panel**, with the picture shrinking back into it if the hand-off makes that natural. Otherwise it cuts back without the animation, but still without restarting.
    - Coming back from a channel opened without a preview, the panel starts a preview after the usual rest delay.
  - **Info box** (Claude's call): after a hand-off, the full-screen Info box's measurements may include the preview's download. That's acceptable.
- **Stopping**
  - The preview stops on any of these: leaving Live TV (another tab, a pushed screen, Home or the app going to the background), opening the search or a chip menu, or turning the setting off.
  - Nothing keeps playing out of sight.

## Goals, non-goals, anti-goals

- **Goals:**
  - Resting on a channel shows and plays it within a few seconds.
  - Flipping up and down stays as quick as today.
  - Going full screen from a preview is instant and animated.
- **Non-goals:**
  - Previews anywhere else: Sports, Home, search results.
  - A mute option or volume control for previews.
  - Picture-in-picture while browsing other tabs.
  - Choosing which copy previews.
- **Anti-goals:**
  - Fast scrolling opens a stream per channel passed.
  - The guide's rows jump or resize as previews start and stop.
  - A preview keeps playing, or keeps a connection open, after leaving the guide.
  - Selecting a previewing channel restarts the stream or shows a black screen before full screen.
  - Previews make the guide sluggish on the onn box or the Streamer.

## Constraints

- **Existing player behaviour:** the full-screen player stays as it is today, with its controls, Info box, subtitles, keeping the screen on and the picture-shape fit.
- **One stream at a time:** a preview and the full-screen player never both hold a connection.
- **Testing:**
  - TV focus and timing get instrumented tests, using local test media rather than the provider.
  - Request focus from `onPlaced`.
  - Back up and restore Dave's emulator data with `~/.goodtv-tools/runclass.sh`.
  - The emulator shows playing video as black, so the animation needs a check on a real device.
- **Real devices:** the onn 4K Pro and the Google TV Streamer. Video decoding on the onn box is limited, so a preview must be released before the full-screen player needs a second decoder, or be the same player.

## Left to the builder

- How the preview player is shared with the full-screen player (for example, one player owned above the navigation and handed over), and how the screen transition animates (a shared-element transition or a custom one).
- The exact rest delay (about 1 s) and how "moving fast" is detected.
- The panel's exact sizes, within twice the current height and a 16:9 picture.
- How the Live TV settings screen is laid out, following the Settings and Integrations screens.
