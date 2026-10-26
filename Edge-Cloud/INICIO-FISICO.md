# PowerShare: stack para pruebas con el circuito físico

Esta entrega contiene Mosquitto, Node-RED y MySQL. El ESP32 usa el programa del
circuito real, compilado y cargado con Arduino IDE. Debe implementar el contrato
MQTT descrito abajo para comunicarse con estos flujos.

## Archivos a enviar

Conservar esta estructura. No incluir firmware, tests, simulación, documentación
anterior ni generadores de flujos; `flows.json` ya contiene la lógica de Node-RED.

```text
.gitignore
Edge-Cloud/
  INICIO-FISICO.md
  compose.yaml
  setup.ps1
  .env.example
  node-red/
    Dockerfile
    settings.js
    flows.json
    flows_cred.json
  mosquitto/
    mosquitto.conf
    local/.gitignore
  mysql/
    001-schema.sql
```

No enviar `.env`, contraseñas/ACL generadas, respaldos ni volúmenes Docker.
`flows_cred.json` contiene referencias a variables, sin claves reales.

## Levantar el stack en Windows

Requisitos: PowerShell y Docker Desktop iniciado con motor Linux.
Desde la carpeta que contiene `Edge-Cloud`:

```powershell
./Edge-Cloud/setup.ps1
Set-Location Edge-Cloud
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
```

Setup genera `.env` y los usuarios/permisos del broker. No necesita una carpeta
de firmware. En una instalación nueva se cargan los flujos y el esquema MySQL.

- Node-RED: <http://localhost:1880>, accesible desde la PC del stack.
- MQTT: IPv4 LAN de la PC (obtener con `ipconfig`), puerto **1883**.
- MySQL: interno a Docker; historial en `powershare.iot_history`.
- Detener conservando datos: `docker compose down`. **No usar `down -v`**:
  elimina los volúmenes. Un volumen existente no vuelve a cargar flujos/esquema.

## Conectar el firmware propio desde Arduino IDE

PC, ESP32 y celular deben estar en la misma LAN; usar Wi-Fi de 2,4 GHz para el ESP32.
En el sketch real configurar Wi-Fi, host MQTT = IP de la PC, puerto `1883`, usuario
`esp32`, contraseña = `ESP32_PASSWORD` de `.env` y prefijo = `MQTT_PREFIX`
(por defecto `powershare/esp32-01`). No usar `localhost` como host desde el ESP32.
Seleccionar la placa ESP32 y su puerto en Arduino IDE y cargar el sketch.

Si el programa solo recibe comandos por Serial, hay que agregar Wi-Fi/MQTT:
Node-RED no traduce MQTT a Serial. Enlazar las acciones recibidas con la lógica
existente de recarga, autorización, finalización, deshabilitación y habilitación.
La máquina de estados y las mediciones siguen ejecutándose en el ESP32.

### Contrato mínimo MQTT

Todos los topics siguientes llevan delante `powershare/esp32-01/`:

| Topic | Acción del ESP32 | Contenido | QoS / Retain |
|---|---|---|---|
| `device/commands` | Suscribirse | JSON de comando | 1 / No |
| `device/status` | Publicar al conectar | Texto `online`; Last Will `offline` | 1 / Sí |
| `device/telemetry` | Publicar cada segundo | JSON con mediciones/estado | 0 / No |
| `device/ack` | Publicar al resolver cada comando | JSON con id y resultado | 1 / No |
| `device/events` | Opcional: publicar cambios/alertas | JSON con campo `event` | 1 / No |

Telemetría de ejemplo; enviar valores reales con estos nombres y tipos:

```json
{"state":"DISPONIBLE","balanceWh":10,"currentA":0,"temperatureC":25,"loadOn":false,"coolerPwm":0,"uptimeMs":1000,"bootId":"arranque-1"}
```

Estados admitidos: `DISPONIBLE`, `ACTIVO`, `DESHABILITADO`, `ENFRIAMIENTO`.
Los campos de medición, PWM y tiempo son números; `loadOn` es booleano y `bootId`
es un texto identificador del arranque (hasta 32 caracteres para el historial).
Node-RED exige estado `online` y telemetría válida de los últimos **3 segundos**
para reenviar comandos; en caso contrario devuelve `device_offline`.

Comandos: `id` de 1–40 letras ASCII, números, guiones o guiones bajos;
`command` = `c`, `a`, `f`, `d` o `h`. Solo `c` lleva `wh`, numérico y positivo.
No agregar otros campos; tamaño máximo 256 bytes. Confirmación de ejemplo:

```json
{"id":"fisico-c1","result":"applied","reason":"ok","source":"esp32"}
```

Para rechazos usar `result: "rejected"` y un motivo, por ejemplo `no_credit` o
`invalid_state`. Confirmar la aplicación real, no solo la recepción. Node-RED espera
aproximadamente **5 segundos** antes de informar `ack_timeout`, sin reintentar.
El firmware debe evitar aplicar dos veces el mismo id (MQTT puede duplicar mensajes),
rechazar comandos retained y volver a suscribirse/publicar `online` al reconectar.

## Primera prueba con MyMQTT

Conectar a la IP de la PC, puerto `1883`, usuario `mymqtt` y contraseña
`MYMQTT_PASSWORD` de `.env`. Suscribirse a `powershare/esp32-01/app/#` y
`powershare/esp32-01/device/#`. Esperar `online` y telemetría.

Publicar en `powershare/esp32-01/app/commands`, **QoS 1 y Retain desactivado**,
un JSON por vez, esperando la confirmación en `app/ack`:

```json
{"id":"fisico-c1","command":"c","wh":10}
{"id":"fisico-a1","command":"a"}
{"id":"fisico-f1","command":"f"}
{"id":"fisico-d1","command":"d"}
{"id":"fisico-h1","command":"h"}
```

Son recargar, autorizar, finalizar, deshabilitar y habilitar. Usar un id nuevo por
nueva acción. Ante un timeout, revisar el resultado antes de repetir una recarga.
Para probar el ESP32 directamente, publicar en `device/commands` y leer `device/ack`.
Adaptar todos los topics si cambia el prefijo.

En Node-RED, **Consultar ultimos 50 registros** muestra el historial en Debug.
Si no conecta, revisar IP, claves, firewall TCP 1883 y aislamiento de clientes Wi-Fi.
Logs desde `Edge-Cloud`: `docker compose logs --tail 50 nodered mosquitto mysql`.
El broker está configurado para la LAN, sin TLS.

Antes de conectar carga, verificar la calibración de sensores, pines y polaridad
del relé según su montaje. Si conservaron el factor de consumo ×60 del programa
base, cambiarlo a ×1 para medir energía en tiempo real. El historial MySQL registra
mensajes: no restaura el saldo ni el estado del ESP32.
