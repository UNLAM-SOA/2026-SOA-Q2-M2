package com.example.controlenergetico;

import android.os.Bundle;
import android.os.Build;
import android.content.pm.PackageManager;
import android.widget.Toast;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.DecimalFormat;
import java.util.EnumMap;

import static com.example.controlenergetico.PowerShareProtocol.*;

/** Pantalla de control y monitoreo de PowerShare. */
public class MainActivity extends AppCompatActivity {
    private PowerShareRepository repository;
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private final ActivityResultLauncher<String> networkPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) repository.connect();
                else Toast.makeText(this, "Para conectar al equipo, permití el acceso a la red local y volvé a tocar Conectar.",
                        Toast.LENGTH_LONG).show();
            });
    private final EnumMap<Action, Button> actions = new EnumMap<>(Action.class);
    private EditText credit;
    private Button connect, disconnect, review;
    private TextView connection, availability, freshness, balance, measurements, result, help, alert;
    private String lastResult;
    private final PowerShareRepository.Observer observer = this::render;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        repository = ((PowerShareApplication) getApplication()).repository();
        PowerShareModel.Operation latest = repository.model.lastUpdated();
        lastResult = latest == null ? null : latest.id + ":" + latest.phase;
        credit = findViewById(R.id.creditoInput);
        connect = findViewById(R.id.conectarBtn);
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
        connect.setOnClickListener(v -> connectWithPermission());
        disconnect.setOnClickListener(v -> repository.disconnect());
        review.setOnClickListener(v -> reviewUncertainty());
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
    @Override protected void onStart() {
        super.onStart();
        repository.foreground(true);
        repository.observe(observer);
    }
    @Override protected void onStop() {
        repository.remove(observer);
        if (!isChangingConfigurations()) repository.foreground(false);
        super.onStop();
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
        if (!model.connected) return "Conectá la app para operar el equipo.";
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
        connect.setVisibility(model.connected ? View.GONE : View.VISIBLE);
        disconnect.setVisibility(model.connected ? View.VISIBLE : View.GONE);
        connect.setEnabled(!repository.connectionBusy);
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
            show(measurements, "Corriente: " + number(t.currentA) + " A\nTemperatura: " + number(t.temperatureC)
                    + " °C\nCarga " + (t.loadOn ? "encendida" : "apagada"));
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
        result.setVisibility(active == null ? View.GONE : View.VISIBLE);
        if (active != null) show(result, operationText(active));
        String resultKey = latest == null ? null : latest.id + ":" + latest.phase;
        if (latest != null && latest.terminal() && !resultKey.equals(lastResult)) {
            Toast.makeText(this, operationText(latest), Toast.LENGTH_LONG).show();
        }
        lastResult = resultKey;
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
