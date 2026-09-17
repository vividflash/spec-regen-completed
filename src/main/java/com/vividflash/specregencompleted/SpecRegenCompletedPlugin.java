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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
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
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
    name = "Spec Regen Completed",
    description = "Plays a sound when your special attack energy reaches your weapon's cost",
    tags = {"special", "attack", "spec", "energy", "sound", "audio", "alert", "combat"}
)
public class SpecRegenCompletedPlugin extends Plugin
{
    private static final String PREVIEW_COMMAND = "specready";

    /** SA_ENERGY counts tenths of a percent, so a full bar reads 1000. */
    private static final int ENERGY_PER_PERCENT = 10;

    /** No reading taken yet, so there is nothing to compare a change against. */
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

    private final Map<SpecSound, byte[]> loadedSounds = new EnumMap<>(SpecSound.class);

    private volatile boolean running;
    private int lastEnergy = NO_READING;

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

        // Enabling the plugin part-way through a regen still has to catch the
        // next crossing, so take a starting reading rather than waiting for the
        // varp to change twice.
        clientThread.invoke(() ->
        {
            if (client.getGameState() == GameState.LOGGED_IN)
            {
                lastEnergy = client.getVarpValue(VarPlayerID.SA_ENERGY);
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
        loadedSounds.clear();
        lastEnergy = NO_READING;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        // A hop or a logout leaves a stale reading behind, and the fresh varps
        // arriving on the next login would then read as a regen.
        if (event.getGameState() != GameState.LOGGED_IN)
        {
            lastEnergy = NO_READING;
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
        lastEnergy = energy;

        // Spending energy, and the first reading of a session, are not regens.
        if (previous == NO_READING || energy <= previous)
        {
            return;
        }

        int required = requiredEnergy();
        if (required <= 0)
        {
            return;
        }

        if (previous < required && energy >= required)
        {
            play();
        }
    }

    @Subscribe
    public void onCommandExecuted(CommandExecuted event)
    {
        if (!PREVIEW_COMMAND.equalsIgnoreCase(event.getCommand()))
        {
            return;
        }

        if (config.volume() <= 0)
        {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                "Spec Regen Completed plays nothing while its volume is 0.", null);
            return;
        }

        play();
    }

    /**
     * Energy the equipped weapon needs for one special attack, in SA_ENERGY
     * units, or 0 when the weapon has no special attack this plugin knows of.
     */
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
        return spec == null ? 0 : spec.getCostPercent() * ENERGY_PER_PERCENT;
    }

    private void play()
    {
        int volume = config.volume();
        if (volume <= 0)
        {
            return;
        }

        byte[] soundBytes = bytesFor(config.sound());
        if (soundBytes == null)
        {
            return;
        }

        // Squaring the slider follows perceived loudness more closely than the
        // raw fraction does.
        float amplitude = (volume / 100f) * (volume / 100f);

        byte[] scaled = withAmplitude(soundBytes, amplitude);
        byte[] clip = scaled == null ? soundBytes : scaled;

        // AudioPlayer's gain runs through the mixer's MASTER_GAIN control, which
        // not every system exposes. Scaled samples need no gain at all, so the
        // dB figure is only for a clip this parser could not scale itself.
        float gainDb = scaled == null ? (float) Math.max(-80.0, 20.0 * Math.log10(amplitude)) : 0f;

        submit(() ->
        {
            if (!running)
            {
                return;
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
    }

    /**
     * Hands work to the shared client executor. Rejection only happens while the
     * client is shutting down, where dropping the sound is the right outcome.
     */
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

    /**
     * The clip bytes for a choice, read from the jar on first use and kept so a
     * repeat play costs nothing, or null when the clip cannot be read.
     */
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
            return bytes;
        }
        catch (IOException e)
        {
            log.warn("Could not read {}", sound.getResource(), e);
            return null;
        }
    }

    /**
     * Copies a RIFF/WAVE clip with every 16 bit sample multiplied by
     * {@code amplitude}, or returns null when the bytes are not a flavour this
     * parser handles, which leaves the caller to fall back on mixer gain.
     */
    private static byte[] withAmplitude(byte[] wav, float amplitude)
    {
        if (wav.length < 44
            || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F'
            || wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E')
        {
            return null;
        }

        int dataStart = -1;
        int dataLength = 0;
        int bitsPerSample = 0;
        int format = 0;

        // Walk the chunk list rather than assuming the canonical 44 byte header,
        // since an encoder is free to write chunks of its own before the samples.
        for (int cursor = 12; cursor + 8 <= wav.length; )
        {
            int chunkLength = readInt(wav, cursor + 4);
            if (chunkLength < 0)
            {
                return null;
            }

            int body = cursor + 8;
            if (matches(wav, cursor, "fmt "))
            {
                if (chunkLength < 16 || body + 16 > wav.length)
                {
                    return null;
                }

                format = readShort(wav, body);
                bitsPerSample = readShort(wav, body + 14);
            }
            else if (matches(wav, cursor, "data"))
            {
                dataStart = body;
                dataLength = Math.min(chunkLength, wav.length - body);
                break;
            }

            // Chunk bodies are word aligned, so an odd length carries a pad byte.
            cursor = body + chunkLength + (chunkLength & 1);
        }

        // Format 1 is uncompressed PCM, the only one whose samples can be
        // multiplied where they lie.
        if (dataStart < 0 || format != 1 || bitsPerSample != 16)
        {
            return null;
        }

        byte[] out = wav.clone();
        int dataEnd = dataStart + dataLength;
        for (int i = dataStart; i + 1 < dataEnd; i += 2)
        {
            int sample = (short) ((out[i] & 0xff) | (out[i + 1] << 8));
            sample = Math.round(sample * amplitude);
            sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
            out[i] = (byte) sample;
            out[i + 1] = (byte) (sample >> 8);
        }

        return out;
    }

    private static boolean matches(byte[] wav, int offset, String id)
    {
        for (int i = 0; i < id.length(); i++)
        {
            if (wav[offset + i] != id.charAt(i))
            {
                return false;
            }
        }

        return true;
    }

    private static int readInt(byte[] wav, int offset)
    {
        return (wav[offset] & 0xff)
            | ((wav[offset + 1] & 0xff) << 8)
            | ((wav[offset + 2] & 0xff) << 16)
            | ((wav[offset + 3] & 0xff) << 24);
    }

    private static int readShort(byte[] wav, int offset)
    {
        return (wav[offset] & 0xff) | ((wav[offset + 1] & 0xff) << 8);
    }
}
