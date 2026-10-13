package com.example.controlenergetico;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttHandler {

    private static final String BROKER_URL = "tcp://broker.emqx.io:1883";
    private final String clientId = MqttClient.generateClientId();
    private MqttClient client;

    // Ejecutar desde un hilo secundario: la conexión puede tardar.
    public void connect() throws MqttException {
        if (isConnected()) {
            return;
        }

        if (client == null) {
            client = new MqttClient(BROKER_URL, clientId, new MemoryPersistence());
        }

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        client.connect(options);
    }

    // Ejecutar también desde el hilo secundario.
    public void disconnect() throws MqttException {
        if (client == null) {
            return;
        }
        if (client.isConnected()) {
            client.disconnect();
        }
        client.close();
        client = null;
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }
}
