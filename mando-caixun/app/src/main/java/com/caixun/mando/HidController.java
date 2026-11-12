package com.caixun.mando;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * El celular se hace pasar por un accesorio Bluetooth (raton + mando + teclado
 * + teclas multimedia) para que el TV lo acepte como si fuera uno de verdad.
 */
@SuppressLint("MissingPermission")
public class HidController {

    public interface Listener {
        void onStatus(String s);
    }

    // IDs de reporte: 1 raton, 2 mando, 3 teclado, 4 teclas de control (vol, home...)
    private static final String DESCRIPTOR =
            // Raton
            "05 01 09 02 A1 01 85 01 09 01 A1 00 05 09 19 01 29 03 15 00 25 01 95 03 75 01 81 02 "
            + "95 01 75 05 81 03 05 01 09 30 09 31 09 38 15 81 25 7F 75 08 95 03 81 06 C0 C0 "
            // Mando de juegos
            + "05 01 09 05 A1 01 85 02 05 09 19 01 29 10 15 00 25 01 75 01 95 10 81 02 "
            + "05 01 09 39 15 01 25 08 35 00 46 3B 01 65 14 75 04 95 01 81 42 65 00 75 04 95 01 81 03 "
            + "05 01 09 30 09 31 09 32 09 35 15 00 26 FF 00 75 08 95 04 81 02 C0 "
            // Teclado
            + "05 01 09 06 A1 01 85 03 05 07 19 E0 29 E7 15 00 25 01 75 01 95 08 81 02 "
            + "95 01 75 08 81 01 95 06 75 08 15 00 25 65 05 07 19 00 29 65 81 00 C0 "
            // Control de consumo (volumen, inicio, atras...)
            + "05 0C 09 01 A1 01 85 04 15 00 26 FF 03 19 00 2A FF 03 75 10 95 01 81 00 C0";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice host;
    private boolean registered = false;
    private Listener listener;

    private static byte[] hex(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    private void say(String s) {
        if (listener != null) listener.onStatus(s);
    }

    public boolean isReady() {
        return hid != null && registered;
    }

    public boolean isConnected() {
        return hid != null && host != null;
    }

    public void init(Context c, Listener l) {
        listener = l;
        if (hid != null) return;
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            say("Este celular no tiene Bluetooth");
            return;
        }
        boolean ok = adapter.getProfileProxy(c, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hid = (BluetoothHidDevice) proxy;
                    register();
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hid = null;
                    host = null;
                    registered = false;
                    say("Bluetooth desconectado");
                }
            }
        }, BluetoothProfile.HID_DEVICE);
        if (!ok) say("Este celular no permite hacerse pasar por mando Bluetooth");
    }

    private void register() {
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "Mando Caixun", "Mando del celular", "Caixun",
                BluetoothHidDevice.SUBCLASS1_COMBO, hex(DESCRIPTOR));
        BluetoothHidDevice.Callback cb = new BluetoothHidDevice.Callback() {
            @Override
            public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
                registered = isRegistered;
                say(isRegistered ? "listo, falta emparejar con el TV" : "no se pudo activar");
            }

            @Override
            public void onConnectionStateChanged(BluetoothDevice device, int state) {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    host = device;
                    String n;
                    try {
                        n = device.getName();
                    } catch (Exception e) {
                        n = null;
                    }
                    say("conectado a " + (n != null ? n : "el TV") + " \u2713");
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (device.equals(host)) host = null;
                    say("desconectado del TV");
                }
            }
        };
        boolean ok = hid.registerApp(sdp, null, null, r -> ui.post(r), cb);
        if (!ok) say("no se pudo registrar el mando");
    }

    public List<BluetoothDevice> bonded() {
        List<BluetoothDevice> l = new ArrayList<>();
        if (adapter == null) return l;
        Set<BluetoothDevice> s = adapter.getBondedDevices();
        if (s != null) l.addAll(s);
        return l;
    }

    public void connect(BluetoothDevice d) {
        if (hid == null || !registered) {
            say("espera un momento y vuelve a intentar");
            return;
        }
        try {
            hid.connect(d);
            say("conectando\u2026");
        } catch (Exception e) {
            say("no se pudo conectar: " + e.getMessage());
        }
    }

    private boolean send(int id, byte[] data) {
        if (hid == null || host == null) return false;
        try {
            return hid.sendReport(host, id, data);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte clamp(int v) {
        return (byte) Math.max(-127, Math.min(127, v));
    }

    // ------------------------------------------------------------- Raton
    public void mouse(int buttons, int dx, int dy, int wheel) {
        send(1, new byte[]{(byte) buttons, clamp(dx), clamp(dy), clamp(wheel)});
    }

    public void click(int buttons) {
        mouse(buttons, 0, 0, 0);
        ui.postDelayed(() -> mouse(0, 0, 0, 0), 40);
    }

    // -------------------------------------------------------------- Mando
    public void gamepad(int buttons, int hat, int x, int y, int z, int rz) {
        send(2, new byte[]{
                (byte) (buttons & 0xFF), (byte) ((buttons >> 8) & 0xFF),
                (byte) (hat & 0x0F),
                (byte) x, (byte) y, (byte) z, (byte) rz});
    }

    // ------------------------------------------------------------ Teclado
    public void key(int usage, int modifiers) {
        send(3, new byte[]{(byte) modifiers, 0, (byte) usage, 0, 0, 0, 0, 0});
        ui.postDelayed(() -> send(3, new byte[8]), 35);
    }

    public void consumer(int usage) {
        send(4, new byte[]{(byte) (usage & 0xFF), (byte) ((usage >> 8) & 0xFF)});
        ui.postDelayed(() -> send(4, new byte[2]), 35);
    }

    /** Escribe un texto (solo letras sin tilde, numeros y signos basicos). Llamar fuera del hilo principal. */
    public void type(String text) {
        for (char c : text.toCharArray()) {
            int usage = 0;
            int mod = 0;
            if (c >= 'a' && c <= 'z') {
                usage = 0x04 + (c - 'a');
            } else if (c >= 'A' && c <= 'Z') {
                usage = 0x04 + (c - 'A');
                mod = 0x02;
            } else if (c >= '1' && c <= '9') {
                usage = 0x1E + (c - '1');
            } else if (c == '0') {
                usage = 0x27;
            } else if (c == ' ') {
                usage = 0x2C;
            } else if (c == '.') {
                usage = 0x37;
            } else if (c == ',') {
                usage = 0x36;
            } else if (c == '-') {
                usage = 0x2D;
            } else if (c == '@') {
                usage = 0x1F;
                mod = 0x02;
            }
            if (usage == 0) continue;
            send(3, new byte[]{(byte) mod, 0, (byte) usage, 0, 0, 0, 0, 0});
            sleep(30);
            send(3, new byte[8]);
            sleep(30);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
