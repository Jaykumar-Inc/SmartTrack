package io.smarttrack.android.ble;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reassembles v1 notifications at any MTU; rejects conflicting fragments and bounded overflow. */
public final class FrameAssembler {
  private static final class Frame {
    long started;
    byte[][] parts;
    int bytes;

    Frame(long now, int total) {
      started = now;
      parts = new byte[total][];
    }
  }

  private final LinkedHashMap<Integer, Frame> frames = new LinkedHashMap<>();

  public synchronized void clear() {
    frames.clear();
  }

  public synchronized String accept(byte[] packet, long now) {
    frames.entrySet().removeIf(e -> now - e.getValue().started > 10000);
    if (packet.length < 9 || packet[0] != 1 || packet[1] != 1)
      throw new IllegalArgumentException("Invalid BLE frame");
    int id = u16(packet, 2), part = u16(packet, 4), total = u16(packet, 6);
    if (total < 1 || total > 256 || part >= total)
      throw new IllegalArgumentException("Invalid fragment count");
    Frame f = frames.get(id);
    if (f == null) {
      if (frames.size() >= 2) frames.remove(frames.keySet().iterator().next());
      f = new Frame(now, total);
      frames.put(id, f);
    }
    if (f.parts.length != total) {
      frames.remove(id);
      throw new IllegalArgumentException("Fragment count changed");
    }
    byte[] data = Arrays.copyOfRange(packet, 8, packet.length);
    if (f.parts[part] != null) {
      if (!Arrays.equals(f.parts[part], data)) {
        frames.remove(id);
        throw new IllegalArgumentException("Conflicting BLE fragment");
      }
    } else {
      f.parts[part] = data;
      f.bytes += data.length;
    }
    if (f.bytes > 16384) {
      frames.remove(id);
      throw new IllegalArgumentException("BLE record too large");
    }
    for (byte[] bytes : f.parts) if (bytes == null) return null;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] bytes : f.parts) out.write(bytes, 0, bytes.length);
    frames.remove(id);
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  private static int u16(byte[] b, int i) {
    return (b[i] & 255) | ((b[i + 1] & 255) << 8);
  }
}
