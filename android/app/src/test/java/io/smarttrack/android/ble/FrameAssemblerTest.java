package io.smarttrack.android.ble;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class FrameAssemblerTest {
  private byte[] packet(int id, int part, int count, String data) {
    byte[] bytes = data.getBytes(StandardCharsets.UTF_8), p = new byte[8 + bytes.length];
    p[0] = 1;
    p[1] = 1;
    p[2] = (byte) id;
    p[3] = (byte) (id >> 8);
    p[4] = (byte) part;
    p[5] = (byte) (part >> 8);
    p[6] = (byte) count;
    p[7] = (byte) (count >> 8);
    System.arraycopy(bytes, 0, p, 8, bytes.length);
    return p;
  }

  @Test
  public void reconnectDoesNotMixPreviousDeviceFragments() {
    FrameAssembler a = new FrameAssembler();
    assertNull(a.accept(packet(1, 0, 2, "previous-device"), 1));
    a.clear();
    assertNull(a.accept(packet(1, 1, 2, "new-tail"), 2));
    assertEquals("new-headnew-tail", a.accept(packet(1, 0, 2, "new-head"), 3));
  }

  @Test
  public void handlesOutOfOrderAndDuplicates() {
    FrameAssembler a = new FrameAssembler();
    assertNull(a.accept(packet(1, 1, 2, "world"), 1));
    assertNull(a.accept(packet(1, 1, 2, "world"), 2));
    assertEquals("hello world", a.accept(packet(1, 0, 2, "hello "), 3));
  }

  @Test
  public void expiresIncompleteFrames() {
    FrameAssembler a = new FrameAssembler();
    a.accept(packet(1, 0, 2, "a"), 1);
    assertNull(a.accept(packet(1, 1, 2, "b"), 11002));
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsConflictingFragments() {
    FrameAssembler a = new FrameAssembler();
    a.accept(packet(1, 0, 2, "a"), 1);
    a.accept(packet(1, 0, 2, "b"), 2);
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsUnboundedPartCounts() {
    new FrameAssembler().accept(packet(1, 0, 257, "a"), 1);
  }
}
