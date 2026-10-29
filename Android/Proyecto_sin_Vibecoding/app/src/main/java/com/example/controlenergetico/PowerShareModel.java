package com.example.controlenergetico;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.example.controlenergetico.PowerShareProtocol.*;

/** Máquina de estado de la app. Todos los accesos se realizan en el hilo principal. */
public final class PowerShareModel {
    public static final long FRESH_MS = 3000, TIMEOUT_MS = 10000;
    public enum Phase { WAITING, UNCERTAIN, APPLIED, REJECTED }
    public static final class Operation {
        public final String id, payload, bootId;
        public final Action action;
        public final long startedAt;
        public Phase phase;
        public String reason, source;
        public boolean manuallyClosed;
        Operation(String id, String payload, Action action, String bootId, long startedAt) {
            this.id = id; this.payload = payload; this.action = action; this.bootId = bootId;
            this.startedAt = startedAt; phase = Phase.WAITING;
            reason = "esperando_ack"; source = "android";
        }
        public boolean terminal() { return phase == Phase.APPLIED || phase == Phase.REJECTED; }
        public String describe() {
            return action.label + " · " + id + "\n" + phase + " · " + reason + " · " + source
                    + (manuallyClosed ? "\nRevisión manual cerrada; sin reenvío" : "");
        }
    }

    public Telemetry telemetry;
    public boolean connected, subscribed, online, foreground, storageHealthy = true;
    public String connectionText = "Broker: desconectado";
    public String notice = "Sin mensajes";
    public String lastEvent = "Sin eventos";
    private long receivedAt = -1, freshnessFloor = -1;
    private boolean eligibleTelemetry;
    private final List<Operation> operations = new ArrayList<>();
    private String lastUpdatedId;

    public List<Operation> operations() { return Collections.unmodifiableList(operations); }
    public Operation active() {
        for (Operation op : operations) if (!op.terminal() && !op.manuallyClosed) return op;
        return null;
    }
    public Operation lastUpdated() {
        for (Operation op : operations) if (op.id.equals(lastUpdatedId)) return op;
        return operations.isEmpty() ? null : operations.get(operations.size() - 1);
    }
    public void invalidate(long now) { freshnessFloor = now; eligibleTelemetry = false; }
    public void foreground(boolean value, long now) {
        if (foreground != value) { foreground = value; invalidate(now); }
    }
    public void connecting(long now) {
        connected = subscribed = online = false;
        invalidate(now); connectionText = "Broker: conectando…";
    }
    public void brokerConnected() { connected = true; connectionText = "Broker: conectado; suscribiendo…"; }
    public void subscriptionComplete(long now) {
        subscribed = true; invalidate(now); connectionText = "Broker: conectado y suscrito";
    }
    public boolean disconnected(long now) {
        connected = subscribed = online = false;
        invalidate(now); connectionText = "Broker: desconectado";
        return uncertain("connection_lost");
    }
    public boolean fresh(long now) {
        return foreground && eligibleTelemetry && receivedAt >= 0 && now >= receivedAt
                && now - receivedAt <= FRESH_MS;
    }
    public boolean available(long now) { return connected && subscribed && online && fresh(now); }
    public boolean can(Action action, long now) {
        if (!storageHealthy || !available(now) || active() != null) return false;
        switch (action) {
            case RECHARGE: return true;
            case AUTHORIZE: return telemetry.state == State.DISPONIBLE && telemetry.balanceWh > 0;
            case FINISH: return telemetry.state == State.ACTIVO || telemetry.state == State.ENFRIAMIENTO;
            case ENABLE: return telemetry.state == State.DESHABILITADO;
            case DISABLE: return telemetry.state != State.DESHABILITADO;
            default: return false;
        }
    }
    public boolean receiveTelemetry(String payload, long arrival) throws JSONException {
        Telemetry next = PowerShareProtocol.telemetry(payload); // Parsear antes de modificar la instantánea.
        Operation pending = active();
        boolean reboot = (telemetry != null && !telemetry.bootId.equals(next.bootId))
                || (pending != null && !pending.bootId.equals(next.bootId));
        boolean changed = reboot && uncertain("device_restarted");
        telemetry = next; receivedAt = arrival;
        eligibleTelemetry = foreground && connected && subscribed && arrival > freshnessFloor;
        return changed;
    }
    public Operation begin(Action action, BigDecimal wh, long now) throws JSONException {
        if (!can(action, now)) throw new IllegalStateException("Acción no disponible: revisá conexión, telemetría y pendientes");
        String id = UUID.randomUUID().toString();
        Operation op = new Operation(id, command(id, action, wh), action, telemetry.bootId, now);
        operations.add(op); lastUpdatedId = id;
        pruneResolved();
        return op;
    }
    // Cancelar solo si falló la persistencia antes de publicar.
    public void cancelUnsent(Operation operation) { operations.remove(operation); }
    public boolean tick(long now) {
        Operation op = active();
        return op != null && op.phase == Phase.WAITING && now - op.startedAt >= TIMEOUT_MS
                && uncertain("local_timeout");
    }
    public boolean uncertain(String reason) {
        Operation op = active();
        if (op == null || op.phase != Phase.WAITING) return false;
        op.phase = Phase.UNCERTAIN; op.reason = reason; op.source = "android";
        lastUpdatedId = op.id;
        return true;
    }
    public boolean receiveAck(String payload) throws JSONException {
        Ack ack = ack(payload);
        for (Operation op : operations) {
            if (!op.id.equals(ack.id) || op.terminal()) continue;
            if (op.phase == Phase.UNCERTAIN && ack.result.equals("unknown")
                    && op.reason.equals(ack.reason) && op.source.equals(ack.source)) return false;
            op.phase = ack.result.equals("applied") ? Phase.APPLIED
                    : ack.result.equals("rejected") ? Phase.REJECTED : Phase.UNCERTAIN;
            op.reason = ack.reason; op.source = ack.source; lastUpdatedId = op.id;
            pruneResolved();
            return true;
        }
        return false; // ACK ajeno, duplicado o menos concluyente que un resultado final.
    }
    public boolean canClose(long now) {
        Operation op = active();
        return storageHealthy && available(now) && op != null && op.phase == Phase.UNCERTAIN;
    }
    public void closeManually(String expectedId, long now) {
        if (!canClose(now) || !active().id.equals(expectedId)) {
            throw new IllegalStateException("El pendiente o la disponibilidad cambiaron; volvé a revisar");
        }
        active().manuallyClosed = true;
    }
    private void pruneResolved() {
        int resolved = 0;
        for (Operation op : operations) if (op.terminal()) resolved++;
        for (int i = 0; i < operations.size() && resolved > 50;) {
            if (operations.get(i).terminal()) { operations.remove(i); resolved--; } else i++;
        }
    }

