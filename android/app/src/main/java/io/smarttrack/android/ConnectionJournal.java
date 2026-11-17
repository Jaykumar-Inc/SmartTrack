package io.smarttrack.android;

import java.time.Instant;
import java.util.*;

/** Bounded status history. Measurements, passwords, account tokens and passkeys are never added. */
public final class ConnectionJournal {
  private final Deque<String> events = new ArrayDeque<>();
  private String previous = "";

  public synchronized void add(String message) {
    if (message == null || message.equals(previous)) return;
    previous = message;
    if (events.size() == 30) events.removeFirst();
    events.addLast(Instant.now() + "  " + message);
  }

  public synchronized String text() {
    return String.join("\n", events);
  }
}
