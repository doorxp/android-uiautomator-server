package com.github.uiautomator;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.android.permission.FloatWindowManager;
import com.github.uiautomator.util.MemoryManager;
import com.github.uiautomator.util.Permissons4App;


public class MainActivity extends Activity {
    private final String TAG = "ATXMainActivity";

    private static final int REQUEST_VPN_PERMISSION = 1002;
    private static final int REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = 1001;

    private TextView tvInStorage;
    private TextView textViewIP;

    private FloatView floatView;

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnFinish = findViewById(R.id.btn_finish);
        btnFinish.setOnClickListener(view -> {
            stopService(new Intent(MainActivity.this, Service.class));
            finish();
        });

        Button btnIdentify = findViewById(R.id.btn_identify);
        btnIdentify.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, IdentifyActivity.class);
            Bundle bundle = new Bundle();
            bundle.putString("theme", "RED");
            intent.putExtras(bundle);
            startActivity(intent);
        });

        findViewById(R.id.accessibility).setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        findViewById(R.id.development_settings).setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));

        Intent intent = getIntent();
        boolean isHide = intent.getBooleanExtra("hide", false);
        if (isHide) {
            Log.i(TAG, "launch args hide:true, move to background");
            moveTaskToBack(true);
        }
        textViewIP = findViewById(R.id.ip_address);
        tvInStorage = findViewById(R.id.in_storage);

        String[] permissions = new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS};
        Permissons4App.initPermissions(this, permissions);

        TextView viewVersion = (TextView) findViewById(R.id.app_version);
        viewVersion.setText("version: " + getAppVersionName());
        if(getIntent().getBooleanExtra("proxy", false)
                && getIntent().getBooleanExtra("from_notification", false)) {
            String proxy = getIntent().getStringExtra("proxy");
            String app = getIntent().getStringExtra("app");
            startVpnWithPermission(proxy, app);
        }
    }

    private void startVpnWithPermission(String proxy, String app) {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            // 需要授权，启动系统弹窗
            // 使用一个封装的 Intent 来传递参数
            Intent wrapperIntent = new Intent(this, MainActivity.class);
            wrapperIntent.setAction("ACTION_START_VPN_AFTER_AUTH");
            wrapperIntent.putExtra("proxy", proxy);
            wrapperIntent.putExtra("app", app);
            wrapperIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    REQUEST_VPN_PERMISSION,
                    wrapperIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // 将 pendingIntent 作为 extra 传给 prepareIntent
            prepare.putExtra("extern_intent", pendingIntent);
            startActivityForResult(prepare, REQUEST_VPN_PERMISSION);
        } else {
            // 已授权，直接启动服务
            startVpnService(proxy, app);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                // 授权成功，从 Intent 中获取参数并启动服务
                String proxy = getIntent().getStringExtra("proxy");
                String app = getIntent().getStringExtra("app");
                startVpnService(proxy, app);
            } else {
                Toast.makeText(this, "VPN 授权被拒绝", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startVpnService(String proxy, String app) {
        Intent serviceIntent = new Intent(this, MyVPNService.class);
        serviceIntent.setAction(MyVPNService.ACTION_CONNECT);
        serviceIntent.putExtra("proxy", proxy);
        serviceIntent.putExtra("app", app);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        finish(); // 可选：启动后关闭 Activity
    }

    private String getAppVersionName() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Permissons4App.handleRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    public void requestIgnoreBatteryOptimizations(View view){
        Intent intent = new Intent();
        intent.setAction(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(android.net.Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
    }

    @RequiresApi(api=Build.VERSION_CODES.M)
    private boolean isIgnoringBatteryOptimizations() {
        boolean isIgnoring = false;
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            isIgnoring = powerManager.isIgnoringBatteryOptimizations(getPackageName());
        }
        return isIgnoring;
    }

    private void showToast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    public void showFloatWindow(View view) {
        boolean floatEnabled = FloatWindowManager.getInstance().checkFloatPermission(MainActivity.this);
        if (!floatEnabled) {
            Log.i(TAG, "float permission not checked");
            return;
        }
        if (floatView == null) {
            floatView = new FloatView(MainActivity.this);
        }
        floatView.show();
    }

    public void dismissFloatWindow(View view) {
        if (floatView != null) {
            Log.d(TAG, "remove floatView immediate");
            floatView.hide();
        }
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void onResume() {
        super.onResume();
        tvInStorage.setText(Formatter.formatFileSize(this, MemoryManager.getAvailableInternalMemorySize()) + "/" + Formatter.formatFileSize(this, MemoryManager.getTotalExternalMemorySize()));
        checkNetworkAddress(null);
    }

    public void checkNetworkAddress(View v) {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int ip = wifiManager.getConnectionInfo().getIpAddress();
        String ipStr = (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        textViewIP.setText(ipStr);
        textViewIP.setTextColor(Color.BLUE);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        // must unbind service, otherwise it will leak memory
        // connection no need to set it to null
        Log.i(TAG, "unbind service");
    }
}
