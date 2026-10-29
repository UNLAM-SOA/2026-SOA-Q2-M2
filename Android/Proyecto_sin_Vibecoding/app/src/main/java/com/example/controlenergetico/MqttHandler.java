package com.example.controlenergetico;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Transporte bloqueante: se usa exclusivamente desde el ejecutor de red del repositorio. */
public final class MqttHandler {
    public interface Listener {
        void brokerConnected();
        void subscribed();
        void lost();
        void message(String topic, String payload);
    }
    private final Listener listener;
    private MqttClient client;
    public MqttHandler(Listener listener) { this.listener = listener; }

    public void connect() throws MqttException {
        // La nueva sesión descarta publicaciones pendientes de conexiones anteriores.
        client = new MqttClient("tcp://" + BuildConfig.MQTT_HOST + ":" + BuildConfig.MQTT_PORT,
                "android-" + UUID.randomUUID(), new MemoryPersistence());
        client.setTimeToWait(10000);
        client.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable cause) { listener.lost(); }
            @Override public void messageArrived(String topic, MqttMessage message) {
                listener.message(topic, new String(message.getPayload(), StandardCharsets.UTF_8));
            }
            @Override public void deliveryComplete(IMqttDeliveryToken token) {
                // PUBACK MQTT no es un resultado funcional de PowerShare.
            }
        });
        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setCleanSession(true);
        options.setAutomaticReconnect(false);
        options.setConnectionTimeout(10);
        if (!BuildConfig.MQTT_USERNAME.trim().isEmpty()) {
            options.setUserName(BuildConfig.MQTT_USERNAME);
            options.setPassword(BuildConfig.MQTT_PASSWORD.toCharArray());
        }
        client.connect(options);
        listener.brokerConnected();
        client.subscribe(BuildConfig.MQTT_PREFIX + "/app/#", 1);
        listener.subscribed();
    }
    public void publish(String payload) throws MqttException {
        if (client == null || !client.isConnected()) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }
        PowerShareProtocol.checkSize(payload);
        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(1); message.setRetained(false);
        client.publish(BuildConfig.MQTT_PREFIX + "/app/commands", message);
    }
    public void disconnect() {
        if (client == null) return;
        try {
            // No enviar mensajes pendientes durante el cierre ni al reconectar.
            client.disconnectForcibly(0, 1000, false);
        } catch (MqttException ignored) {
            // La conexión puede haberse perdido antes del cierre manual.
        }
        try { client.close(true); } catch (MqttException ignored) { /* Ya cerrado. */ }
        client = null;
    }
}
