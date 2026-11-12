#!/data/data/com.termux/files/usr/bin/bash
# Da al lanzador permiso para leer Spotify/Kodi, ver tus imagenes y reaccionar al audio.
PKG=com.tvlauncher
CN=$PKG/$PKG.MediaListener

if ! command -v adb >/dev/null 2>&1; then
  echo "No encuentro adb en Termux."; exit 1
fi
if ! adb devices | grep -qw "device"; then
  echo "Conecta primero el TV:  adb connect IP_DEL_TV:5555"; exit 1
fi

CUR=$(adb shell settings get secure enabled_notification_listeners | tr -d '\r')
case "$CUR" in
  *"$CN"*) echo "Acceso a notificaciones: ya estaba activo." ;;
  ""|null) adb shell settings put secure enabled_notification_listeners "$CN"
           echo "Acceso a notificaciones: activado." ;;
  *)       adb shell settings put secure enabled_notification_listeners "$CUR:$CN"
           echo "Acceso a notificaciones: activado (se conservaron los que ya tenias)." ;;
esac

adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE 2>/dev/null && echo "Permiso de imagenes: OK"
adb shell pm grant $PKG android.permission.RECORD_AUDIO 2>/dev/null && echo "Permiso de audio (onda real): OK"
echo "Listo. Abre el lanzador (boton Inicio) y reproduce algo en Spotify."
