package com.example.controlenergetico;

import android.content.Context;
import android.content.Intent;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttHandler implements MqttCallback {

    public static final String ACTION_CONNECTION_LOST =
            "com.example.controlenergetico.ACTION_CONNECTION_LOST";

    private static final String BROKER_URL = "tcp://broker.emqx.io:1883";
    private final String clientId = MqttClient.generateClientId();
    private MqttClient client;
    private final Context context;

    public MqttHandler(Context context) {
        this.context = context.getApplicationContext();
    }

    // Ejecutar desde un hilo secundario: la conexión puede tardar.
    public void connect() throws MqttException {
        if (isConnected()) {
            return;
        }

        if (client == null) {
            client = new MqttClient(BROKER_URL, clientId, new MemoryPersistence());
            client.setCallback(this);
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
            client.disconnect(1000);
        }
        client.close();
        client = null;
    }

    @Override
    public void connectionLost(Throwable cause) {
        // Avisar a la Activity mediante un broadcast, como en el ejemplo de la cátedra.
        Intent intent = new Intent(ACTION_CONNECTION_LOST);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // Todavía no nos suscribimos a ningún topic.
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }
}
