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

// Pantalla de monitoreo y recarga simulada, sin comunicación con el ESP32.
public class MainActivity extends AppCompatActivity {

    private TextView saldoTxt;
    private EditText recargaInput;
    private Button recargarBtn;
    private double saldo = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        saldoTxt = findViewById(R.id.tvSaldo);
        recargaInput = findViewById(R.id.recargaInput);
        recargarBtn = findViewById(R.id.recargarBtn);
        recargarBtn.setOnClickListener(listener);

        // Recuperar el saldo si Android recreó la pantalla, por ejemplo al girarla.
        if (savedInstanceState != null) {
            saldo = savedInstanceState.getDouble("saldo", 100);
        }
        actualizarSaldo();

        ajustarEspacioBarrasSistema();
    }

    private void ajustarEspacioBarrasSistema() {
        View pantallaPrincipal = findViewById(R.id.main);

        ViewCompat.setOnApplyWindowInsetsListener(
                pantallaPrincipal,
                (vista, insets) -> {
                    // Espacio ocupado por las barras del sistema.
                    Insets barras = insets.getInsets(
                            WindowInsetsCompat.Type.systemBars()
                    );

                    // Separar el contenido de esas barras.
                    vista.setPadding(
                            barras.left,
                            barras.top,
                            barras.right,
                            barras.bottom
                    );

                    return insets;
                }
        );
    }

    // Se ejecuta cuando el usuario toca un botón asociado a este listener.
    View.OnClickListener listener = v -> {
        if (v.getId() == R.id.recargarBtn) {
            recargarSaldo();
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

        saldo += cantidad;
        actualizarSaldo();
        recargaInput.setText("");
        recargaInput.setError(null);
    }

    private void actualizarSaldo() {
        saldoTxt.setText(String.format(Locale.getDefault(), "Saldo energético: %.2f Wh", saldo));
    }

    // Android llama a este método para guardar el estado antes de una recreación.
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putDouble("saldo", saldo);
        super.onSaveInstanceState(outState);
    }
}
