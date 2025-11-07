package com.github.uiautomator;


import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.net.VpnService;
import android.os.Build;
import android.util.JsonWriter;
import android.util.Log;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Objects;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AdbBroadcastReceiver extends BroadcastReceiver {
    private static final int REQUEST_VPN_PREPARE = 1001;
    private static final int NOTIFICATION_ID = 1;
    private MockLocationProvider mockGPS;
    private MockLocationProvider mockWifi;
    private static final String TAG = "MockGPSReceiver";



    private String checkIP(OkHttpClient client, String url) throws IOException {
        Request request = new Request.Builder().url(url)
                .header("Accept", "text/plain")  // 明确指定接受纯文本
                .header("User-Agent", "curl/7.64.1")  // 模拟 curl 客户端
                .build();
        Response response = client.newCall(request).execute();
        return response.body().string().trim();
    }

private void sendAuthorizationNotification(Context context, Intent originalIntent) {
    // 创建一个 PendingIntent，指向您的 MainActivity 或专门的 AuthActivity
    Intent mainIntent = new Intent(context, MainActivity.class);
    // 将原始 intent 数据传递过去，以便后续使用
    mainIntent.putExtra("from_notification", true);
    mainIntent.putExtra("proxy", true);
    mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    PendingIntent pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_VPN_PREPARE,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
    );

    // 构建通知
    NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        NotificationChannel channel = new NotificationChannel(
                "vpn_auth_channel",
                "VPN Authorization",
                NotificationManager.IMPORTANCE_HIGH
        );
        nm.createNotificationChannel(channel);
    }

    Notification notification = null;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        notification = new Notification.Builder(context, "vpn_auth_channel")
                .setContentTitle("MyVPN 需要授权")
                .setContentText("点击以授权并连接 VPN")
                .setSmallIcon(R.drawable.icon)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .build();
    }

    nm.notify(NOTIFICATION_ID, notification);
}
    @SuppressLint("UnsafeIntentLaunch")
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
            final String url = intent.getStringExtra("url");
            Log.i(TAG, "onReceive: " + url);
            final PendingResult pendingResult = goAsync();
            new Thread(()->{
                String ret = "";
                OkHttpClient client = new OkHttpClient.Builder().build();
                try {
                    String http_ip = checkIP(client, url);
                    ret += "{\"ip\":\"" + http_ip + "\"}";
                } catch (IOException e) {
                    ret += "{\"error\":\"" + e + "\"}";
                }

                pendingResult.setResultData(ret);
                pendingResult.finish();
            }).start();
        }
        else if(action.equals("send.mock")) {
            try {
                mockGPS = new MockLocationProvider(LocationManager.GPS_PROVIDER, context);
                mockWifi = new MockLocationProvider(LocationManager.NETWORK_PROVIDER, context);
                double lat = 0;
                double lon = 0;
                double alt = 0;
                float accurate = 0;
                if(intent.hasExtra("lat")) {
                    lat = Double.parseDouble(Objects.requireNonNull(intent.getStringExtra("lat")));
                }

                if(intent.hasExtra("lon")) {
                    lon = Double.parseDouble(Objects.requireNonNull(intent.getStringExtra("lon")));
                }

                if(intent.hasExtra("alt")) {
                    alt = Double.parseDouble(Objects.requireNonNull(intent.getStringExtra("alt")));
                }

                if(intent.hasExtra("accurate")) {
                    accurate = Float.parseFloat(Objects.requireNonNull(intent.getStringExtra("accurate")));
                }

                Log.i(TAG, String.format("setting mock to Latitude=%f, Longitude=%f Altitude=%f Accuracy=%f", lat, lon, alt, accurate));
                mockGPS.pushLocation(lat, lon, alt, accurate);
                mockWifi.pushLocation(lat, lon, alt, accurate);
            } catch (Exception e) {
                Log.e(TAG, "onReceive: " + e);
            }
        }
        else if(action.equals("proxy_start")) {
            Intent i = new Intent(context, MyVPNService.class);
            i.setAction(MyVPNService.ACTION_CONNECT);
            i.putExtra("proxy", intent.getStringExtra("proxy"));
            i.putExtra("app", intent.getStringExtra("app"));
            Intent prepare = VpnService.prepare(context);
            if (prepare != null) {
                // 用户尚未授权，启动授权流程
                sendAuthorizationNotification(context, prepare);
            } else {
                // 根据 Android 版本选择启动方式
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i);
                } else {
                    context.startService(i);
                }
            }

        }
        else if(action.equals("proxy_stop")) {
            Intent i = new Intent(context, MyVPNService.class);
            i.setAction(MyVPNService.ACTION_DISCONNECT);
            context.startService(intent);
        }
    }
}
