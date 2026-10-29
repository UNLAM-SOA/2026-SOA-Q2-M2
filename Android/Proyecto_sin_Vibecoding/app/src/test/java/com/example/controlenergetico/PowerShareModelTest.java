package com.example.controlenergetico;

import org.json.JSONObject;
import org.junit.Test;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.Assert.*;
import static com.example.controlenergetico.PowerShareProtocol.*;
import static com.example.controlenergetico.PowerShareModel.*;

public class PowerShareModelTest {
    private PowerShareModel ready(String state, double balance) throws Exception {
        PowerShareModel m = new PowerShareModel();
        m.foreground(true, 0); m.connecting(0); m.brokerConnected(); m.subscriptionComplete(0);
        m.online = true; m.receiveTelemetry(sample(state, balance, "boot-1"), 1);
        return m;
    }
    private String sample(String state, double balance, String boot) throws Exception {
        return new JSONObject(PowerShareProtocolTest.SAMPLE).put("state", state)
                .put("balanceWh", balance).put("bootId", boot).toString();
    }
    private String ackFor(String id, String result) throws Exception {
        return new JSONObject().put("id", id == null ? JSONObject.NULL : id).put("result", result)
                .put("reason", result.equals("unknown") ? "ack_timeout" : result.equals("applied") ? "ok" : "invalid_state")
                .put("source", result.equals("unknown") ? "nodered" : "esp32").toString();
    }
    @Test public void permissionsMatchEveryFirmwareState() throws Exception {
        for (State state : State.values()) {
            PowerShareModel m = ready(state.name(), 10);
            assertTrue(m.can(Action.RECHARGE, 1));
            assertEquals(state == State.DISPONIBLE, m.can(Action.AUTHORIZE, 1));
            assertEquals(state == State.ACTIVO || state == State.ENFRIAMIENTO, m.can(Action.FINISH, 1));
            assertEquals(state == State.DESHABILITADO, m.can(Action.ENABLE, 1));
            assertEquals(state != State.DESHABILITADO, m.can(Action.DISABLE, 1));
        }
        assertFalse(ready("DISPONIBLE", 0).can(Action.AUTHORIZE, 1));
    }
    @Test public void retainedOnlineAndBrokerConnectionAreInsufficient() throws Exception {
        PowerShareModel m = new PowerShareModel();
        m.foreground(true, 0); m.brokerConnected(); m.online = true;
        assertFalse(m.can(Action.RECHARGE, 1));
        m.subscriptionComplete(1);
        assertFalse(m.can(Action.RECHARGE, 2));
        m.receiveTelemetry(sample("DISPONIBLE", 10, "boot-1"), 2);
        assertTrue(m.can(Action.RECHARGE, 2));
        m.online = false; assertFalse(m.can(Action.RECHARGE, 2));
        m.online = true; m.subscribed = false; assertFalse(m.can(Action.RECHARGE, 2));
    }
    @Test public void freshnessExpiresAfterThreeSecondsAndInvalidDataDoesNotRenewIt() throws Exception {
        PowerShareModel m = ready("DISPONIBLE", 10);
        assertTrue(m.can(Action.RECHARGE, 3001));
        assertFalse(m.can(Action.RECHARGE, 3002));
        Telemetry before = m.telemetry;
        assertThrows(Exception.class, () -> m.receiveTelemetry("{\"state\":\"ACTIVO\"}", 3000));
        assertSame(before, m.telemetry);
        assertFalse(m.fresh(3002));
    }
    @Test public void reconnectAndReturnFromBackgroundRequireNewTelemetry() throws Exception {
        PowerShareModel m = ready("DISPONIBLE", 10);
        m.foreground(false, 2);
        m.receiveTelemetry(sample("DISPONIBLE", 10, "boot-1"), 3);
        m.foreground(true, 4);
        assertFalse(m.can(Action.RECHARGE, 4));
        m.receiveTelemetry(sample("DISPONIBLE", 10, "boot-1"), 3); // Callback encolado antes de volver.
        assertFalse(m.can(Action.RECHARGE, 4));
        m.receiveTelemetry(sample("DISPONIBLE", 10, "boot-1"), 5);
        assertTrue(m.can(Action.RECHARGE, 5));
        m.disconnected(6); m.connecting(7); m.brokerConnected(); m.online = true; m.subscriptionComplete(7);
        assertFalse(m.can(Action.RECHARGE, 7));
        m.receiveTelemetry(sample("DISPONIBLE", 10, "boot-1"), 8);
        assertTrue(m.can(Action.RECHARGE, 8));
    }
    @Test public void observingAgainDuringRotationDoesNotInvalidateDataOrPending() throws Exception {
        PowerShareModel m = ready("DISPONIBLE", 10);
        Operation op = m.begin(Action.RECHARGE, BigDecimal.TEN, 2);
        m.foreground(true, 3); // onStart de la Activity recreada, sin salida real a segundo plano.
        assertTrue(m.fresh(3)); assertSame(op, m.active());
        assertTrue(m.connected); assertTrue(m.subscribed);
    }
    @Test public void onePendingAndSendingNeverChangesDeviceSnapshot() throws Exception {
        PowerShareModel m = ready("DISPONIBLE", 10);
        Telemetry original = m.telemetry;
        m.begin(Action.RECHARGE, BigDecimal.TEN, 2);
        for (Action action : Action.values()) assertFalse(m.can(action, 2));
        assertThrows(IllegalStateException.class, () -> m.begin(Action.AUTHORIZE, null, 2));
        assertSame(original, m.telemetry);
        assertEquals(10, m.telemetry.balanceWh, 0);
        assertEquals(State.DISPONIBLE, m.telemetry.state);
    }
    @Test public void appliedRejectedForeignDuplicateAndNullAcks() throws Exception {
        for (String result : new String[]{"applied", "rejected"}) {
            PowerShareModel m = ready("DISPONIBLE", 10);
            Operation op = m.begin(Action.RECHARGE, BigDecimal.TEN, 2);
            assertFalse(m.receiveAck(ackFor(UUID.randomUUID().toString(), result)));
            assertFalse(m.receiveAck(ackFor(null, "rejected")));
            assertSame(op, m.active());
            assertTrue(m.receiveAck(ackFor(op.id, result)));
            assertNull(m.active());
            assertEquals(result.equals("applied") ? Phase.APPLIED : Phase.REJECTED, op.phase);
            assertEquals("esp32", op.source);
            assertFalse(m.receiveAck(ackFor(op.id, result)));
            assertFalse(m.receiveAck(ackFor(op.id, "unknown")));
            assertEquals(10, m.telemetry.balanceWh, 0);
        }
    }
    @Test public void unknownAndLocalTimeoutRemainUncertainUntilLateAck() throws Exception {
        PowerShareModel m = ready("DISPONIBLE", 10);
        Operation op = m.begin(Action.RECHARGE, BigDecimal.TEN, 2);
        assertFalse(m.tick(10001)); assertTrue(m.tick(10002));
        assertEquals(Phase.UNCERTAIN, op.phase); assertEquals("local_timeout", op.reason);
        assertFalse(m.tick(20002));
        assertTrue(m.receiveAck(ackFor(op.id, "unknown")));
        assertEquals("ack_timeout", op.reason); assertEquals("nodered", op.source);
        assertFalse(m.receiveAck(ackFor(op.id, "unknown")));
        assertTrue(m.receiveAck(ackFor(op.id, "applied")));
        assertNull(m.active()); assertEquals(1, m.operations().size());
    }
    @Test public void rebootOrDisconnectPreservesOriginalIdAndPayload() throws Exception {
        for (boolean reboot : new boolean[]{false, true}) {
            PowerShareModel m = ready("DISPONIBLE", 10);
            Operation op = m.begin(Action.RECHARGE, BigDecimal.TEN, 2);
            String payload = op.payload;
            if (reboot) m.receiveTelemetry(sample("DISPONIBLE", 0, "boot-2"), 3);
            else m.disconnected(3);
            assertEquals(Phase.UNCERTAIN, op.phase);
            assertSame(op, m.active()); assertEquals(payload, op.payload);
            assertEquals(1, m.operations().size());
            assertFalse(m.can(Action.RECHARGE, 3));
        }
    }
    @Test public void manualReviewRequiresFreshAvailabilityAndChecksTheSameId() throws Exception {
        PowerShareModel m = ready("DISPONIBLE", 10);
        Operation op = m.begin(Action.RECHARGE, BigDecimal.TEN, 2);
        assertFalse(m.canClose(2));
        m.uncertain("connection_lost");
        assertFalse(m.canClose(3002));
        m.receiveTelemetry(sample("DISPONIBLE", 20, "boot-1"), 3003);
        m.online = false; assertFalse(m.canClose(3003)); m.online = true;
        assertTrue(m.canClose(3003));
        assertThrows(IllegalStateException.class, () -> m.closeManually("other-id", 3003));
        String payload = op.payload;
        m.closeManually(op.id, 3003);
        assertNull(m.active()); assertTrue(op.manuallyClosed);
        assertEquals(payload, op.payload); assertEquals(1, m.operations().size());
        Operation newer = m.begin(Action.RECHARGE, BigDecimal.ONE, 3004);
        assertTrue(m.receiveAck(ackFor(op.id, "applied")));
        assertSame(newer, m.active()); assertEquals(Phase.APPLIED, op.phase);
        assertThrows(IllegalStateException.class, () -> m.closeManually(op.id, 3004));
    }
    @Test public void processRestartRecoversUncertainIdsWithoutAvailabilityOrNewOperations() throws Exception {
        PowerShareModel original = ready("DISPONIBLE", 10);
        Operation sent = original.begin(Action.RECHARGE, BigDecimal.TEN, 2);
        PowerShareModel restored = new PowerShareModel();
        restored.restore(original.serialize());
        assertEquals(sent.id, restored.active().id);
        assertEquals(sent.payload, restored.active().payload);
        assertEquals(Phase.UNCERTAIN, restored.active().phase);
        assertEquals("app_restarted", restored.active().reason);
        assertFalse(restored.connected); assertNull(restored.telemetry);
        restored.foreground(true, 50); restored.brokerConnected(); restored.subscriptionComplete(50); restored.online = true;
        restored.receiveTelemetry(sample("DISPONIBLE", 0, "boot-2"), 51);
        assertEquals(1, restored.operations().size()); assertFalse(restored.can(Action.RECHARGE, 51));
        assertTrue(restored.receiveAck(ackFor(sent.id, "applied")));
        assertNull(restored.active());
    }
    @Test public void manuallyReviewedUnresolvedIdsAreDurableAndAcceptLateAck() throws Exception {
        PowerShareModel m = ready("DISPONIBLE", 10);
        Operation op = m.begin(Action.RECHARGE, BigDecimal.TEN, 2);
        m.uncertain("local_timeout"); m.closeManually(op.id, 3);
        PowerShareModel restored = new PowerShareModel(); restored.restore(m.serialize());
        assertNull(restored.active()); assertEquals(op.id, restored.operations().get(0).id);
        assertTrue(restored.receiveAck(ackFor(op.id, "applied")));
        assertEquals(Phase.APPLIED, restored.operations().get(0).phase);
    }
    @Test public void corruptJournalCannotPartiallyRestoreAndStorageFailureBlocksCommands() throws Exception {
        PowerShareModel m = ready("DISPONIBLE", 10);
        m.storageHealthy = false; assertFalse(m.can(Action.RECHARGE, 2));
        PowerShareModel empty = new PowerShareModel();
        assertThrows(Exception.class, () -> empty.restore("{\"version\":99,\"operations\":[]}"));
        assertThrows(Exception.class, () -> empty.restore("broken"));
        assertTrue(empty.operations().isEmpty());
    }
}
