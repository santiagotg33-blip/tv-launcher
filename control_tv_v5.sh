#!/data/data/com.termux/files/usr/bin/bash
# Control TV v5: Control / Tactil / Apps / Juegos / IR / Ajustes (Selector de Color Infinito RGB & Todos los Colores)
cd ~/tv-launcher || { echo "No existe ~/tv-launcher"; exit 1; }
[ -d remote/src/main/java/com/tvremote ] || { echo "Primero ejecuta control_tv.sh (no existe el modulo remote)"; exit 1; }

cat > remote/src/main/AndroidManifest.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.BLUETOOTH"/>
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"/>
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"/>
    <uses-permission android:name="android.permission.VIBRATE"/>
    <uses-permission android:name="android.permission.TRANSMIT_IR"/>
    <application android:label="Control TV"
        android:theme="@android:style/Theme.Material.NoActionBar"
        android:allowBackup="false">
        <activity android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait"
            android:windowSoftInputMode="adjustResize|stateHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
    </application>
</manifest>
