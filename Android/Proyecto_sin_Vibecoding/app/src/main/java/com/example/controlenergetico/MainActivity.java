package com.example.controlenergetico;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

/** Pantalla de inicio: vincula la app con el equipo PowerShare por QR o deep link. */
public class MainActivity extends AppCompatActivity {
    private static final String LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final String STATE_PANEL_OPENED = "panelOpened";
    private PowerShareRepository repository;
    private final ActivityResultLauncher<String> networkPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) repository.connect();
                else Toast.makeText(this, "Para conectar al equipo, permití el acceso a la red local y volvé a escanear el QR.",
                        Toast.LENGTH_LONG).show();
            });
    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) launchQrScanner();
                else Toast.makeText(this, "Necesitamos permiso de cámara para escanear el QR.",
                        Toast.LENGTH_LONG).show();
            });
    private final ActivityResultLauncher<ScanOptions> qrLauncher = registerForActivityResult(
            new ScanContract(), scanResult -> {
                if (scanResult.getContents() != null) handleQrContent(scanResult.getContents());
            });
    private Button scanQr, openPanel;
    private TextView connection, guide;
    // El panel se abre solo una vez por conexión: al volver con Atrás no se reabre.
    private boolean panelOpened;
    private final PowerShareRepository.Observer observer = this::render;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        repository = ((PowerShareApplication) getApplication()).repository();
        connection = findViewById(R.id.conexionTxt);
        guide = findViewById(R.id.guiaTxt);
        scanQr = findViewById(R.id.escanearQrBtn);
        openPanel = findViewById(R.id.abrirPanelBtn);
        scanQr.setOnClickListener(v -> scanQrWithPermission());
        openPanel.setOnClickListener(v -> openPanel());
        if (savedInstanceState == null) handleDeepLink(getIntent());
        else panelOpened = savedInstanceState.getBoolean(STATE_PANEL_OPENED);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }
    @Override protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_PANEL_OPENED, panelOpened);
    }
    private void connectWithPermission() {
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            networkPermission.launch(LOCAL_NETWORK_PERMISSION);
        } else repository.connect();
    }
    private void scanQrWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA);
        } else {
            launchQrScanner();
        }
    }
    private void launchQrScanner() {
        ScanOptions options = new ScanOptions();
        options.setPrompt("Escaneá el QR de la maqueta PowerShare");
        options.setBeepEnabled(true);
        options.setOrientationLocked(false);
        qrLauncher.launch(options);
    }
    private void handleQrContent(String content) {
        if (content != null && content.contains("powershare")) {
            if (!repository.model.connected && !repository.connectionBusy) connectWithPermission();
            Toast.makeText(this, "Equipo detectado por QR, conectando...", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "El QR escaneado no corresponde a PowerShare.", Toast.LENGTH_LONG).show();
        }
    }
    private void handleDeepLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data != null && "powershare".equals(data.getScheme()) && "connect".equals(data.getHost())) {
            if (!repository.model.connected && !repository.connectionBusy) connectWithPermission();
        }
    }
    private void openPanel() {
        panelOpened = true;
        // Si el panel ya estaba abierto (deep link recibido con el panel en uso) se vuelve a esa instancia.
        startActivity(new Intent(this, ControlActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent);
    }
    @Override protected void onStart() {
        super.onStart();
        repository.foreground(true);
        repository.observe(observer);
    }
    @Override protected void onStop() {
        repository.remove(observer);
        repository.foreground(false);
        super.onStop();
    }
    private void render() {
        PowerShareModel model = repository.model;
        boolean linked = model.connected && model.subscribed;
        String label = repository.connectionLabel();
        // Evitar eventos de accesibilidad y redibujos si el texto no cambió.
        if (!label.contentEquals(connection.getText())) connection.setText(label);
        String guideText = getString(linked ? R.string.guia_vinculado
                : repository.connectionBusy ? R.string.guia_conectando : R.string.guia_vinculacion);
        if (!guideText.contentEquals(guide.getText())) guide.setText(guideText);
        scanQr.setVisibility(model.connected ? View.GONE : View.VISIBLE);
        scanQr.setEnabled(!repository.connectionBusy);
        openPanel.setVisibility(linked ? View.VISIBLE : View.GONE);
        if (!linked) panelOpened = false;
        else if (!panelOpened) openPanel();
    }
}
