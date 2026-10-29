package com.example.controlenergetico;

import org.json.JSONObject;
import org.junit.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.Assert.*;
import static com.example.controlenergetico.PowerShareProtocol.*;

public class PowerShareProtocolTest {
    static final String SAMPLE = "{\"state\":\"DISPONIBLE\",\"balanceWh\":10.0,\"currentA\":0.0,"
            + "\"temperatureC\":25.0,\"temperatureSimulated\":false,\"loadOn\":false,"
            + "\"coolerPwm\":0,\"loadPwm\":0,\"loadPercent\":100,\"uptimeMs\":12345,"
            + "\"bootId\":\"a1b2c3d4e5f60708\"}";

    @Test public void fiveCommandsHaveExactFieldsAndStandardUuid() throws Exception {
        for (Action action : Action.values()) {
            String id = UUID.randomUUID().toString();
            String payload = command(id, action, action == Action.RECHARGE ? recharge("10,25") : null);
            JSONObject json = new JSONObject(payload);
            assertEquals(id, json.getString("id"));
            assertEquals(36, id.length());
            assertEquals(action.code, json.getString("command"));
            assertEquals(action == Action.RECHARGE ? 3 : 2, json.length());
            if (action == Action.RECHARGE) {
                assertTrue(json.get("wh") instanceof Number);
                assertEquals(10.25, json.getDouble("wh"), 0);
            } else assertFalse(json.has("wh"));
            assertTrue(payload.getBytes(StandardCharsets.UTF_8).length <= 256);
        }
    }
    @Test public void rechargeEnforcesExactGatewayLimitAndFinitePositiveNumbers() {
        assertEquals(0, MAX_WH.compareTo(recharge("3.402823466e38")));
        assertEquals(0, new BigDecimal("0.5").compareTo(recharge(" ,5 ")));
        assertEquals(0, recharge("1,25").compareTo(recharge("1.25")));
        for (String invalid : new String[]{"", "0", "-1", "NaN", "Infinity", "1,2.3", "1 000",
                "3.402823467e38", "3.402823466000000001e38", "1e999", "1e-999", "0x1.0p1"}) {
            assertThrows(invalid, IllegalArgumentException.class, () -> recharge(invalid));
        }
    }
    @Test public void commandRejectsInvalidUuidExtrasAndOversizedUtf8() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> command("android-1", Action.AUTHORIZE, null));
        String id = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class, () -> command(id, Action.RECHARGE, null));
        assertThrows(IllegalArgumentException.class, () -> command(id, Action.ENABLE, BigDecimal.ONE));
        checkSize("a".repeat(256));
        assertThrows(IllegalArgumentException.class, () -> checkSize("á".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> command(id, Action.RECHARGE,
                new BigDecimal("0." + "1".repeat(300))));
    }
    @Test public void readsGuideSampleAndOptionalFieldsCanBeAbsent() throws Exception {
        Telemetry full = telemetry(SAMPLE);
        assertEquals(State.DISPONIBLE, full.state);
        assertEquals(10, full.balanceWh, 0);
        assertEquals(Integer.valueOf(100), full.loadPercent);
        assertEquals(Boolean.FALSE, full.temperatureSimulated);
        JSONObject minimal = new JSONObject(SAMPLE);
        minimal.remove("temperatureSimulated"); minimal.remove("loadPwm"); minimal.remove("loadPercent");
        minimal.put("futureField", new JSONObject().put("x", 3));
        Telemetry parsed = telemetry(minimal.toString());
        assertNull(parsed.loadPwm); assertNull(parsed.loadPercent); assertNull(parsed.temperatureSimulated);
    }
    @Test public void missingRequiredFieldsAndWrongTypesAreRejected() throws Exception {
        for (String field : new String[]{"state", "balanceWh", "currentA", "temperatureC", "loadOn",
                "coolerPwm", "uptimeMs", "bootId"}) {
            JSONObject bad = new JSONObject(SAMPLE); bad.remove(field);
            assertThrows(Exception.class, () -> telemetry(bad.toString()));
        }
        for (String field : new String[]{"balanceWh", "currentA", "temperatureC", "uptimeMs", "coolerPwm"}) {
            JSONObject bad = new JSONObject(SAMPLE).put(field, "10");
            assertThrows(Exception.class, () -> telemetry(bad.toString()));
        }
        for (String bad : new String[]{"{", "[]", "null", "{\"state\":\"OTRO\"}"}) {
            assertThrows(Exception.class, () -> telemetry(bad));
        }
        for (Object[] field : new Object[][]{{"loadOn", "false"}, {"balanceWh", -1},
                {"coolerPwm", 256}, {"bootId", ""}, {"loadPercent", 101}, {"loadPwm", 0.5},
                {"temperatureC", JSONObject.NULL}, {"temperatureSimulated", 0}}) {
            JSONObject bad = new JSONObject(SAMPLE).put((String) field[0], field[1]);
            assertThrows(Exception.class, () -> telemetry(bad.toString()));
        }
    }
    @Test public void ackAndEventsAreIndependentMessages() throws Exception {
        Ack parsed = ack("{\"id\":null,\"result\":\"rejected\",\"reason\":\"invalid_format\",\"source\":\"nodered\",\"future\":1}");
        assertNull(parsed.id);
        assertThrows(Exception.class, () -> ack("{\"id\":\"a\",\"result\":\"success\",\"reason\":\"ok\",\"source\":\"esp32\"}"));
        Event event = event("{\"event\":\"TEMPERATURA_ALTA\",\"previousState\":\"ACTIVO\",\"alert\":true}");
        assertTrue(event.alert); assertEquals("TEMPERATURA_ALTA", event.name);
        assertThrows(Exception.class, () -> ack("{\"event\":\"TEMPERATURA_ALTA\",\"alert\":true}"));
    }
}
