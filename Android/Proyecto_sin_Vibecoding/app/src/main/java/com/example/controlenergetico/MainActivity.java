package com.example.controlenergetico;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

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
        if (v.getId() == R.id.recargarBtn) {
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
