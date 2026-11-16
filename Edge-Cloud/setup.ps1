param([string]$Prefix = 'powershare/esp32-01')
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
if ($Prefix -notmatch '^powershare/[A-Za-z0-9_-]{1,32}$') { throw 'Prefijo invalido: powershare/<id>' }
if (Test-Path .env) {
    $values = @{}
    Get-Content .env | Where-Object { $_ -match '^[A-Z0-9_]+=' } | ForEach-Object {
        $key, $value = $_ -split '=', 2; $values[$key] = $value
    }
    $Prefix = $values.MQTT_PREFIX
    if ($Prefix -notmatch '^powershare/[A-Za-z0-9_-]{1,32}$') { throw 'Prefijo invalido en .env' }
} else {
    $values = @{ MQTT_PREFIX = $Prefix }
    foreach ($key in @('ESP32_PASSWORD','NODERED_PASSWORD','MYMQTT_PASSWORD','NODE_RED_CREDENTIAL_SECRET','MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD')) {
        $bytes = New-Object byte[] 24
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $rng.GetBytes($bytes); $rng.Dispose()
        $values[$key] = [Convert]::ToBase64String($bytes)
    }
    $values.GetEnumerator() | Sort-Object Name | ForEach-Object { '{0}={1}' -f $_.Key,$_.Value } | Set-Content .env -Encoding ascii
}
# Actualizar instalaciones anteriores sin rotar claves existentes ni borrar volumenes.
foreach ($key in @('MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD')) {
    if (-not $values.ContainsKey($key)) {
        $bytes = New-Object byte[] 24
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $rng.GetBytes($bytes); $rng.Dispose()
        $values[$key] = [Convert]::ToBase64String($bytes)
        Add-Content .env -Value ('{0}={1}' -f $key,$values[$key]) -Encoding ascii
    }
}
foreach ($key in @('ESP32_PASSWORD','NODERED_PASSWORD','MYMQTT_PASSWORD','NODE_RED_CREDENTIAL_SECRET','MYSQL_ROOT_PASSWORD','MYSQL_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace($values[$key]) -or $values[$key] -match '^CHANGE_ME') { throw "Configurar $key en .env" }
}
New-Item -ItemType Directory -Force mosquitto/local | Out-Null
# Las claves no aparecen en argumentos ni en la salida.
$passwordLines = @("esp32:$($values.ESP32_PASSWORD)", "nodered:$($values.NODERED_PASSWORD)", "mymqtt:$($values.MYMQTT_PASSWORD)")
# Generar un archivo portable con LF, tambien en Windows.
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'mosquitto/local/passwords'), ($passwordLines -join [char]10) + [char]10, [System.Text.Encoding]::ASCII)
docker run --rm --entrypoint /bin/sh -v "${PSScriptRoot}/mosquitto/local:/config" eclipse-mosquitto:2.0.22 -ec 'cp /config/passwords /tmp/passwords; chmod 600 /tmp/passwords; mosquitto_passwd -U /tmp/passwords; cp /tmp/passwords /config/passwords'
if ($LASTEXITCODE -ne 0) { throw 'No se pudo generar el archivo de passwords; iniciar Docker Desktop y repetir.' }
@"
user esp32
topic read $Prefix/device/commands
topic write $Prefix/device/telemetry
topic write $Prefix/device/events
topic write $Prefix/device/ack
topic write $Prefix/device/status

user nodered
topic read $Prefix/device/#
topic read $Prefix/app/commands
topic write $Prefix/device/commands
topic write $Prefix/app/telemetry
topic write $Prefix/app/events
topic write $Prefix/app/ack
topic write $Prefix/app/status

user mymqtt
topic read $Prefix/app/#
topic read $Prefix/device/#
topic write $Prefix/app/commands
topic write $Prefix/device/commands
"@ | Set-Content mosquitto/local/acl -Encoding ascii
$firmware = Join-Path $PSScriptRoot '../Embebido/Proyecto_con_Vibecoding'
# La entrega del stack funciona sola; generar la configuracion del simulador
# unicamente cuando su plantilla esta presente en el checkout completo.
if ((Test-Path "$firmware/config.example.h") -and -not (Test-Path "$firmware/config.local.h")) {
    $config = Get-Content "$firmware/config.example.h" -Raw
    $config = $config.Replace('CHANGE_ME_ESP32', $values.ESP32_PASSWORD).Replace('powershare/esp32-01', $Prefix)
    [System.IO.File]::WriteAllText("$firmware/config.local.h", $config)
}
Write-Host 'Configuracion lista. Credenciales: Edge-Cloud/.env (no versionado). Ejecutar docker compose up -d.'

} finally { Pop-Location }
