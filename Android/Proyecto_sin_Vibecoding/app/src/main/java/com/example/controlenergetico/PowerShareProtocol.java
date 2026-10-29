package com.example.controlenergetico;

import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Contrato app/* de INTEGRACION-MQTT.md, referencia 366bec1. Sin dependencias de UI. */
public final class PowerShareProtocol {
    public static final BigDecimal MAX_WH = new BigDecimal("3.402823466e38");
    public enum State { DISPONIBLE, ACTIVO, ENFRIAMIENTO, DESHABILITADO }
    public enum Action {
        RECHARGE("c", "Recargar"), AUTHORIZE("a", "Autorizar"), FINISH("f", "Finalizar"),
        DISABLE("d", "Deshabilitar"), ENABLE("h", "Habilitar");
        public final String code;
        public final String label;
        Action(String code, String label) { this.code = code; this.label = label; }
    }

    public static final class Telemetry {
        public final State state;
        public final double balanceWh, currentA, temperatureC, uptimeMs;
        public final boolean loadOn;
        public final int coolerPwm;
        public final String bootId;
        public final Integer loadPwm, loadPercent;
        public final Boolean temperatureSimulated;
        private Telemetry(JSONObject json) throws JSONException {
            state = State.valueOf(text(json, "state"));
            balanceWh = nonNegative(json, "balanceWh");
            currentA = nonNegative(json, "currentA");
            temperatureC = number(json, "temperatureC");
            loadOn = bool(json, "loadOn");
            coolerPwm = integer(json, "coolerPwm", 255);
            uptimeMs = nonNegative(json, "uptimeMs");
            bootId = text(json, "bootId");
            loadPwm = json.has("loadPwm") ? integer(json, "loadPwm", 255) : null;
            loadPercent = json.has("loadPercent") ? integer(json, "loadPercent", 100) : null;
            temperatureSimulated = json.has("temperatureSimulated")
                    ? bool(json, "temperatureSimulated") : null;
        }
    }

    public static final class Ack {
        public final String id, result, reason, source;
        private Ack(JSONObject json) throws JSONException {
            Object rawId = json.get("id");
            if (rawId != JSONObject.NULL && !(rawId instanceof String)) {
                throw new IllegalArgumentException("id inválido");
            }
            id = rawId == JSONObject.NULL ? null : (String) rawId;
            result = text(json, "result");
            if (!result.equals("applied") && !result.equals("rejected") && !result.equals("unknown")) {
                throw new IllegalArgumentException("Resultado ACK inválido");
            }
            reason = text(json, "reason");
            source = text(json, "source");
        }
    }

    public static final class Event {
        public final String name, previousState;
        public final boolean alert;
        private Event(JSONObject json) throws JSONException {
            name = text(json, "event");
            alert = bool(json, "alert");
            previousState = json.has("previousState") ? text(json, "previousState") : "sin estado anterior";
        }
    }

    public static Telemetry telemetry(String payload) throws JSONException {
        return new Telemetry(new JSONObject(payload));
    }
    public static Ack ack(String payload) throws JSONException { return new Ack(new JSONObject(payload)); }
    public static Event event(String payload) throws JSONException { return new Event(new JSONObject(payload)); }

    public static BigDecimal recharge(String input) {
        String normalized = input.trim().replace(',', '.');
        if (!normalized.matches("[+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
            throw new IllegalArgumentException("Ingresá una cantidad válida de Wh (coma o punto decimal)");
        }
        try {
            BigDecimal value = new BigDecimal(normalized);
            if (value.signum() <= 0 || value.compareTo(MAX_WH) > 0 || value.doubleValue() == 0) {
                throw new IllegalArgumentException("La recarga debe ser mayor que 0 y como máximo 3.402823466e38 Wh");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Recarga fuera del rango admitido");
        }
    }

    public static String command(String id, Action action, BigDecimal wh) throws JSONException {
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("UUID inválido");
        JSONObject json = new JSONObject().put("id", id).put("command", action.code);
        if (action == Action.RECHARGE) {
            if (wh == null) throw new IllegalArgumentException("Falta la recarga");
            recharge(wh.toString());
            json.put("wh", wh.stripTrailingZeros());
        } else if (wh != null) {
            throw new IllegalArgumentException("wh solo se admite para recarga");
        }
        String payload = json.toString();
        checkSize(payload);
        return payload;
    }

    static void checkSize(String payload) {
        if (payload.getBytes(StandardCharsets.UTF_8).length > 256) {
            throw new IllegalArgumentException("El comando supera los 256 bytes UTF-8");
        }
    }

    static String text(JSONObject json, String field) throws JSONException {
        Object value = json.get(field);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException(field + " debe ser texto no vacío");
        }
        return (String) value;
    }
    static double number(JSONObject json, String field) throws JSONException {
        Object value = json.get(field);
        if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue())) {
            throw new IllegalArgumentException(field + " debe ser un número finito");
        }
        return ((Number) value).doubleValue();
    }
    static double nonNegative(JSONObject json, String field) throws JSONException {
        double value = number(json, field);
        if (value < 0) throw new IllegalArgumentException(field + " no puede ser negativo");
        return value;
    }
    static boolean bool(JSONObject json, String field) throws JSONException {
        Object value = json.get(field);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(field + " debe ser booleano");
        return (Boolean) value;
    }
    private static int integer(JSONObject json, String field, int max) throws JSONException {
        double value = nonNegative(json, field);
        if (value > max || value != Math.rint(value)) throw new IllegalArgumentException(field + " fuera de rango");
        return (int) value;
    }
    private PowerShareProtocol() {}
}
