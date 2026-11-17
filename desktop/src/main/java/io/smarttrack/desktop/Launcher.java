package io.smarttrack.desktop;

/** jpackage classpath launcher avoids the Java launcher special case for Application. */
public final class Launcher {
  public static void main(String[] args) {
    SmartTrackDesktop.main(args);
  }
}
