package io.smarttrack.android.data;

import com.google.gson.*;
import io.smarttrack.contract.BackendEndpoint;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import retrofit2.*;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.*;

public interface Backend {
  OkHttpClient TRANSPORT =
      new OkHttpClient.Builder()
          .connectTimeout(5, TimeUnit.SECONDS)
          .readTimeout(10, TimeUnit.SECONDS)
          .build();

  @POST("v1/auth/register")
  Call<JsonObject> register(@Body JsonObject body);

  @POST("v1/auth/login")
  Call<JsonObject> login(@Body JsonObject body);

  @POST("v1/auth/logout")
  Call<JsonObject> logout();

  @GET("v1/devices")
  Call<JsonArray> devices();

  @POST("v1/devices")
  Call<JsonObject> registerDevice(@Body JsonObject body);

  @GET("v1/devices/{device}/latest")
  Call<JsonObject> latest(@Path("device") String device);

  @GET("v1/devices/{device}/history")
  Call<JsonArray> history(
      @Path("device") String device,
      @Query("from") String from,
      @Query("to") String to,
      @Query("limit") int limit);

  @GET("v1/devices/{device}/alerts")
  Call<JsonArray> alerts(@Path("device") String device);

  @POST("v1/alerts/{alert}/acknowledgement")
  Call<JsonObject> acknowledge(@Path("alert") String alert);

  @POST("v1/synchronization")
  Call<JsonArray> sync(@Body JsonArray values);

  @GET("actuator/health")
  Call<JsonObject> health();

  @GET("v1/info")
  Call<JsonObject> info();

  static void verifyIdentity(Backend api) throws java.io.IOException {
    var response = api.info().execute();
    if (!response.isSuccessful()
        || response.body() == null
        || !response.body().has("application")
        || !response.body().get("application").getAsString().equals("io.smarttrack.backend"))
      throw new IllegalArgumentException(
          "This is not the current SmartTrack backend. Run Start-Backend.cmd -Restart and use port"
              + " 8087.");
  }

  static Backend create(String address, String token) {
    address = BackendEndpoint.normalize(address);
    if (!BuildDebug.isDebug() && !address.startsWith("https://"))
      throw new IllegalArgumentException("Release builds require HTTPS");
    OkHttpClient client =
        TRANSPORT
            .newBuilder()
            .addInterceptor(
                chain -> {
                  var b = chain.request().newBuilder();
                  if (token != null) b.header("Authorization", "Bearer " + token);
                  return chain.proceed(b.build());
                })
            .build();
    return new Retrofit.Builder()
        .baseUrl(address.endsWith("/") ? address : address + "/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(Wire.JSON))
        .build()
        .create(Backend.class);
  }
}
