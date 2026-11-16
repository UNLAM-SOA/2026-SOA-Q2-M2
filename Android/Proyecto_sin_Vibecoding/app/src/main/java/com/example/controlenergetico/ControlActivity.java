package com.example.controlenergetico;

import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.EnumMap;

import static com.example.controlenergetico.PowerShareProtocol.*;

/** Panel de control: mediciones, recarga, perfiles de carga, acciones y sensor de movimiento (shake). */
public class ControlActivity extends AppCompatActivity implements SensorEventListener {
    // Módulo de la aceleración con la gravedad incluida (en reposo vale unos 9,8 m/s²).
    private static final float SHAKE_THRESHOLD_MS2 = 25.0f;
    // Un golpe (apoyar el celular en la mesa) da un solo pico; agitar da varios seguidos.
    private static final int SHAKE_MIN_PEAKS = 3;
    private static final long SHAKE_WINDOW_MS = 1000;  // Plazo para juntar los picos.
    private static final long SHAKE_PEAK_GAP_MS = 100; // Ignora el rebote de un mismo golpe.
    private static final long SHAKE_COOLDOWN_MS = 1500;
    private static final String PREFERENCES = "powershare-ui", PREF_SHAKE = "shake_enabled";
    private PowerShareRepository repository;
    private final EnumMap<Action, Button> actions = new EnumMap<>(Action.class);
    private EditText credit;
    private Button disconnect, review;
    private TextView connection, availability, freshness, balance, measurements, result, help, alert;
    private MaterialSwitch shakeSwitch;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private boolean resumed;
    private long lastShakeMs = 0, firstPeakMs = 0, lastPeakMs = 0;
    private int shakePeaks = 0;
    private boolean aboveShakeThreshold;
    private Action pendingPwmAction = null;
    private final PowerShareRepository.Observer observer = this::render;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_control);
        repository = ((PowerShareApplication) getApplication()).repository();
        credit = findViewById(R.id.creditoInput);
        disconnect = findViewById(R.id.desconectarBtn);
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
        review.setOnClickListener(v -> reviewUncertainty());
        setUpShakeSensor();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.panel), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return insets;
        });
    }
    private void setUpShakeSensor() {
        shakeSwitch = findViewById(R.id.shakeSwitch);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        if (accelerometer == null) {
            shakeSwitch.setChecked(false);
            shakeSwitch.setEnabled(false);
            ((TextView) findViewById(R.id.shakeAyudaTxt)).setText(R.string.shake_sin_sensor);
            return;
        }
        SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        shakeSwitch.setChecked(preferences.getBoolean(PREF_SHAKE, true));
        shakeSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(PREF_SHAKE, checked).apply();
            updateShakeListener();
        });
    }
    /** El acelerómetro solo se escucha con la pantalla en primer plano y el switch activado. */
    private void updateShakeListener() {
        if (accelerometer == null) return;
        sensorManager.unregisterListener(this);
        if (resumed && shakeSwitch.isChecked()) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
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
    @Override protected void onStart() {
        super.onStart();
        repository.foreground(true);
        repository.observe(observer);
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        updateShakeListener();
    }
    @Override protected void onPause() {
        resumed = false;
        updateShakeListener();
        super.onPause();
    }
    @Override protected void onStop() {
        repository.remove(observer);
        repository.foreground(false);
        super.onStop();
    }
    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        double acceleration = Math.sqrt(x * x + y * y + z * z);

        long now = repository.now();
        // Un pico es el cruce del umbral hacia arriba, separado del pico anterior.
        boolean above = acceleration >= SHAKE_THRESHOLD_MS2;
        boolean newPeak = above && !aboveShakeThreshold && now - lastPeakMs >= SHAKE_PEAK_GAP_MS;
        aboveShakeThreshold = above;
        if (!newPeak) return;
        lastPeakMs = now;
        if (now - lastShakeMs < SHAKE_COOLDOWN_MS) {
            // Sigue el mismo movimiento: no repetir la acción hasta que el celular se quede quieto.
            lastShakeMs = now;
            shakePeaks = 0;
            return;
        }
        if (shakePeaks == 0 || now - firstPeakMs > SHAKE_WINDOW_MS) {
            shakePeaks = 0;
            firstPeakMs = now;
        }
        if (++shakePeaks < SHAKE_MIN_PEAKS) return;
        shakePeaks = 0;
        lastShakeMs = now;
        String message;
        if (repository.model.can(Action.FINISH, now)) {
            repository.send(Action.FINISH, null);
            message = "Movimiento detectado: finalizando el uso";
        } else if (repository.model.can(Action.AUTHORIZE, now)) {
            repository.send(Action.AUTHORIZE, null);
            message = "Movimiento detectado: iniciando el uso";
        } else {
            message = "Movimiento detectado, pero ahora no se puede iniciar ni finalizar el uso";
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
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
        if (!model.connected) return "Sin conexión con el equipo. Reintentando…";
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
    /**
     * El panel solo tiene sentido con el equipo vinculado. Se cierra al desconectar o si la
     * conexión falló; durante una reconexión automática por un microcorte permanece abierto.
     */
    private boolean closeIfUnlinked() {
        if (repository.wantsConnection() && !repository.connectionFailed()) return false;
        if (!isFinishing()) finish(); // Vuelve a la pantalla de inicio, que queda debajo.
        return true;
    }
    private void render() {
        if (closeIfUnlinked()) return;
        PowerShareModel model = repository.model;
        long now = repository.now();
        show(connection, repository.connectionLabel());
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
        review.setVisibility(uncertain ? View.VISIBLE : View.GONE);
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
