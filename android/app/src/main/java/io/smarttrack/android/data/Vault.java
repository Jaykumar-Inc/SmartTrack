package io.smarttrack.android.data;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypt sensitive cache payloads and credentials with an app-bound Android Keystore key. */
public final class Vault {
  private final SharedPreferences prefs;
  private final javax.crypto.SecretKey key;

  public Vault(Context context) {
    prefs = context.getSharedPreferences("smarttrack", Context.MODE_PRIVATE);
    try {
      KeyStore store = KeyStore.getInstance("AndroidKeyStore");
      store.load(null);
      if (!store.containsAlias("smarttrack-v1")) {
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(
            new KeyGenParameterSpec.Builder(
                    "smarttrack-v1", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        generator.generateKey();
      }
      key = (javax.crypto.SecretKey) store.getKey("smarttrack-v1", null);
    } catch (Exception e) {
      throw new IllegalStateException("Secure storage unavailable", e);
    }
  }

  public String encrypt(String value) {
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key);
      return Base64.encodeToString(c.getIV(), Base64.NO_WRAP)
          + ":"
          + Base64.encodeToString(
              c.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot encrypt local data", e);
    }
  }

  public String decrypt(String value) {
    try {
      String[] parts = value.split(":");
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          key,
          new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
      return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot unlock local data; sign in again", e);
    }
  }

  public void token(String value) {
    if (value == null) prefs.edit().remove("token").apply();
    else prefs.edit().putString("token", encrypt(value)).apply();
  }

  public String token() {
    String value = prefs.getString("token", null);
    return value == null ? null : decrypt(value);
  }

  public SharedPreferences preferences() {
    return prefs;
  }
}
