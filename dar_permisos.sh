#!/data/data/com.termux/files/usr/bin/bash
# Da al lanzador permiso para leer Spotify/Kodi, ver tus imagenes y reaccionar al audio.
# Se corre UNA vez, despues de instalar el APK.   Uso:  ./dar_permisos.sh 192.168.1.50
PKG=com.tvlauncher
CN=$PKG/$PKG.MediaListener

if ! command -v adb >/dev/null 2>&1; then
  echo "No encuentro adb en Termux. Instalalo con:  pkg install android-tools"; exit 1
fi
if [ -n "$1" ]; then
  adb connect "$1:5555" >/dev/null 2>&1
  sleep 1
fi
if ! adb devices | grep -w "device" | grep -qv "^List"; then
  echo "Conecta primero el TV:  ./dar_permisos.sh IP_DEL_TV"
  echo "(En el TV activa: Ajustes > Opciones de desarrollador > Depuracion USB / ADB por red)"
  exit 1
fi

# 1) Permiso para que el lanzador se active solo el acceso a la musica
adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS 2>/dev/null && echo "Permiso especial: OK"

# 2) Activar el acceso a notificaciones (lo que lee Spotify/Kodi), sin borrar los que ya tengas
CUR=$(adb shell settings get secure enabled_notification_listeners | tr -d '\r')
case "$CUR" in
  *"$CN"*) echo "Acceso a notificaciones: ya estaba activo." ;;
  ""|null) adb shell settings put secure enabled_notification_listeners "$CN"
           echo "Acceso a notificaciones: activado." ;;
  *)       adb shell settings put secure enabled_notification_listeners "$CUR:$CN"
           echo "Acceso a notificaciones: activado (se conservaron los que ya tenias)." ;;
esac
echo "Estado actual: $(adb shell settings get secure enabled_notification_listeners | tr -d '\r')"

# 3) Imagenes (fondos e iconos) y audio (onda real)
adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE 2>/dev/null && echo "Permiso de imagenes: OK"
adb shell pm grant $PKG android.permission.RECORD_AUDIO 2>/dev/null && echo "Permiso de audio (onda real): OK"

# 4) Reiniciar el lanzador para que tome los cambios
adb shell am force-stop $PKG
adb shell input keyevent KEYCODE_HOME
echo "Listo. Reproduce algo en Spotify y el cuadro aparecera solo."
