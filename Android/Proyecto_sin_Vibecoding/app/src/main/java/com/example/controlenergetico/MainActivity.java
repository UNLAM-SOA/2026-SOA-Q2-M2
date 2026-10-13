package com.example.controlenergetico;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.json.JSONObject;
import org.json.JSONException;

public class MainActivity extends AppCompatActivity {

    private TextView conexionTxt;
    private TextView ultimoMensajeTxt;
    private TextView estadoMqttTxt;
    private TextView temperaturaMqttTxt;
    private TextView saldoMqttTxt;
    private TextView corrienteMqttTxt;
    private Button conectarBtn;
    private Button desconectarBtn;
    private MqttHandler mqttHandler;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pantallaCerrada;

    private final BroadcastReceiver mqttReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MqttHandler.ACTION_CONNECTION_LOST.equals(intent.getAction())) {
                mostrarConexion("MQTT: conexión perdida", false);
            } else if (MqttHandler.ACTION_DATA_RECEIVE.equals(intent.getAction())) {
                String topic = intent.getStringExtra("topic");
                String mensaje = intent.getStringExtra("mensaje");
                if (MqttHandler.TOPIC_PRUEBA.equals(topic) && mensaje != null) {
                    ultimoMensajeTxt.setText("Último mensaje: " + mensaje);
                    mostrarDatosMqtt(mensaje);
                }
            }
        }
    };

    private enum Estado { DISPONIBLE, ACTIVO, ENFRIAMIENTO, DESHABILITADO }

    // TODO: Reemplazar el estado y los valores iniciales simulados por datos del ESP32.
    private Estado estado = Estado.DISPONIBLE;
    private double temperatura = 25;
    private TextView temperaturaTxt;
    // TODO: Quitar estos controles cuando la temperatura provenga del sensor real.
    private EditText temperaturaInput;
    private Button aplicarTemperaturaBtn;
    private Button habilitarBtn;
    private TextView estadoTxt;
    private Button autorizarBtn;
    private Button finalizarBtn;
    private TextView saldoTxt;
    private EditText recargaInput;
    private Button recargarBtn;
    private double saldo = 100;
    // TODO: Quitar estos controles; el consumo real lo calcula el ESP32.
    private EditText consumoInput;
    private Button simularConsumoBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        conexionTxt = findViewById(R.id.conexionTxt);
        ultimoMensajeTxt = findViewById(R.id.ultimoMensajeTxt);
        estadoMqttTxt = findViewById(R.id.estadoMqttTxt);
        temperaturaMqttTxt = findViewById(R.id.temperaturaMqttTxt);
        saldoMqttTxt = findViewById(R.id.saldoMqttTxt);
        corrienteMqttTxt = findViewById(R.id.corrienteMqttTxt);
        conectarBtn = findViewById(R.id.conectarBtn);
        desconectarBtn = findViewById(R.id.desconectarBtn);
        conectarBtn.setOnClickListener(listener);
        desconectarBtn.setOnClickListener(listener);
        mqttHandler = new MqttHandler(this);
        IntentFilter filter = new IntentFilter(MqttHandler.ACTION_CONNECTION_LOST);
        filter.addAction(MqttHandler.ACTION_DATA_RECEIVE);
        // Recibir únicamente los broadcasts enviados por nuestra app.
        ContextCompat.registerReceiver(this, mqttReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        temperaturaTxt = findViewById(R.id.tvTemperatura);
        temperaturaInput = findViewById(R.id.temperaturaInput);
        aplicarTemperaturaBtn = findViewById(R.id.aplicarTemperaturaBtn);
        habilitarBtn = findViewById(R.id.habilitarBtn);
        aplicarTemperaturaBtn.setOnClickListener(listener);
        habilitarBtn.setOnClickListener(listener);
        estadoTxt = findViewById(R.id.tvEstado);
        autorizarBtn = findViewById(R.id.autorizarBtn);
        finalizarBtn = findViewById(R.id.finalizarBtn);
        autorizarBtn.setOnClickListener(listener);
        finalizarBtn.setOnClickListener(listener);
        consumoInput = findViewById(R.id.consumoInput);
        simularConsumoBtn = findViewById(R.id.simularConsumoBtn);
        simularConsumoBtn.setOnClickListener(listener);
        saldoTxt = findViewById(R.id.tvSaldo);
        recargaInput = findViewById(R.id.recargaInput);
        recargarBtn = findViewById(R.id.recargarBtn);
        recargarBtn.setOnClickListener(listener);

        // Recuperar el saldo si Android recreó la pantalla, por ejemplo al girarla.
        if (savedInstanceState != null) {
            saldo = savedInstanceState.getDouble("saldo", 100);
            temperatura = savedInstanceState.getDouble("temperatura", 25);
            estado = Estado.valueOf(savedInstanceState.getString("estado", "DISPONIBLE"));
        }
        actualizarSaldo();
        actualizarTemperatura();
        actualizarEstado();

        ajustarEspacioBarrasSistema();
    }

    private void ajustarEspacioBarrasSistema() {
        View pantallaPrincipal = findViewById(R.id.main);

        ViewCompat.setOnApplyWindowInsetsListener(pantallaPrincipal, (vista, insets) -> {
            Insets barras = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets teclado = insets.getInsets(WindowInsetsCompat.Type.ime());

            // Reservar espacio abajo para las barras o el teclado.
            vista.setPadding(
                    barras.left,
                    barras.top,
                    barras.right,
                    Math.max(barras.bottom, teclado.bottom)
            );
            return insets;
        });
    }

    // Se ejecuta cuando el usuario toca un botón asociado a este listener.
    View.OnClickListener listener = v -> {
        if (v.getId() == R.id.conectarBtn) {
            conectarMqtt();
        } else if (v.getId() == R.id.desconectarBtn) {
            desconectarMqtt();
        } else if (v.getId() == R.id.recargarBtn) {
            recargarSaldo();
        } else if (v.getId() == R.id.autorizarBtn) {
            autorizarUso();
        } else if (v.getId() == R.id.finalizarBtn) {
            finalizarUso();
        } else if (v.getId() == R.id.aplicarTemperaturaBtn) {
            aplicarTemperatura();
        } else if (v.getId() == R.id.habilitarBtn) {
            habilitarEquipo();
        } else if (v.getId() == R.id.simularConsumoBtn) {
            simularConsumo();
        }
    };

    private void mostrarDatosMqtt(String mensaje) {
        try {
            JSONObject datos = new JSONObject(mensaje);
            double temperaturaRecibida = leerNumeroMqtt(datos, "temperatura");
            double saldoRecibido = leerNumeroMqtt(datos, "saldoWh");
            double corrienteRecibida = leerNumeroMqtt(datos, "corrienteA");
            if (saldoRecibido < 0 || corrienteRecibida < 0) {
                throw new JSONException("El saldo y la corriente no pueden ser negativos");
            }

            Object valorEstado = datos.get("estado");
            if (!(valorEstado instanceof String)) {
                throw new JSONException("El estado debe ser un texto");
            }
            // valueOf acepta únicamente los nombres definidos en el enum Estado.
            Estado estadoRecibido = Estado.valueOf((String) valorEstado);

            // Actualizar los cuatro datos solo si el mensaje completo es válido.
            temperaturaMqttTxt.setText(String.format(Locale.getDefault(),
                    "Temperatura MQTT: %.2f °C", temperaturaRecibida));
            estadoMqttTxt.setText("Estado MQTT: " + estadoRecibido.name());
            saldoMqttTxt.setText(String.format(Locale.getDefault(),
                    "Saldo MQTT: %.2f Wh", saldoRecibido));
            corrienteMqttTxt.setText(String.format(Locale.getDefault(),
                    "Corriente MQTT: %.2f A", corrienteRecibida));
        } catch (JSONException | IllegalArgumentException e) {
            ultimoMensajeTxt.setText("Error MQTT: revisá temperatura, estado, saldoWh y corrienteA");
            Log.e("MQTT", "Mensaje de datos inválido", e);
        }
    }

    private double leerNumeroMqtt(JSONObject datos, String campo) throws JSONException {
        Object valor = datos.get(campo);
        if (!(valor instanceof Number)) {
            throw new JSONException(campo + " debe ser un número");
        }
        double numero = ((Number) valor).doubleValue();
        if (!Double.isFinite(numero)) {
            throw new JSONException(campo + " no es válido");
        }
        return numero;
    }

    private void conectarMqtt() {
        conexionTxt.setText("MQTT: conectando…");
        conectarBtn.setEnabled(false);
        desconectarBtn.setEnabled(false);

        // La espera de red ocurre en otro hilo para no congelar la pantalla.
        executorService.execute(() -> {
            try {
                mqttHandler.connect();
                mqttHandler.subscribe(MqttHandler.TOPIC_PRUEBA);
                handler.post(() -> {
                    if (!pantallaCerrada) mostrarConexion("MQTT: conectado y suscrito a prueba", true);
                });
            } catch (MqttException e) {
                Log.e("MQTT", "No se pudo conectar o suscribir", e);
                try {
                    mqttHandler.disconnect();
                } catch (MqttException errorCierre) {
                    Log.e("MQTT", "Error al cerrar la conexión", errorCierre);
                }
                boolean conectado = mqttHandler.isConnected();
                handler.post(() -> {
                    if (!pantallaCerrada) mostrarConexion("MQTT: error al conectar o suscribir", conectado);
                });
            }
        });
    }

    private void desconectarMqtt() {
        conexionTxt.setText("MQTT: desconectando…");
        conectarBtn.setEnabled(false);
        desconectarBtn.setEnabled(false);
        executorService.execute(() -> {
            try {
                mqttHandler.disconnect();
                handler.post(() -> {
                    if (!pantallaCerrada) mostrarConexion("MQTT: desconectado", false);
                });
            } catch (MqttException e) {
                Log.e("MQTT", "No se pudo desconectar", e);
                boolean conectado = mqttHandler.isConnected();
                handler.post(() -> {
                    if (!pantallaCerrada) mostrarConexion("MQTT: error al desconectar", conectado);
                });
            }
        });
    }

    // Este método se llama en el hilo principal, donde se actualiza la pantalla.
    private void mostrarConexion(String texto, boolean conectado) {
        conexionTxt.setText(texto);
        conectarBtn.setEnabled(!conectado);
        desconectarBtn.setEnabled(conectado);
    }

    @Override
    protected void onDestroy() {
        pantallaCerrada = true;
        unregisterReceiver(mqttReceiver);
        handler.removeCallbacksAndMessages(null);
        // La desconexión espera su turno si todavía hay una conexión en curso.
        executorService.execute(() -> {
            try {
                mqttHandler.disconnect();
            } catch (MqttException e) {
                Log.e("MQTT", "Error al cerrar la conexión", e);
            }
        });
        executorService.shutdown();
        super.onDestroy();
    }

    private void recargarSaldo() {
        String texto = recargaInput.getText().toString().trim();
        if (texto.isEmpty()) {
            recargaInput.setError("Ingresá la cantidad de Wh");
            return;
        }

        double cantidad;
        try {
            // Aceptar tanto coma como punto para los decimales.
            cantidad = Double.parseDouble(texto.replace(',', '.'));
        } catch (NumberFormatException e) {
            recargaInput.setError("Ingresá un número válido");
            return;
        }

        if (!Double.isFinite(cantidad) || cantidad <= 0) {
            recargaInput.setError("La recarga debe ser un número mayor que cero");
            return;
        }
        if (!Double.isFinite(saldo + cantidad)) {
            recargaInput.setError("La cantidad ingresada es demasiado grande");
            return;
        }

        // TODO: Enviar la recarga al ESP32 y actualizar el saldo con su confirmación.
        saldo += cantidad;
        actualizarSaldo();
        actualizarEstado();
        recargaInput.setText("");
        recargaInput.setError(null);
    }

    // TODO: Reemplazar las transiciones locales por comandos y estados confirmados por el ESP32.
    private void autorizarUso() {
        if (estado == Estado.DISPONIBLE && saldo > 0) {
            estado = Estado.ACTIVO;
            evaluarTemperatura();
            actualizarEstado();
        }
    }

    private void finalizarUso() {
        if (estado == Estado.ACTIVO || estado == Estado.ENFRIAMIENTO) {
            estado = Estado.DISPONIBLE;
            actualizarEstado();
        }
    }

    // TODO: Quitar este método de entrada manual al recibir mediciones reales del sensor.
    private void aplicarTemperatura() {
        String texto = temperaturaInput.getText().toString().trim();
        double valor;
        try {
            valor = Double.parseDouble(texto.replace(',', '.'));
        } catch (NumberFormatException e) {
            temperaturaInput.setError("Ingresá una temperatura válida");
            return;
        }
        if (!Double.isFinite(valor)) {
            temperaturaInput.setError("Ingresá una temperatura válida");
            return;
        }
        temperatura = valor;
        evaluarTemperatura();
        actualizarTemperatura();
        actualizarEstado();
        temperaturaInput.setText("");
        temperaturaInput.setError(null);
    }

    // TODO: Quitar esta lógica térmica local; la FSM real se ejecuta en el ESP32.
    // En esta simulación evaluamos la temperatura al aplicarla y al autorizar uso.
    private void evaluarTemperatura() {
        if (estado == Estado.ACTIVO || estado == Estado.ENFRIAMIENTO) {
            if (temperatura >= 75) {
                estado = Estado.DESHABILITADO;
            } else if (estado == Estado.ACTIVO && temperatura >= 50) {
                estado = Estado.ENFRIAMIENTO;
            } else if (estado == Estado.ENFRIAMIENTO && temperatura <= 30) {
                estado = Estado.ACTIVO;
            }
        }
    }

    private void habilitarEquipo() {
        // TODO: Condición solo de esta simulación: habilitar con temperatura <= 30 °C.
        // El firmware actual permite habilitación manual sin comprobar la temperatura.
        // Al conectar, enviar el comando y esperar la decisión del ESP32.
        if (estado == Estado.DESHABILITADO && temperatura <= 30) {
            estado = Estado.DISPONIBLE;
            actualizarEstado();
        }
    }

    private void actualizarTemperatura() {
        temperaturaTxt.setText(String.format(Locale.getDefault(), "Temperatura: %.2f °C", temperatura));
    }

    private void actualizarEstado() {
        estadoTxt.setText("Estado: " + estado.name());
        boolean enUso = estado == Estado.ACTIVO || estado == Estado.ENFRIAMIENTO;
        simularConsumoBtn.setEnabled(enUso);
        consumoInput.setEnabled(enUso);
        autorizarBtn.setEnabled(estado == Estado.DISPONIBLE && saldo > 0);
        finalizarBtn.setEnabled(estado == Estado.ACTIVO || estado == Estado.ENFRIAMIENTO);
        habilitarBtn.setEnabled(estado == Estado.DESHABILITADO && temperatura <= 30);
    }

    // TODO: Quitar este descuento manual al recibir el saldo actualizado del ESP32.
    private void simularConsumo() {
        if (estado != Estado.ACTIVO && estado != Estado.ENFRIAMIENTO) {
            return;
        }

        String texto = consumoInput.getText().toString().trim();
        double consumo;
        try {
            consumo = Double.parseDouble(texto.replace(',', '.'));
        } catch (NumberFormatException e) {
            consumoInput.setError("Ingresá un consumo válido en Wh");
            return;
        }
        if (!Double.isFinite(consumo) || consumo <= 0) {
            consumoInput.setError("El consumo debe ser un número mayor que cero");
            return;
        }

        saldo = Math.max(0, saldo - consumo);
        if (saldo == 0) {
            estado = Estado.DISPONIBLE;
        }
        actualizarSaldo();
        actualizarEstado();
        consumoInput.setText("");
        consumoInput.setError(null);
    }

    private void actualizarSaldo() {
        saldoTxt.setText(String.format(Locale.getDefault(), "Saldo energético: %.2f Wh", saldo));
    }

    // Android llama a este método para guardar el estado antes de una recreación.
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putDouble("saldo", saldo);
        // TODO: Al conectar, volver a consultar el estado real en lugar de restaurar una simulación.
        outState.putString("estado", estado.name());
        outState.putDouble("temperatura", temperatura);
        super.onSaveInstanceState(outState);
    }
}
