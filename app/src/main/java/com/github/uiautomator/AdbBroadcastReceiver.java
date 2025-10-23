package com.github.uiautomator;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.util.JsonWriter;
import android.util.Log;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AdbBroadcastReceiver extends BroadcastReceiver {

    private MockLocationProvider mockGPS;
    private MockLocationProvider mockWifi;
    private static final String TAG = "MockGPSReceiver";

    private String checkIP(OkHttpClient client, String url) {
        Request request = new Request.Builder().url(url)
                .header("Accept", "text/plain")  // 明确指定接受纯文本
                .header("User-Agent", "curl/7.64.1")  // 模拟 curl 客户端
                .build();
        try(Response response = client.newCall(request).execute()) {
            if(!response.isSuccessful()) {
               return null;
            }
            return response.body().string().trim();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if(action == null) {
            return;
        }
        action = action.toLowerCase();
        if (action.equals("stop.mock")) {
            if (mockGPS != null) {
                mockGPS.shutdown();
            }
            if (mockWifi != null) {
                mockWifi.shutdown();
            }
        }
        else if(action.equals("led_on")) {
            if(!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
                return;
            }
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            try {
                String[] cameraIds = cameraManager.getCameraIdList();
                for (String cameraId : cameraIds) {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);

                    // 只对有闪光灯的相机设置手电筒模式
                    if (flashAvailable != null && flashAvailable) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            cameraManager.setTorchMode(cameraId, true);
                        }
                    }
                }
            } catch (CameraAccessException e) {
                Log.e("AdbIME", "Failed to access camera", e);
            }
        }
        else if(action.equals("led_off")) {
            if(!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
                return;
            }
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            try {
                String[] cameraIds = cameraManager.getCameraIdList();
                for (String cameraId : cameraIds) {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);

                    // 只对有闪光灯的相机设置手电筒模式
                    if (flashAvailable != null && flashAvailable) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            cameraManager.setTorchMode(cameraId, false);
                        }
                    }
                }
            } catch (CameraAccessException e) {
                Log.e("AdbIME", "Failed to access camera", e);
            }
        }
        else if(action.equals("public_ip")) {
            Log.i(TAG, "onReceive: " + action);
            final PendingResult pendingResult = goAsync();
            new Thread(()->{
                OkHttpClient client = new OkHttpClient.Builder().build();
                String http_ip = AdbBroadcastReceiver.this.checkIP(client, "http://checkip.amazonaws.com");
                String https_ip = AdbBroadcastReceiver.this.checkIP(client, "https://checkip.amazonaws.com");
                StringWriter stringWriter = new StringWriter();
                JsonWriter jsonWriter = new JsonWriter(stringWriter);
                try {
                    jsonWriter.beginObject();
                    if (http_ip != null) {
                        jsonWriter.name("http_ip").value(http_ip);
                    }
                    if (https_ip != null) {
                        jsonWriter.name("https_ip").value(https_ip);
                    }
                    jsonWriter.endObject();
                } catch (IOException ignore) {

                }
                pendingResult.setResultData(stringWriter.toString());
                pendingResult.finish();
            }).start();
        }
        else if(action.equals("send.mock")) {
            mockGPS = new MockLocationProvider(LocationManager.GPS_PROVIDER, context);
            mockWifi = new MockLocationProvider(LocationManager.NETWORK_PROVIDER, context);

            double lat = Double.parseDouble(intent.getStringExtra("lat") != null ? intent.getStringExtra("lat") : "0");
            double lon = Double.parseDouble(intent.getStringExtra("lon") != null ? intent.getStringExtra("lon") : "0");
            double alt = Double.parseDouble(intent.getStringExtra("alt") != null ? intent.getStringExtra("alt") : "0");
            float accurate = Float.parseFloat(intent.getStringExtra("accurate") != null ? intent.getStringExtra("accurate") : "0");
            Log.i(TAG, String.format("setting mock to Latitude=%f, Longitude=%f Altitude=%f Accuracy=%f", lat, lon, alt, accurate));
            mockGPS.pushLocation(lat, lon, alt, accurate);
            mockWifi.pushLocation(lat, lon, alt, accurate);
        }
    }
}
