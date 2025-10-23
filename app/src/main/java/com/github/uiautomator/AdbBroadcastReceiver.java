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
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AdbBroadcastReceiver extends BroadcastReceiver {

    private MockLocationProvider mockGPS;
    private MockLocationProvider mockWifi;
    private static final String TAG = "MockGPSReceiver";

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
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
            Bundle extras = new Bundle();
            Request request = new Request.Builder().url("http://ifconfig.me").build();
            try(Response response = client.newCall(request).execute()) {
                if(!response.isSuccessful()) {
                    throw new Exception("");
                }
                extras.putString("http", response.body().string());
            } catch (Exception ignore) {

            }

            request = new Request.Builder().url("https://ifconfig.me").build();
            try(Response response = client.newCall(request).execute()) {
                if(!response.isSuccessful()) {
                    throw new Exception("");
                }
                extras.putString("https", response.body().string());
            } catch (Exception ignore) {

            }
            setResultExtras(extras);
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
