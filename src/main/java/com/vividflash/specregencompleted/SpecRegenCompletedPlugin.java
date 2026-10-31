/*
 * Copyright (c) 2026, vividflash
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.vividflash.specregencompleted;

import com.google.inject.Provides;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
    name = "Spec Regen Completed",
    description = "Plays a Sound When Your Special Attack Is Ready",
    tags = {"special", "attack", "spec", "energy", "sound", "audio", "alert", "combat"}
)
public class SpecRegenCompletedPlugin extends Plugin
{
    private static final String PREVIEW_COMMAND = "specready";

    // SA_ENERGY counts tenths of a percent, so a full bar reads 1000.
    private static final int ENERGY_PER_PERCENT = 10;

    private static final int FULL_ENERGY = 100 * ENERGY_PER_PERCENT;

    private static final String LAST_SEEN_VERSION_KEY = "lastSeenVersion";

    private static final String VERSION = "1.2";
    private static final String UPDATE_MESSAGE =
        "Spec Regen Completed v" + VERSION + ": Added 10 Weapons";

    private static final Color NOTICE_COLOR = new Color(0x480000);

    // No reading taken yet, so there is nothing to compare a change against.
    private static final int NO_READING = -1;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private AudioPlayer audioPlayer;

    @Inject
    private SpecRegenCompletedConfig config;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ChatMessageManager chatMessageManager;

    // Filled on the executor, cleared by shutDown on another thread.
    private final Map<SpecSound, byte[]> loadedSounds = new ConcurrentHashMap<>();

    private volatile boolean running;
    private boolean updateChecked;
    private int lastEnergy = NO_READING;

    // Last state from GameStateChanged, kept only so the energy debug line can name it without a
    // client read.
    private GameState gameState;

    @Provides
    SpecRegenCompletedConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(SpecRegenCompletedConfig.class);
    }

    @Override
    protected void startUp() throws Exception
    {
        for (SpecSound sound : SpecSound.values())
        {
            if (getClass().getResource(sound.getResource()) == null)
            {
                throw new IOException(sound.getResource() + " is not in the plugin jar");
            }
        }

        running = true;
        lastEnergy = NO_READING;
        if (log.isDebugEnabled())
        {
            log.debug("[config] startUp volume={} sound={}", config.volume(), config.sound());
        }

        // Enabling the plugin part-way through a regen still has to catch the
        // next crossing, so take a starting reading rather than waiting for the
        // varp to change twice.
        clientThread.invoke(() ->
        {
            gameState = client.getGameState();
            if (gameState == GameState.LOGGED_IN)
            {
                lastEnergy = client.getVarpValue(VarPlayerID.SA_ENERGY);
                if (log.isDebugEnabled())
                {
                    log.debug("[state] starting reading energy={}", lastEnergy);
                }
            }
        });
    }

    @Override
    protected void shutDown()
    {
        // A clip already queued on the shared executor checks this before it
        // plays, so disabling the plugin cannot be followed by a sound. The
        // audio line itself is the client's to release: AudioPlayer hands out a
        // self-closing one.
        running = false;
        log.debug("[state] shutDown");
        loadedSounds.clear();
        lastEnergy = NO_READING;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        // A hop or a logout leaves a stale reading behind, and the fresh varps
        // arriving on the next login would then read as a regen. A loading
        // screen (teleport, instance) keeps the reading: the energy varp is not
        // resent on a map load, so the next regen still has its previous value.
        gameState = event.getGameState();
        if (log.isDebugEnabled())
        {
            log.debug("[spec] game state {} energy baseline={}", gameState, lastEnergy);
        }
        switch (gameState)
        {
            case LOGIN_SCREEN:
            case HOPPING:
            case CONNECTION_LOST:
                if (log.isDebugEnabled())
                {
                    log.debug("[state] game state {}, energy reading reset", gameState);
                }
                lastEnergy = NO_READING;
                break;
            case LOGGED_IN:
                maybeAnnounceUpdate();
                break;
            default:
                break;
        }
    }

    // One-time notice on the first login with a new VERSION. A profile with no recorded version is
    // told as well.
    private void maybeAnnounceUpdate()
    {
        if (updateChecked)
        {
            return;
        }
        updateChecked = true;

        String lastSeen = configManager.getConfiguration(SpecRegenCompletedConfig.GROUP, LAST_SEEN_VERSION_KEY);

        if (log.isDebugEnabled())
        {
            log.debug("[update] lastSeen={} current={}", lastSeen, VERSION);
        }
        if (!VERSION.equals(lastSeen))
        {
            configManager.setConfiguration(SpecRegenCompletedConfig.GROUP, LAST_SEEN_VERSION_KEY, VERSION);
            chatMessageManager.queue(QueuedMessage.builder()
                .type(ChatMessageType.CONSOLE)
                .runeLiteFormattedMessage(new ChatMessageBuilder()
                    .append(NOTICE_COLOR, UPDATE_MESSAGE)
                    .build())
                .build());
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        if (event.getVarpId() != VarPlayerID.SA_ENERGY)
        {
            return;
        }

        int previous = lastEnergy;
        int energy = event.getValue();
        // Login sends 0 before the restored energy, so a 0 cannot be the first reading.
        if (previous != NO_READING || energy != 0)
        {
            lastEnergy = energy;
        }
        if (log.isDebugEnabled())
        {
            log.debug("[spec] energy varp state={} previous={} new={}", gameState, previous, energy);
        }

        // Spending energy, and the first reading of a session, are not regens.
        if (previous == NO_READING || energy <= previous)
        {
            return;
        }

        int required = requiredEnergy();
        if (crossed(previous, energy, required))
        {
            if (log.isDebugEnabled())
            {
                log.debug("[spec] crossed required={} previous={} energy={}", required, previous, energy);
            }
            play();
        }
        else if (previous < FULL_ENERGY && energy >= FULL_ENERGY && config.playAtFull()
            && (required > 0 || config.playAtFullWithoutSpecWeapon()))
        {
            if (log.isDebugEnabled())
            {
                log.debug("[spec] full bar sound, previous={} new={} playAtFull=true required={}",
                    previous, energy, required);
            }
            play();
        }
    }

    // True when a regen step took the bar from below a weapon's cost to at or above it, which is
    // the one thing that plays a sound. A required of 0 means the weapon has no special attack this
    // plugin knows. The caller has already returned on NO_READING.
    private static boolean crossed(int previous, int energy, int required)
    {
        return required > 0
            && previous < required
            && energy >= required;
    }

    @Subscribe
    public void onCommandExecuted(CommandExecuted event)
    {
        if (!PREVIEW_COMMAND.equalsIgnoreCase(event.getCommand()))
        {
            return;
        }

        if (play())
        {
            if (log.isDebugEnabled())
            {
                log.debug("[preview] ::{} playing", PREVIEW_COMMAND);
            }
            return;
        }

        if (log.isDebugEnabled())
        {
            log.debug("[preview] ::{} blocked, volume is 0", PREVIEW_COMMAND);
        }
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
            "Spec Regen Completed plays nothing while its volume is 0.", null);
    }

    // Energy the equipped weapon needs for one special attack, in SA_ENERGY units, or 0 when the
    // weapon has no special attack this plugin knows of.
    private int requiredEnergy()
    {
        ItemContainer worn = client.getItemContainer(InventoryID.WORN);
        if (worn == null)
        {
            return 0;
        }

        Item weapon = worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());

        // An empty slot comes back as an Item holding -1 rather than as null.
        if (weapon == null || weapon.getId() <= 0)
        {
            return 0;
        }

        SpecWeapon spec = SpecWeapon.forItemId(weapon.getId());
        if (spec == null)
        {
            return 0;
        }

        int required = spec.getCostPercent() * ENERGY_PER_PERCENT;

        // Liquid adrenaline (Tombs of Amascut) halves every special attack's
        // cost while it lasts. Every cost is a whole percent, so half of it is
        // still a whole number of SA_ENERGY units.
        if (client.getVarbitValue(VarbitID.TOA_MIDRAIDLOOT_ENERGY_ACTIVE) != 0)
        {
            if (log.isDebugEnabled())
            {
                log.debug("[spec] liquid adrenaline active, cost {} halved", required);
            }
            return required / 2;
        }

        return required;
    }

    // Returns false only when the volume is 0, so the preview command can say so.
    private boolean play()
    {
        int volume = config.volume();
        if (volume <= 0)
        {
            log.debug("[sound] skipped, volume is 0");
            return false;
        }

        SpecSound sound = config.sound();

        // Squaring the slider follows perceived loudness more closely than the
        // raw fraction does.
        float amplitude = (volume / 100f) * (volume / 100f);

        // Reading the clip from the jar and scaling it run on the executor, off
        // the client thread.
        submit(() ->
        {
            if (!running)
            {
                log.debug("[sound] dropped, plugin is shut down");
                return;
            }

            byte[] soundBytes = bytesFor(sound);
            if (soundBytes == null)
            {
                return;
            }

            // Full volume plays the clip as it is.
            byte[] scaled = amplitude == 1f ? soundBytes : WavAmplitude.withAmplitude(soundBytes, amplitude);
            byte[] clip = scaled == null ? soundBytes : scaled;

            // AudioPlayer's gain runs through the mixer's MASTER_GAIN control, which
            // not every system exposes. Scaled samples need no gain at all, so the
            // dB figure is only for a clip this parser could not scale itself.
            float gainDb = scaled == null ? (float) Math.max(-80.0, 20.0 * Math.log10(amplitude)) : 0f;

            if (log.isDebugEnabled())
            {
                log.debug("[sound] playing sound={} volume={} scaled={} gainDb={}", sound, volume, scaled != null, gainDb);
            }
            try
            {
                audioPlayer.play(new ByteArrayInputStream(clip), gainDb);
            }
            catch (Exception e)
            {
                // Covers a missing mixer, a busy line and an unreadable clip.
                log.warn("Could not play the special attack sound", e);
            }
        });
        return true;
    }

    // Hands work to the shared client executor. Rejection only happens while the client is shutting
    // down, where dropping the sound is the right outcome.
    private void submit(Runnable task)
    {
        try
        {
            executor.execute(task);
        }
        catch (RejectedExecutionException e)
        {
            log.debug("Executor rejected a spec sound, the client is shutting down");
        }
    }

    // The clip bytes for a choice, read from the jar on first use and kept so a repeat play costs
    // nothing, or null when the clip cannot be read.
    private byte[] bytesFor(SpecSound sound)
    {
        byte[] cached = loadedSounds.get(sound);
        if (cached != null)
        {
            return cached;
        }

        try (InputStream in = getClass().getResourceAsStream(sound.getResource()))
        {
            if (in == null)
            {
                log.warn("{} is not in the plugin jar", sound.getResource());
                return null;
            }

            byte[] bytes = in.readAllBytes();
            loadedSounds.put(sound, bytes);
            if (log.isDebugEnabled())
            {
                log.debug("[sound] loaded {} bytes={}", sound.getResource(), bytes.length);
            }
            return bytes;
        }
        catch (IOException e)
        {
            log.warn("Could not read {}", sound.getResource(), e);
            return null;
        }
    }
}
