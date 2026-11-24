// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (c) 2026 CamBridge contributors
package app.droidalter.camera;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** Converts the length-prefixed output used by some codecs into an H.264 byte stream. */
public final class AnnexB {
    private AnnexB() {}

    public static byte[] normalize(ByteBuffer input) {
        byte[] bytes = new byte[input.remaining()];
        input.get(bytes);
        if (bytes.length < 4 || (bytes[0] == 0 && bytes[1] == 0
                && (bytes[2] == 1 || (bytes[2] == 0 && bytes[3] == 1)))) {
            return bytes;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(bytes.length + 32);
        int position = 0;
        while (position + 4 <= bytes.length) {
            long length = ((bytes[position] & 255L) << 24) | ((bytes[position + 1] & 255L) << 16)
                    | ((bytes[position + 2] & 255L) << 8) | (bytes[position + 3] & 255L);
            position += 4;
            if (length < 1 || length > bytes.length - position) {
                throw new IllegalArgumentException("Invalid H.264 NAL length");
            }
            output.write(0); output.write(0); output.write(0); output.write(1);
            output.write(bytes, position, (int) length);
            position += (int) length;
        }
        if (position != bytes.length) {
            throw new IllegalArgumentException("Truncated H.264 NAL length");
        }
        return output.toByteArray();
    }
}
