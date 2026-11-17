package io.smarttrack.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmartTrackApplication {
  public static void main(String[] args) {
    SpringApplication.run(SmartTrackApplication.class, args);
  }
}
