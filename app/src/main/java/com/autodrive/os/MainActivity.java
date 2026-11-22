package com.autodrive.os;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final int PERMISSION_REQUEST_CODE = 2002;

    // Standard SPP UUID for ELM327 Bluetooth Classic (v1.5 / v2.1)
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket btSocket;
    private InputStream btIn;
    private OutputStream btOut;
    private volatile boolean isObdConnected = false;
    private Thread pollingThread;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Native High-Accuracy GPS Location Manager
    private LocationManager locationManager;
    private double lastLat = -6.2088; // Default Jakarta fallback before first GPS fix
    private double lastLng = 106.8456;
    private float lastAccuracy = 0f;
    private float lastBearing = 0f;
    private float lastSpeedKmh = 0f;
    private boolean hasGpsFix = false;

    private final LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location loc) {
            lastLat = loc.getLatitude();
            lastLng = loc.getLongitude();
            lastAccuracy = loc.getAccuracy();
            lastBearing = loc.getBearing();
            lastSpeedKmh = loc.hasSpeed() ? (loc.getSpeed() * 3.6f) : 0f;
            hasGpsFix = true;

            String json = String.format(java.util.Locale.US,
                    "{"lat":%.6f,"lng":%.6f,"acc":%.1f,"bearing":%.1f,"gpsSpeed":%.1f}",
                    lastLat, lastLng, lastAccuracy, lastBearing, lastSpeedKmh);
            sendJsEvent("onNativeGpsUpdate", json);
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {}
        @Override
        public void onProviderDisabled(@NonNull String provider) {}
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.addJavascriptInterface(new AndroidObdBridge(), "AndroidOBD");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.startsWith("google.navigation:") || url.startsWith("geo:") || url.startsWith("intent:"))) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                        return true;
                    } catch (Exception ignored) {}
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                String[] mimeTypes = {"audio/*", "video/*"};
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(intent, "Pilih File Musik / Video"), FILE_CHOOSER_REQUEST_CODE);
                return true;
            }
        });

        requestRequiredPermissions();
        startNativeGps();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    private void requestRequiredPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            perms.add(Manifest.permission.BLUETOOTH);
            perms.add(Manifest.permission.BLUETOOTH_ADMIN);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        List<String> needed = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            startNativeGps();
        }
    }

    private void startNativeGps() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null) {
                Location lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (lastKnown == null) {
                    lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
                if (lastKnown != null) {
                    lastLat = lastKnown.getLatitude();
                    lastLng = lastKnown.getLongitude();
                    lastAccuracy = lastKnown.getAccuracy();
                    hasGpsFix = true;
                }
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, gpsListener);
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5.0f, gpsListener);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE && filePathCallback != null) {
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    public class AndroidObdBridge {

        @JavascriptInterface
        public String getLastLocationJson() {
            return String.format(java.util.Locale.US,
                    "{"lat":%.6f,"lng":%.6f,"acc":%.1f,"bearing":%.1f,"gpsSpeed":%.1f,"hasFix":%b}",
                    lastLat, lastLng, lastAccuracy, lastBearing, lastSpeedKmh, hasGpsFix);
        }

        @JavascriptInterface
        public void openTurnByTurnNavigation(String destinationQuery) {
            try {
                Uri gmmIntentUri = Uri.parse("google.navigation:q=" + Uri.encode(destinationQuery) + "&mode=d");
                Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
                mapIntent.setPackage("com.google.android.apps.maps");
                if (mapIntent.resolveActivity(getPackageManager()) != null) {
                    startActivity(mapIntent);
                } else {
                    // Fallback to browser Google Maps directions
                    Uri webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(destinationQuery) + "&travelmode=driving");
                    startActivity(new Intent(Intent.ACTION_VIEW, webUri));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @JavascriptInterface
        public String getPairedDevices() {
            JSONArray arr = new JSONArray();
            try {
                if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                    return arr.toString();
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return arr.toString();
                }
                Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
                for (BluetoothDevice dev : paired) {
                    JSONObject obj = new JSONObject();
                    obj.put("name", dev.getName() != null ? dev.getName() : "Unknown BT");
                    obj.put("address", dev.getAddress());
                    arr.put(obj);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return arr.toString();
        }

        @JavascriptInterface
        public void connectDevice(String macAddress) {
            new Thread(() -> {
                try {
                    disconnectObd();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        sendJsEvent("onObdError", "Izin Bluetooth belum diberikan");
                        return;
                    }
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);
                    btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    bluetoothAdapter.cancelDiscovery();
                    btSocket.connect();
                    btIn = btSocket.getInputStream();
                    btOut = btSocket.getOutputStream();

                    sendAtCommand("ATZ");
                    Thread.sleep(500);
                    sendAtCommand("ATE0");
                    sendAtCommand("ATL0");
                    sendAtCommand("ATS0");
                    sendAtCommand("ATH0");
                    sendAtCommand("ATSP0");

                    isObdConnected = true;
                    sendJsEvent("onObdConnected", device.getName() != null ? device.getName() : macAddress);
                    startObdPolling();
                } catch (Exception e) {
                    isObdConnected = false;
                    sendJsEvent("onObdError", "Gagal konek ELM327: " + e.getMessage());
                }
            }).start();
        }

        @JavascriptInterface
        public void disconnect() {
            disconnectObd();
            sendJsEvent("onObdDisconnected", "Terputus");
        }

        @JavascriptInterface
        public String clearDtcCodes() {
            if (!isObdConnected) return "NOT_CONNECTED";
            try {
                return sendAtCommand("04");
            } catch (Exception e) {
                return "ERR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public String readDtcCodes() {
            if (!isObdConnected) return "";
            try {
                return sendAtCommand("03");
            } catch (Exception e) {
                return "";
            }
        }
    }

    private synchronized String sendAtCommand(String cmd) throws IOException {
        if (btOut == null || btIn == null) return "";
        btOut.write((cmd + "\r").getBytes());
        btOut.flush();
        StringBuilder sb = new StringBuilder();
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 1200) {
            if (btIn.available() > 0) {
                int c = btIn.read();
                if (c == '>') break;
                if (c != '\r' && c != '\n') sb.append((char) c);
            } else {
                try { Thread.sleep(15); } catch (InterruptedException ignored) {}
            }
        }
        return sb.toString().trim();
    }

    private void startObdPolling() {
        pollingThread = new Thread(() -> {
            String[] pids = {"010C", "010D", "0105", "0142", "0111", "0104", "010F", "010B", "012F"};
            while (isObdConnected) {
                for (String pid : pids) {
                    if (!isObdConnected) break;
                    try {
                        String resp = sendAtCommand(pid);
                        if (!resp.isEmpty() && !resp.contains("NO DATA") && !resp.contains("ERROR")) {
                            sendJsEvent("onObdPidData", pid + "|" + resp);
                        }
                        Thread.sleep(45);
                    } catch (Exception e) {
                        isObdConnected = false;
                        sendJsEvent("onObdError", "Koneksi OBD terputus");
                        break;
                    }
                }
            }
        });
        pollingThread.start();
    }

    private void disconnectObd() {
        isObdConnected = false;
        try {
            if (btSocket != null) btSocket.close();
        } catch (IOException ignored) {}
        btSocket = null;
        btIn = null;
        btOut = null;
    }

    private void sendJsEvent(String fnName, String payload) {
        String safe = payload.replace("'", "\\'").replace("\n", " ");
        mainHandler.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript("if(window." + fnName + ") window." + fnName + "('" + safe + "');", null);
            }
        });
    }

    @Override
    protected void onDestroy() {
        disconnectObd();
        if (locationManager != null) {
            locationManager.removeUpdates(gpsListener);
        }
        super.onDestroy();
    }
}
