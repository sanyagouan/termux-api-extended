# Termux API Extended

App Android companion que extiende Termux:API con **Bluetooth completo** (Classic + BLE) y **WiFi avanzado** (connect, hotspot, WiFi Direct, monitorización).

## Arquitectura

```
Termux → TCP (localhost:8080) → Termux API Extended (App Android)
                                    ├── WiFi: connect, scan, hotspot, WiFi Direct, config
                                    ├── Bluetooth: scan, pair, connect, audio, transfer
                                    └── BLE: scan, connect, read, write, notify, services
```

## Compilación

### Requisitos
- Android Studio (recomendado) o JDK 17 + Gradle + Android SDK
- minSdk 26 (Android 8.0), targetSdk 34 (Android 14)

### Desde Android Studio
1. Importar el proyecto
2. `Build → Build APK(s)`
3. APK en `app/build/outputs/apk/debug/`

### Desde Termux (experimental)
```bash
pkg install openjdk-21 gradle
# Descargar Android SDK cmdline-tools
export ANDROID_HOME=$HOME/android-sdk
./gradlew assembleDebug
```

### GitHub Actions (CI)
Push a la rama `main` para build automático.

## Instalación

1. Instalar `termux-api-extended.apk` en el dispositivo
2. Abrir la app → conceder todos los permisos
3. Pulsar **Start** → notificación "Running on 127.0.0.1:8080"
4. Copiar los scripts de `scripts/` a `$PREFIX/bin/`
5. Ejecutar: `termux-bluetooth-scan`, `termux-wifi-connect`, etc.

## Comandos disponibles

### WiFi
| Script | Descripción |
|--------|-------------|
| `termux-wifi-connect -s SSID -p PASS` | Conectar a red WiFi |
| `termux-wifi-disconnect` | Desconectar |
| `termux-wifi-forget SSID` | Olvidar red |
| `termux-wifi-scan` | Escanear redes (detallado) |
| `termux-wifi-saved` | Redes guardadas |
| `termux-wifi-info` | Info conexión actual |
| `termux-wifi-config` | IP/DNS/Gateway |
| `termux-wifi-hotspot enable -s SSID -p PASS` | Activar hotspot |
| `termux-wifi-hotspot status` | Estado hotspot |

### Bluetooth Classic
| Script | Descripción |
|--------|-------------|
| `termux-bluetooth-enable on\|off` | Activar/desactivar BT |
| `termux-bluetooth-status` | Estado del adaptador |
| `termux-bluetooth-scan -d 10` | Escanear dispositivos |
| `termux-bluetooth-pair MAC` | Emparejar |
| `termux-bluetooth-unpair MAC` | Desemparejar |
| `termux-bluetooth-list` | Dispositivos emparejados |
| `termux-bluetooth-connect MAC` | Conectar |
| `termux-bluetooth-disconnect MAC` | Desconectar |

### Bluetooth LE
| Script | Descripción |
|--------|-------------|
| `termux-ble-scan -d 10` | Escanear BLE |
| `termux-ble-connect MAC` | Conectar a dispositivo BLE |
| `termux-ble-disconnect MAC` | Desconectar BLE |
| `termux-ble-services MAC` | Listar servicios |
| `termux-ble-read MAC SVC_UUID CHR_UUID` | Leer característica |
| `termux-ble-write MAC SVC_UUID CHR_UUID HEX` | Escribir valor |
| `termux-ble-notify MAC SVC_UUID CHR_UUID on\|off` | Notificaciones |

## Protocolo TCP

```json
// Request
{"id":"req_001","method":"bt.scan","params":{"duration":10}}

// Response
{"id":"req_001","status":"ok","data":{...},"timestamp":...}

// Error
{"id":"req_001","status":"error","error":{"code":"BT_DISABLED","message":"..."}}
```

## Licencia
MIT