    public String serialize() throws JSONException {
        JSONArray entries = new JSONArray();
        for (Operation op : operations) {
            entries.put(new JSONObject().put("id", op.id).put("payload", op.payload)
                    .put("action", op.action.name()).put("bootId", op.bootId)
                    .put("phase", op.phase.name()).put("reason", op.reason).put("source", op.source)
                    .put("manuallyClosed", op.manuallyClosed));
        }
        return new JSONObject().put("version", 1).put("operations", entries).toString();
    }
    public void restore(String journal) throws JSONException {
        if (journal == null) return;
        JSONObject root = new JSONObject(journal);
        if (number(root, "version") != 1) throw new IllegalArgumentException("Registro de versión desconocida");
        JSONArray entries = root.getJSONArray("operations");
        List<Operation> restored = new ArrayList<>();
        int blockers = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String id = text(entry, "id");
            Action action = Action.valueOf(text(entry, "action"));
            String payload = text(entry, "payload");
            JSONObject cmd = new JSONObject(payload);
            BigDecimal wh = action == Action.RECHARGE ? new BigDecimal(cmd.get("wh").toString()) : null;
            command(id, action, wh); // Validar sin reconstruir ni alterar el payload guardado.
            if (!id.equals(cmd.getString("id")) || !action.code.equals(cmd.getString("command"))
                    || cmd.length() != (action == Action.RECHARGE ? 3 : 2)) {
                throw new IllegalArgumentException("Comando guardado inválido");
            }
            for (Operation existing : restored) if (existing.id.equals(id)) throw new IllegalArgumentException("ID duplicado");
            Operation op = new Operation(id, payload, action, text(entry, "bootId"), 0);
            op.phase = Phase.valueOf(text(entry, "phase"));
            op.reason = text(entry, "reason"); op.source = text(entry, "source");
            op.manuallyClosed = bool(entry, "manuallyClosed");
            if (op.phase == Phase.WAITING) {
                op.phase = Phase.UNCERTAIN; op.reason = "app_restarted"; op.source = "android";
            }
            if (!op.terminal() && !op.manuallyClosed) blockers++;
            restored.add(op);
        }
        if (blockers > 1) throw new IllegalArgumentException("Más de un pendiente activo");
        operations.clear(); operations.addAll(restored);
        // No se restaura disponibilidad ni se publica ningún comando.
    }
}
