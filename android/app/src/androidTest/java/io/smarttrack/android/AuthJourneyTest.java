package io.smarttrack.android;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.smarttrack.android.data.Vault;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AuthJourneyTest {
  private static void awaitText(String text) {
    long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
    var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
      var root = automation.getRootInActiveWindow();
      if (root != null && !root.findAccessibilityNodeInfosByText(text).isEmpty()) return;
      android.os.SystemClock.sleep(100);
    }
    fail("Auth UI did not show: " + text);
  }

  private static void editors(View view, List<EditText> result) {
    if (view instanceof EditText edit) result.add(edit);
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) editors(group.getChildAt(i), result);
  }

  private static void fill(ActivityScenario<MainActivity> activity, String email, String password) {
    activity.onActivity(
        a -> {
          var fields = new ArrayList<EditText>();
          editors(a.findViewById(R.id.content), fields);
          assertEquals(
              InstrumentationRegistry.getArguments().getString("backend", "http://10.0.2.2:8087"),
              fields.get(0).getText().toString());
          fields.get(1).setText(email);
          fields.get(2).setText(password);
        });
  }

  private static void signOut() {
    onView(withId(R.id.more)).perform(click());
    awaitText("Settings");
    onView(withText("Settings")).perform(click());
    awaitText("Connection & privacy");
    onView(withText("Sign out")).perform(scrollTo(), click());
    awaitText("Sign in / create account");
    onView(withText("Sign in / create account")).perform(scrollTo(), click());
  }

  @Test
  public void createsAccountAndSignsInThroughTheRealForm() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var vault = new Vault(context);
    vault.token(null);
    vault
        .preferences()
        .edit()
        .putString(
            "backend",
            InstrumentationRegistry.getArguments().getString("backend", "http://10.0.2.2:8087"))
        .putString("device", "")
        .putString("mode", "BACKEND")
        .commit();
    String email = "android-auth-" + UUID.randomUUID() + "@example.test",
        password = "android-auth-pass-123";
    try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
      onView(withId(R.id.devices)).perform(click());
      onView(withText("Sign in / create account")).perform(scrollTo(), click());
      onView(withText("Test connection")).perform(scrollTo(), click());
      awaitText("Backend ready");
      fill(activity, email, "short");
      onView(withText("Create account")).perform(scrollTo(), click());
      awaitText("at least 12 characters");
      fill(activity, email, password);
      onView(withText("Create account")).perform(scrollTo(), click());
      awaitText("Register device");
      assertNotNull(vault.token());
      signOut();
      fill(activity, email, "wrong-password-123");
      onView(withText("Sign in")).perform(scrollTo(), click());
      awaitText("Invalid email or password");
      fill(activity, email, password);
      onView(withText("Sign in")).perform(scrollTo(), click());
      awaitText("Register device");
      signOut();
      fill(activity, email, password);
      onView(withText("Create account")).perform(scrollTo(), click());
      awaitText("Sign in instead");
    }
  }
}
