package com.cuemymusic.client.music;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compressed OGG duration contract: total samples come from the last page
 * granule position and the sample rate from the Vorbis identification
 * header. The scan never decodes PCM; malformed or truncated inputs yield
 * an honestly unknown duration instead of a guess.
 */
class OggDurationTest {

    private static final int SERIAL = 0x19E9_1234;

    private static byte[] identificationHeader(int sampleRate) {
        byte[] header = new byte[30];
        header[0] = 0x01;
        header[1] = 'v';
        header[2] = 'o';
        header[3] = 'r';
        header[4] = 'b';
        header[5] = 'i';
        header[6] = 's';
        header[7] = 0; // version
        header[11] = 2; // channels
        header[12] = (byte) sampleRate;
        header[13] = (byte) (sampleRate >>> 8);
        header[14] = (byte) (sampleRate >>> 16);
        header[15] = (byte) (sampleRate >>> 24);
        header[29] = 0x01; // framing
        return header;
    }

    private static void packet(StreamState stream, byte[] body, long granule, long packetNo, boolean bos,
            boolean eos) {
        Packet p = new Packet();
        p.packet_base = body;
        p.packet = 0;
        p.bytes = body.length;
        p.b_o_s = bos ? 1 : 0;
        p.e_o_s = eos ? 1 : 0;
        p.granulepos = granule;
        p.packetno = packetNo;
        assertEquals(0, stream.packetin(p));
    }

    private static byte[] oggBytes(int sampleRate, long... granules) {
        StreamState stream = new StreamState();
        stream.init(SERIAL);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Page page = new Page();
        packet(stream, identificationHeader(sampleRate), 0, 0, true, false);
        packet(stream, new byte[] {0x03, 'v', 'o', 'r', 'b', 'i', 's'}, 0, 1, false, false);
        long packetNo = 2;
        for (int i = 0; i < granules.length; i++) {
            boolean last = i == granules.length - 1;
            packet(stream, new byte[] {(byte) i, 0x55, 0x33}, granules[i], packetNo++, false, last);
        }
        while (stream.pageout(page) == 1) {
            out.write(page.header_base, page.header, page.header_len);
            out.write(page.body_base, page.body, page.body_len);
        }
        while (stream.flush(page) == 1) {
            out.write(page.header_base, page.header, page.header_len);
            out.write(page.body_base, page.body, page.body_len);
        }
        return out.toByteArray();
    }

    @Test void durationIsLastGranuleOverSampleRate() throws Exception {
        byte[] ogg = oggBytes(48_000, 48_000L * 10, 48_000L * 183);
        try (ByteArrayInputStream in = new ByteArrayInputStream(ogg)) {
            assertEquals(OptionalDouble.of(183.0), OggDuration.scan(in));
        }
    }

    @Test void nonStandardSampleRateIsHonored() throws Exception {
        byte[] ogg = oggBytes(22_050, 22_050L * 60);
        try (ByteArrayInputStream in = new ByteArrayInputStream(ogg)) {
            assertEquals(OptionalDouble.of(60.0), OggDuration.scan(in));
        }
    }

    @Test void emptyInputIsUnknown() throws Exception {
        try (ByteArrayInputStream in = new ByteArrayInputStream(new byte[0])) {
            assertEquals(OptionalDouble.empty(), OggDuration.scan(in));
        }
    }

    @Test void garbageInputIsUnknown() throws Exception {
        byte[] garbage = new byte[4096];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = (byte) (i * 31 + 7);
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(garbage)) {
            assertEquals(OptionalDouble.empty(), OggDuration.scan(in));
        }
    }

    @Test void truncatedStreamIsUnknown() throws Exception {
        byte[] ogg = oggBytes(48_000, 48_000L * 10, 48_000L * 183);
        byte[] cut = new byte[64];
        System.arraycopy(ogg, 0, cut, 0, cut.length);
        try (ByteArrayInputStream in = new ByteArrayInputStream(cut)) {
            assertEquals(OptionalDouble.empty(), OggDuration.scan(in));
        }
    }

    @Test void audioWithoutIdentificationHeaderIsUnknown() throws Exception {
        StreamState stream = new StreamState();
        stream.init(SERIAL + 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Page page = new Page();
        packet(stream, new byte[] {0x11, 0x22, 0x33}, 777, 0, true, true);
        while (stream.pageout(page) == 1) {
            out.write(page.header_base, page.header, page.header_len);
            out.write(page.body_base, page.body, page.body_len);
        }
        while (stream.flush(page) == 1) {
            out.write(page.header_base, page.header, page.header_len);
            out.write(page.body_base, page.body, page.body_len);
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray())) {
            assertEquals(OptionalDouble.empty(), OggDuration.scan(in));
        }
    }
}
