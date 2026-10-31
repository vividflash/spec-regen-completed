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

// Volume scaling for the bundled 16 bit PCM clips, done on the samples so no mixer gain control is
// needed.
final class WavAmplitude
{
    private WavAmplitude()
    {
    }

    // Copies a RIFF/WAVE clip with every 16 bit sample multiplied by amplitude, or returns null
    // when the bytes are not a flavour this parser handles, which leaves the caller to fall back on
    // mixer gain.
    static byte[] withAmplitude(byte[] wav, float amplitude)
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
            int sample = (short) readShort(out, i);
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
