package com.github.uiautomator;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Debug;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;


@SuppressLint("VpnServicePolicy")
public class MyVPNService extends VpnService {
    public static final String ACTION_CONNECT = "ACTION_CONNECT";
    public static final String ACTION_DISCONNECT = "ACTION_DISCONNECT";
    private static final String TAG = "MyVPNService";
    private Thread vpnThread;
    private ParcelFileDescriptor vpnInterface;

    @Override
    public void onCreate() {
        super.onCreate();
        // 等待调试器连接
        Debug.waitForDebugger();  // Service 会在这里暂停等待
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 启动 VPN 连接
        if (intent != null && ACTION_CONNECT.equals(intent.getAction())) {
            String  proxy_url = intent.getStringExtra("proxy");
            String app = intent.getStringExtra("app");
            startVPN(proxy_url, app);
        } else if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            stopVPN();
        }
        return START_STICKY;
    }

    private void startVPN(String proxy, String app)  {
        Log.d(TAG, "startVPN: " + proxy + " " + app);
        // 创建 VPN 接口
        Builder builder = new Builder();
        try {
            URI url = new URI(proxy);
            Log.d(TAG,"a " + url.getUserInfo());
            Log.d(TAG, " " + url.getHost());
            Log.d(TAG, " " + url.getScheme());
            Log.d(TAG, " " + url.getPort());
        } catch (URISyntaxException ignore) {

        }
        // 配置 VPN 参数
        builder.setSession("MyVPN")
                .setMtu(1500)
                .addAddress("10.0.0.2", 24)  // VPN IP 地址
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .addRoute("0.0.0.0", 0);     // 路由所有流量

        // 排除不需要经过 VPN 的应用（可选）
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        if(app != null && !app.isEmpty()) {
            try {
                builder.addAllowedApplication(app);
            } catch (PackageManager.NameNotFoundException ignore) {

            }
        }

        // 建立 VPN 接口
        vpnInterface = builder.establish();

        // 启动 VPN 线程处理数据
        vpnThread = new Thread(new VpnRunnable(vpnInterface));
        vpnThread.start();
    }

    private void stopVPN() {
        if (vpnThread != null) {
            vpnThread.interrupt();
        }
        try {
            if (vpnInterface != null) {
                vpnInterface.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
