package com.example.controlenergetico;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.EnumMap;

import static com.example.controlenergetico.PowerShareProtocol.*;

/** Pantalla de control y monitoreo de PowerShare. */
public class MainActivity extends AppCompatActivity implements SensorEventListener {
    private PowerShareRepository repository;
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private final ActivityResultLauncher<String> networkPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) repository.connect();
                else Toast.makeText(this, "Para conectar al equipo, permití el acceso a la red local y volvé a escanear el QR.",
                        Toast.LENGTH_LONG).show();
            });
    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) launchQrScanner();
                else Toast.makeText(this, "Necesitamos permiso de cámara para escanear el QR.",
                        Toast.LENGTH_LONG).show();
            });
    private final ActivityResultLauncher<ScanOptions> qrLauncher = registerForActivityResult(
            new ScanContract(), scanResult -> {
                if (scanResult.getContents() != null) handleQrContent(scanResult.getContents());
            });
    private final EnumMap<Action, Button> actions = new EnumMap<>(Action.class);
    private EditText credit;
    private Button disconnect, review, scanQr;
    private TextView connection, availability, freshness, balance, measurements, result, help, alert;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private long lastShakeMs = 0;
    private Action pendingPwmAction = null;
    private final PowerShareRepository.Observer observer = this::render;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        repository = ((PowerShareApplication) getApplication()).repository();
        credit = findViewById(R.id.creditoInput);
        disconnect = findViewById(R.id.desconectarBtn);
        scanQr = findViewById(R.id.escanearQrBtn);
        review = findViewById(R.id.revisarBtn);
        connection = findViewById(R.id.conexionTxt);
        availability = findViewById(R.id.disponibilidadTxt);
        freshness = findViewById(R.id.vigenciaTxt);
        balance = findViewById(R.id.saldoTxt);
        measurements = findViewById(R.id.medicionesTxt);
        result = findViewById(R.id.comandoTxt);
        help = findViewById(R.id.ayudaTxt);
        alert = findViewById(R.id.alertaTxt);
        bind(Action.RECHARGE, R.id.recargarBtn);
        bind(Action.AUTHORIZE, R.id.enviarAutorizacionBtn);
        bind(Action.FINISH, R.id.enviarFinalizacionBtn);
        bind(Action.DISABLE, R.id.deshabilitarBtn);
        bind(Action.ENABLE, R.id.habilitarBtn);
        bindPowerButton(Action.PWM_50, R.id.cargarCelularBtn);
        bindPowerButton(Action.PWM_100, R.id.cargarComputadoraBtn);
        disconnect.setOnClickListener(v -> repository.disconnect());
        scanQr.setOnClickListener(v -> scanQrWithPermission());
        review.setOnClickListener(v -> reviewUncertainty());
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        if (savedInstanceState == null) {
            handleDeepLink(getIntent());
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return insets;
        });
    }
    private void connectWithPermission() {
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            networkPermission.launch(LOCAL_NETWORK_PERMISSION);
        } else repository.connect();
    }
    private void show(TextView view, String text) {
        // Evitar eventos de accesibilidad y redibujos si solo transcurrió un tick de frescura.
        if (!text.contentEquals(view.getText())) view.setText(text);
    }
    private void bind(Action action, int id) {
        Button button = findViewById(id);
        actions.put(action, button);
        button.setOnClickListener(v -> {
            try {
                repository.send(action, action == Action.RECHARGE
                        ? recharge(credit.getText().toString()) : null);
                credit.setError(null);
            } catch (IllegalArgumentException e) { credit.setError(e.getMessage()); }
        });
    }
    private void bindPowerButton(Action mode, int id) {
        Button button = findViewById(id);
        actions.put(mode, button);
        button.setOnClickListener(v -> selectPowerProfile(mode));
    }
    private void selectPowerProfile(Action mode) {
        Telemetry t = repository.model.telemetry;
        if (t != null && t.state == State.DISPONIBLE && t.balanceWh <= 0) {
            askRechargeBeforeMode(mode);
        } else {
            pendingPwmAction = null;
            repository.send(mode, null);
        }
    }
    private void askRechargeBeforeMode(Action mode) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("Ej: 10");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        String profile = mode == Action.PWM_50 ? "Celular (50%)" : "Computadora (100%)";
        new AlertDialog.Builder(this)
                .setTitle("Sin saldo para " + profile)
                .setMessage("Ingresá cuántos Wh querés cargar antes de configurar este modo:")
                .setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Recargar", (dialog, which) -> {
                    try {
                        BigDecimal wh = recharge(input.getText().toString());
                        pendingPwmAction = mode;
                        repository.send(Action.RECHARGE, wh);
                        if (repository.model.active() == null) pendingPwmAction = null;
                    } catch (IllegalArgumentException e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }
    private void scanQrWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA);
        } else {
            launchQrScanner();
        }
    }
    private void launchQrScanner() {
        ScanOptions options = new ScanOptions();
        options.setPrompt("Escaneá el QR de la maqueta PowerShare");
        options.setBeepEnabled(true);
        options.setOrientationLocked(false);
        qrLauncher.launch(options);
    }
    private void handleQrContent(String content) {
        if (content != null && content.contains("powershare")) {
            if (!repository.model.connected && !repository.connectionBusy) connectWithPermission();
            Toast.makeText(this, "Equipo detectado por QR, conectando...", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "El QR escaneado no corresponde a PowerShare.", Toast.LENGTH_LONG).show();
        }
    }
    private void handleDeepLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data != null && "powershare".equals(data.getScheme()) && "connect".equals(data.getHost())) {
            if (!repository.model.connected && !repository.connectionBusy) connectWithPermission();
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent);
    }
    @Override protected void onStart() {
        super.onStart();
        repository.foreground(true);
        repository.observe(observer);
    }
    @Override protected void onResume() {
        super.onResume();
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }
    @Override protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }
    @Override protected void onStop() {
        repository.remove(observer);
        if (!isChangingConfigurations()) repository.foreground(false);
        super.onStop();
    }
    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        double acceleration = Math.sqrt(x * x + y * y + z * z);

        long nowMs = System.currentTimeMillis();
        if (acceleration > 70.0 && nowMs - lastShakeMs > 1500) {
            lastShakeMs = nowMs;
            if (repository.model.can(Action.FINISH, repository.now())) {
                repository.send(Action.FINISH, null);
                Toast.makeText(this, "Shake detectado: finalizando uso", Toast.LENGTH_SHORT).show();
            }
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No se necesita manejo especial de precision para el acelerometro.
    }
    private String number(double value) {
        boolean scientific = value != 0 && (Math.abs(value) < 0.01 || Math.abs(value) >= 1000000);
        return new DecimalFormat(scientific ? "0.###E0" : "0.##").format(value);
    }
    private String stateName(State state) {
        switch (state) {
            case DISPONIBLE: return "Disponible";
            case ACTIVO: return "En uso";
            case ENFRIAMIENTO: return "En enfriamiento";
            case DESHABILITADO: return "Deshabilitado";
            default: return state.name();
        }
    }
    private String actionName(Action action) {
        switch (action) {
            case RECHARGE: return "Recarga de energía";
            case AUTHORIZE: return "Inicio de uso";
            case FINISH: return "Finalización de uso";
            case DISABLE: return "Deshabilitación del equipo";
            case ENABLE: return "Habilitación del equipo";
            case PWM_50: return "Modo celular (50%)";
            case PWM_100: return "Modo computadora (100%)";
            default: return action.label;
        }
    }
    private String reasonText(String reason) {
        switch (reason) {
            case "no_credit": return "El equipo no tiene energía disponible.";
            case "invalid_state": return "El estado del equipo no permite esta acción.";
            case "device_offline": return "El equipo no está disponible.";
            case "invalid_format": return "El equipo no aceptó el formato de la solicitud.";
            case "retained_command": return "La solicitud se recibió como un mensaje retenido.";
            case "id_conflict": return "El identificador ya corresponde a otra solicitud.";
            case "queue_full": return "El equipo está ocupado. Intentá más tarde.";
            default: return "El equipo no pudo aceptar la solicitud.";
        }
    }
    private String operationText(PowerShareModel.Operation op) {
        String name = actionName(op.action);
        switch (op.phase) {
            case WAITING: return name + ": esperando confirmación del equipo…";
            case APPLIED: return name + ": operación realizada.";
            case REJECTED: return name + ": operación rechazada.\n" + reasonText(op.reason);
            case UNCERTAIN: return name + (op.manuallyClosed
                    ? ": revisión cerrada. El resultado sigue sin confirmar."
                    : ": no se pudo confirmar. Revisá el equipo antes de repetir.");
            default: return name;
        }
    }
    private String actionHelp(PowerShareModel model, long now) {
        if (!model.storageHealthy) return "No se pudo acceder al registro local. Las acciones están bloqueadas.";
        if (repository.connectionBusy) return "Esperá a que termine la conexión.";
        if (!model.connected) return "Escaneá el QR del equipo para conectarte.";
        if (!model.available(now)) return "Esperando datos recientes del equipo para habilitar las acciones.";
        PowerShareModel.Operation pending = model.active();
        if (pending != null) return "";
        if (model.telemetry.state == State.DESHABILITADO) return "Habilitá el equipo para poder iniciar el uso.";
        if (model.telemetry.state == State.DISPONIBLE && model.telemetry.balanceWh <= 0)
            return "Recargá energía para iniciar el uso.";
        if (model.telemetry.state == State.ENFRIAMIENTO) return "La carga sigue encendida durante el enfriamiento.";
        return "";
    }
    private String snapshot(Telemetry t) {
        return "Estado: " + stateName(t.state) + "\nSaldo: " + number(t.balanceWh) + " Wh"
                + "\nCorriente: " + number(t.currentA) + " A\nTemperatura: " + number(t.temperatureC)
                + " °C\nCarga: " + (t.loadOn ? "encendida" : "apagada");
    }
    private void render() {
        PowerShareModel model = repository.model;
        long now = repository.now();
        String connectionLabel;
        if (repository.connectionBusy) connectionLabel = model.connectionText.equals("Broker: desconectado")
                ? "Desconectando…" : "Conectando…";
        else if (!model.connected) connectionLabel = model.connectionText.contains("error")
                ? "No se pudo conectar" : "Sin conexión";
        else connectionLabel = model.available(now) ? "Conectado · equipo online" : "Conectado · esperando al equipo";
        show(connection, connectionLabel);
        scanQr.setVisibility(model.connected ? View.GONE : View.VISIBLE);
        disconnect.setVisibility(model.connected ? View.VISIBLE : View.GONE);
        scanQr.setEnabled(!repository.connectionBusy);
        disconnect.setEnabled(!repository.connectionBusy);
        for (Action action : Action.values()) actions.get(action).setEnabled(model.can(action, now));

        Telemetry t = model.telemetry;
        boolean inUse = t != null && (t.state == State.ACTIVO || t.state == State.ENFRIAMIENTO);
        boolean disabled = t != null && t.state == State.DESHABILITADO;
        actions.get(Action.AUTHORIZE).setVisibility(inUse ? View.GONE : View.VISIBLE);
        actions.get(Action.FINISH).setVisibility(inUse ? View.VISIBLE : View.GONE);
        actions.get(Action.DISABLE).setVisibility(disabled ? View.GONE : View.VISIBLE);
        actions.get(Action.ENABLE).setVisibility(disabled ? View.VISIBLE : View.GONE);
        if (t != null) {
            show(availability, stateName(t.state));
            show(balance, number(t.balanceWh) + " Wh");
            int powerPercent = t.loadPercent != null ? t.loadPercent : 100;
            show(measurements, "Corriente: " + number(t.currentA) + " A\nTemperatura: " + number(t.temperatureC)
                    + " °C\nCarga " + (t.loadOn ? "encendida" : "apagada")
                    + "\nPotencia: " + powerPercent + " %");
        } else {
            availability.setText(R.string.estado_sin_datos);
            balance.setText(R.string.saldo_sin_datos);
            measurements.setText(R.string.mediciones_sin_datos);
        }
        balance.setAlpha(model.available(now) ? 1f : 0.5f);
        show(freshness, model.available(now) ? "Datos actualizados"
                : t == null ? "Esperando mediciones del equipo" : "Última lectura · sin datos recientes del equipo");
        String helpText = actionHelp(model, now);
        show(help, helpText);
        help.setVisibility(helpText.isEmpty() ? View.GONE : View.VISIBLE);

        PowerShareModel.Operation active = model.active();
        PowerShareModel.Operation latest = model.lastUpdated();
        boolean uncertain = active != null && active.phase == PowerShareModel.Phase.UNCERTAIN;
        result.setVisibility(uncertain ? View.VISIBLE : View.GONE);
        if (uncertain) show(result, operationText(active));
        if (pendingPwmAction != null && latest != null && latest.action == Action.RECHARGE) {
            if (latest.phase == PowerShareModel.Phase.APPLIED && model.can(pendingPwmAction, now)) {
                Action next = pendingPwmAction;
                pendingPwmAction = null;
                repository.send(next, null);
                return;
            } else if (latest.phase == PowerShareModel.Phase.REJECTED
                    || latest.phase == PowerShareModel.Phase.UNCERTAIN) {
                pendingPwmAction = null;
            }
        }
        review.setVisibility(active != null && active.phase == PowerShareModel.Phase.UNCERTAIN ? View.VISIBLE : View.GONE);
        review.setEnabled(model.canClose(now));

        boolean hasAlert = model.lastEvent.startsWith("ALERTA:");
        alert.setVisibility(hasAlert ? View.VISIBLE : View.GONE);
        if (hasAlert) {
            String alertText = model.lastEvent.contains("TEMPERATURA_CRITICA") ? "Temperatura crítica"
                    : model.lastEvent.contains("TEMPERATURA_ALTA") ? "Temperatura alta"
                    : model.lastEvent.contains("CREDITO_AGOTADO") ? "Energía agotada" : "Revisá el estado del equipo.";
            show(alert, "Última alerta: " + alertText);
        }

    }
    private void reviewUncertainty() {
        PowerShareModel model = repository.model;
        if (!model.canClose(repository.now())) return;
        PowerShareModel.Operation op = model.active();
        final String expectedId = op.id;
        new AlertDialog.Builder(this)
                .setTitle("Revisar operación")
                .setMessage(actionName(op.action) + "\n\nÚltima lectura:\n" + snapshot(model.telemetry)
                        + "\n\nLa acción pudo aplicarse. Comprobá el saldo y el estado del equipo antes de repetirla. "
                        + "Cerrar este aviso no cancela la acción ni confirma su resultado.")
                .setNegativeButton("Seguir esperando", null)
                .setPositiveButton("Cerrar tras revisión", (dialog, which) -> repository.closeUncertainty(expectedId))
                .show();
    }
}
