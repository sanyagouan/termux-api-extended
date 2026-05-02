# Termux:API Extended — Blueprint v1.0

## 🎯 Objetivo
App Android companion que extiende las capacidades de Termux:API con Bluetooth completo y WiFi avanzado, comunicándose con Termux vía TCP local (localhost).

## 🏗️ Arquitectura

### ¿Por qué no un fork directo de Termux:API?
Termux:API usa `android:sharedUserId="com.termux"`, lo que requiere firmar con la misma clave que Termux. No tenemos acceso a esa clave, por lo que un fork con el mismo package name no se puede instalar. La solución: app companion independiente con TCP server.

### Flujo de comunicación
```
Termux (shell script) → TCP socket (localhost:8080) → TermuxAPI Extended (Java/Android)
                                                          ↓
                                                    Android APIs
                                                          ↓
                                                    JSON response → Termux
```

### Componentes
1. **TermuxAPI Extended** — App Android (Java, minSdk 26, targetSdk 34)
   - TCP Server en `localhost:8080`
   - JSON command protocol
   - Módulos: WiFi, Bluetooth, BLE

2. **Shell scripts** — CLI en Termux
   - `termux-wifi-connect`, `termux-wifi-hotspot`, etc.
   - `termux-bluetooth-*`, `termux-ble-*`
   - Cada script envía JSON al TCP server y parsea la respuesta

3. **termux-api-ext** — Script wrapper/dispatcher (instalable via pip/pkg)

## 📋 Módulos WiFi

### WiFiConnectAPI
- `wifi.connect` — Conectar a red por SSID + password (WPA2/WPA3)
- `wifi.disconnect` — Desconectar de red actual
- `wifi.forget` — Olvidar red guardada
- `wifi.reassociate` — Forzar reconexión a red actual

### WifiNetworksAPI
- `wifi.saved` — Listar redes guardadas
- `wifi.available` — Listar redes visibles (mejorado con más datos)
- `wifi.signal` — Monitorizar señal en tiempo real (streaming)
- `wifi.config` — Obtener configuración IP/DNS/proxy de red actual

### WifiHotspotAPI
- `hotspot.enable` — Activar hotspot con SSID/password/banda
- `hotspot.disable` — Desactivar hotspot
- `hotspot.status` — Estado del hotspot + clientes conectados
- `hotspot.clients` — Listar clientes conectados al hotspot

### WifiDirectAPI
- `wifidirect.discover` — Descubrir peers WiFi Direct
- `wifidirect.connect` — Conectar a peer
- `wifidirect.info` — Info de conexión WiFi Direct

## 📋 Módulos Bluetooth

### BluetoothAdapterAPI
- `bt.enable` — Activar Bluetooth
- `bt.disable` — Desactivar Bluetooth
- `bt.status` — Estado del adaptador (nombre, dirección, estado)
- `bt.discoverable` — Modo discoverable (duración configurable)
- `bt.scan` — Escanear dispositivos cercanos (con RSSI, tipo, nombre)

### BluetoothPairingAPI
- `bt.pair` — Emparejar con dispositivo (dirección MAC)
- `bt.unpair` — Desemparejar dispositivo
- `bt.paired` — Listar dispositivos emparejados
- `bt.bondstate` — Estado del bonding de un dispositivo

### BluetoothConnectionAPI
- `bt.connect` — Conectar a dispositivo emparejado (por perfil)
- `bt.disconnect` — Desconectar dispositivo
- `bt.connected` — Listar dispositivos conectados
- `bt.profiles` — Perfiles soportados (A2DP, HFP, SPP, etc.)
- `bt.profile.connect` — Conectar perfil específico

### BluetoothAudioAPI
- `bt.audio.a2dp` — Info y control de audio (play, pause, skip)
- `bt.audio.hfp` — Info de manos libres
- `bt.audio.devices` — Dispositivos audio disponibles

### BluetoothTransferAPI
- `bt.send.file` — Enviar archivo via OBEX OPP
- `bt.receive.status` — Estado de recepción

### BluetoothLEAPI
- `ble.scan` — Escanear dispositivos BLE (con RSSI, advertising data)
- `ble.scan.filter` — Escanear por UUID de servicio
- `ble.connect` — Conectar a dispositivo BLE
- `ble.disconnect` — Desconectar BLE
- `ble.services` — Descubrir servicios de dispositivo
- `ble.characteristics` — Leer características de servicio
- `ble.read` — Leer valor de característica
- `ble.write` — Escribir valor en característica
- `ble.notify` — Suscribir a notificaciones
- `ble.rssi` — RSSI en tiempo real
- `ble.mtu` — Negociar MTU

## 🔌 Protocolo TCP

### Request (Termux → App)
```json
{
  "id": "req_001",
  "method": "bt.scan",
  "params": {
    "duration": 10,
    "filter": {}
  }
}
```

### Response (App → Termux)
```json
{
  "id": "req_001",
  "status": "ok",
  "data": { ... },
  "timestamp": 1714567890123
}
```

### Error Response
```json
{
  "id": "req_001",
  "status": "error",
  "error": {
    "code": "BT_PERMISSION_DENIED",
    "message": "BLUETOOTH_SCAN permission not granted"
  },
  "timestamp": 1714567890123
}
```

### Streaming (WiFi signal, BLE notifications)
```
→ {"id":"req_stream","method":"wifi.signal","params":{"interval":1000}}
← {"id":"req_stream","status":"stream","data":{"rssi":-55,"speed":65},"seq":1}
← {"id":"req_stream","status":"done","data":{"samples":3600},"seq":3600}
```

## ⚡ Orden de Implementación
1. Core (TCP Server, Command Dispatcher, JSON protocol)
2. WiFi Connect
3. Bluetooth Scan + Pair
4. Bluetooth Connect
5. WiFi Hotspot
6. BLE Scan + Services
7. BLE Read/Write
8. WiFi Direct
9. Bluetooth Audio
10. Bluetooth Transfer
11. WiFi Signal Monitor
