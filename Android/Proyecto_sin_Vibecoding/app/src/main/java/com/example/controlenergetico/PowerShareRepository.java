package com.example.controlenergetico;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Repositorio de aplicación; los observadores se registran entre onStart y onStop. */
public final class PowerShareRepository {
    public interface Observer { void changed(); }
    public final PowerShareModel model = new PowerShareModel();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<Observer> observers = new ArrayList<>();
    private final AtomicFile journal;
    private MqttHandler transport;
    private int connectionEpoch;
    public boolean connectionBusy;

    public PowerShareRepository(Context context) {
        journal = new AtomicFile(new File(context.getNoBackupFilesDir(), "powershare-operations.json"));
        try {
            if (journal.getBaseFile().exists()
                    || new File(journal.getBaseFile().getPath() + ".bak").exists()) {
                model.restore(new String(journal.readFully(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            model.storageHealthy = false;
            model.notice = "No se pudo recuperar el registro local. Comandos bloqueados para conservarlo.";
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (model.tick(now())) persist();
                notifyObservers(); // También actualiza la caducidad aunque no lleguen mensajes.
                main.postDelayed(this, 250);
            }
        }, 250);
    }
    public long now() { return SystemClock.elapsedRealtime(); }
    public void observe(Observer observer) { observers.add(observer); observer.changed(); }
    public void remove(Observer observer) { observers.remove(observer); }
    public void foreground(boolean value) { model.foreground(value, now()); notifyObservers(); }
    private void notifyObservers() {
        for (Observer observer : new ArrayList<>(observers)) observer.changed();
    }
    private boolean persist() {
        FileOutputStream output = null;
        try {
            if (!model.storageHealthy) return false;
            byte[] content = model.serialize().getBytes(StandardCharsets.UTF_8);
            output = journal.startWrite(); output.write(content);
            output.flush(); output.getFD().sync(); journal.finishWrite(output);
            return true;
        } catch (Exception e) {
            if (output != null) journal.failWrite(output);
            model.storageHealthy = false;
            model.notice = "No se pudo guardar el registro local. No se permiten más comandos en este proceso.";
            return false;
        }
    }
    public void connect() {
        if (connectionBusy || model.connected) return;
        connectionBusy = true;
        model.connecting(now());
        int epoch = ++connectionEpoch;
        MqttHandler previous = transport;
        MqttHandler next = new MqttHandler(new MqttHandler.Listener() {
            @Override public void brokerConnected() {
                main.post(() -> { if (epoch == connectionEpoch) { model.brokerConnected(); notifyObservers(); } });
            }
            @Override public void subscribed() {
                main.post(() -> {
                    if (epoch != connectionEpoch) return;
                    model.subscriptionComplete(now()); connectionBusy = false; notifyObservers();
                });
            }
            @Override public void lost() {
                main.post(() -> {
                    if (epoch != connectionEpoch) return;
                    if (model.disconnected(now())) persist();
                    connectionBusy = false; ++connectionEpoch; notifyObservers();
                });
            }
            @Override public void message(String topic, String payload) {
                long arrival = now();
                main.post(() -> { if (epoch == connectionEpoch) receive(topic, payload, arrival); });
            }
        });
        transport = next;
        notifyObservers();
        network.execute(() -> {
            if (previous != null) previous.disconnect();
            try { next.connect(); }
            catch (Exception e) {
                next.disconnect();
                main.post(() -> {
                    if (epoch != connectionEpoch) return;
                    if (model.disconnected(now())) persist();
                    model.connectionText = "Broker: error de conexión o suscripción; revisá configuración y red";
                    connectionBusy = false; ++connectionEpoch; notifyObservers();
                });
            }
        });
    }
    public void disconnect() {
        if (connectionBusy) return;
        ++connectionEpoch;
        if (model.disconnected(now())) persist();
        MqttHandler previous = transport; transport = null;
        connectionBusy = true; notifyObservers();
        network.execute(() -> {
            if (previous != null) previous.disconnect();
            main.post(() -> { connectionBusy = false; notifyObservers(); });
        });
    }
    private void receive(String topic, String payload, long arrival) {
        String base = BuildConfig.MQTT_PREFIX + "/app/";
        try {
            if (topic.equals(base + "telemetry")) {
                if (model.receiveTelemetry(payload, arrival)) persist();
            } else if (topic.equals(base + "status")) {
                if (!payload.equals("online") && !payload.equals("offline")) {
                    throw new IllegalArgumentException("Estado de disponibilidad inválido");
                }
                model.online = payload.equals("online");
                if (!model.online) model.invalidate(now());
            } else if (topic.equals(base + "ack")) {
                if (model.receiveAck(payload)) persist();
            } else if (topic.equals(base + "events")) {
                PowerShareProtocol.Event event = PowerShareProtocol.event(payload);
                model.lastEvent = (event.alert ? "ALERTA: " : "Evento: ") + event.name
                        + " · anterior: " + event.previousState;
            }
        } catch (Exception e) {
            model.notice = "Mensaje inválido en " + topic + ": " + e.getMessage();
        }
        notifyObservers();
    }
    public void send(PowerShareProtocol.Action action, BigDecimal wh) {
        try {
            PowerShareModel.Operation operation = model.begin(action, wh, now());
            if (!persist()) { model.cancelUnsent(operation); notifyObservers(); return; }
            // Guardar la operación antes de encolar el envío.
            MqttHandler current = transport;
            network.execute(() -> {
                try { current.publish(operation.payload); }
                catch (Exception e) {
                    main.post(() -> {
                        PowerShareModel.Operation active = model.active();
                        if (active != null && active.id.equals(operation.id)
                                && model.uncertain("publish_unconfirmed")) persist();
                        notifyObservers();
                    });
                }
            });
            notifyObservers();
        } catch (Exception e) { model.notice = e.getMessage(); notifyObservers(); }
    }
    public void closeUncertainty(String expectedId) {
        try { model.closeManually(expectedId, now()); persist(); }
        catch (IllegalStateException e) { model.notice = e.getMessage(); }
        notifyObservers();
    }
}
