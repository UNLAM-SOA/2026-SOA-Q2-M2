package com.example.controlenergetico;

import android.content.Context;
import android.content.Intent;

import java.nio.charset.StandardCharsets;

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

    public static final String ACTION_DATA_RECEIVE =
            "com.example.controlenergetico.ACTION_DATA_RECEIVE";
    public static final String TOPIC_PRUEBA = "soa/2026/q2/m2/equipo1/prueba";

    public static final String TOPIC_COMANDOS = "soa/2026/q2/m2/equipo1/comandos";

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
            client.setTimeToWait(10000);
        }

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        client.connect(options);
    }

    public void subscribe(String topic) throws MqttException {
        client.subscribe(topic, 0);
    }

    public void publish(String topic, String mensaje) throws MqttException {
        if (!isConnected()) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }
        MqttMessage mqttMessage = new MqttMessage(mensaje.getBytes(StandardCharsets.UTF_8));
        mqttMessage.setQos(1);
        // No guardar comandos para entregarlos a futuros suscriptores.
        mqttMessage.setRetained(false);
        client.publish(topic, mqttMessage);
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
        // Paho recibe en otro hilo: enviar el dato a la Activity mediante un broadcast.
        Intent intent = new Intent(ACTION_DATA_RECEIVE);
        intent.setPackage(context.getPackageName());
        intent.putExtra("topic", topic);
        intent.putExtra("mensaje", message.toString());
        context.sendBroadcast(intent);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }
}
