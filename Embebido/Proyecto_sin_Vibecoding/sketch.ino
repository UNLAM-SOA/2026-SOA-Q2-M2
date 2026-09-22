#include <Arduino.h>
#include <LiquidCrystal_I2C.h>
#include <math.h>
#include <ctype.h>
#include <stdlib.h>

// El rele representa la carga de 12 V; GPIO12 acciona el cooler de refrigeracion.
constexpr byte PIN_COOLER_REFRIGERACION = 12, PIN_RELE_CARGA = 26, PIN_LED_CARGA = 27;
constexpr byte PIN_CORRIENTE = 32, PIN_TEMPERATURA = 35;
constexpr float TENSION_NOMINAL_CARGA_V = 12.0f, FACTOR_ACELERACION = 60.0f;
constexpr float CORRIENTE_MINIMA_A = 0.2f, CORRIENTE_MAXIMA_SIMULADA_A = 5.0f;
constexpr float TEMP_ALTA_C = 60.0f, TEMP_NORMAL_C = 30.0f, TEMP_CRITICA_C = 75.0f;
constexpr unsigned long PERIODO_FSM_MS = 50, PERIODO_DISPLAY_MS = 500, TIMEOUT_SIN_USO_MS = 10000, PERIODO_SERIAL_MS = 20;
constexpr int ADC_MAXIMO = 4095;
constexpr float BETA_NTC = 3950.0f;
constexpr byte RELE_ACTIVO = LOW, RELE_INACTIVO = HIGH;  // Modulo Wokwi activo en bajo.
constexpr byte LCD_DIRECCION = 0x27, LCD_COLUMNAS = 16, LCD_FILAS = 2, LARGO_COLA_EVENTOS = 8;

enum Estado { DISPONIBLE, ACTIVO, DESHABILITADO, ENFRIAMIENTO };
enum Evento { AUTORIZACION, FINALIZAR_USO, RECARGAR, CREDITO_AGOTADO, TIMEOUT_SIN_USO,
              DESHABILITADO_MANUAL, HABILITADO_MANUAL, TEMPERATURA_ALTA, TEMPERATURA_NORMAL,
              TEMPERATURA_CRITICA, ACTUALIZAR_DISPLAY, CONTINUE };
struct Mensaje { Evento evento; float creditoWh; };

const char *NOMBRE_ESTADO[] = { "DISP", "ACT", "DESH", "ENFR" };
const char *NOMBRE_EVENTO[] = { "autorizacion", "finalizar", "recargar", "creditoAgotado", "timeoutSinUso",
  "deshabilitar", "habilitar", "temperaturaAlta", "temperaturaNormal", "temperaturaCritica", "actualizarDisplay", "continue" };

LiquidCrystal_I2C lcd(LCD_DIRECCION, LCD_COLUMNAS, LCD_FILAS);
QueueHandle_t colaEventos;
Estado estadoActual;
float saldoWh = 0.0f, temperaturaC = 0.0f, corrienteA = 0.0f;
unsigned long ultimoDisplayMs = 0, inicioSinUsoMs = 0, ultimaMedicionCreditoMs = 0;

float convertirTemperatura(int lectura) {
  lectura = constrain(lectura, 1, ADC_MAXIMO - 1);
  return 1.0f / (log(1.0f / (ADC_MAXIMO / (float)lectura - 1.0f)) / BETA_NTC + 1.0f / 298.15f) - 273.15f;
}

void actualizarDisplay() {
  char linea[LCD_COLUMNAS + 1];
  snprintf(linea, sizeof(linea), "%-4s %6.1fWh", NOMBRE_ESTADO[estadoActual], saldoWh);
  lcd.setCursor(0, 0); lcd.print(linea);
  snprintf(linea, sizeof(linea), "I:%3.1fA T:%4.1f", corrienteA, temperaturaC);
  lcd.setCursor(0, 1); lcd.print(linea);
}

void aplicarActuadores() {
  const bool cargaActiva = estadoActual == ACTIVO;
  digitalWrite(PIN_RELE_CARGA, cargaActiva ? RELE_ACTIVO : RELE_INACTIVO);
  digitalWrite(PIN_LED_CARGA, cargaActiva ? HIGH : LOW);
  digitalWrite(PIN_COOLER_REFRIGERACION, estadoActual == ENFRIAMIENTO ? HIGH : LOW);
}

void finalizarTransicion(Estado estadoAnterior, Evento evento) {
  if (estadoAnterior == estadoActual) return;
  inicioSinUsoMs = 0;
  ultimaMedicionCreditoMs = millis();
  aplicarActuadores();
  Serial.printf("[FSM] %s --%s--> %s\n", NOMBRE_ESTADO[estadoAnterior], NOMBRE_EVENTO[evento], NOMBRE_ESTADO[estadoActual]);
  actualizarDisplay();
}

void recargar(float creditoWh) {
  saldoWh += creditoWh;
  Serial.printf("Recarga confirmada: +%.2f Wh. Saldo: %.2f Wh\n", creditoWh, saldoWh);
  actualizarDisplay();
}

// Consumo = 12 V x corriente x horas reales x 60. Solo ACTIVO y > 0,2 A.
bool actualizarSaldo(unsigned long ahora) {
  const unsigned long transcurridoMs = ahora - ultimaMedicionCreditoMs;
  ultimaMedicionCreditoMs = ahora;
  if (estadoActual != ACTIVO || corrienteA <= CORRIENTE_MINIMA_A || transcurridoMs == 0) return false;
  const float consumoWh = TENSION_NOMINAL_CARGA_V * corrienteA * (transcurridoMs / 3600000.0f) * FACTOR_ACELERACION;
  if (consumoWh >= saldoWh) {
    saldoWh = 0.0f;
    Serial.println("Credito agotado automaticamente; carga desactivada.");
    return true;
  }
  saldoWh -= consumoWh;
  return false;
}

Mensaje obtenerEvento() {
  const unsigned long ahora = millis();
  temperaturaC = convertirTemperatura(analogRead(PIN_TEMPERATURA));
  corrienteA = analogRead(PIN_CORRIENTE) * CORRIENTE_MAXIMA_SIMULADA_A / ADC_MAXIMO;
  if (actualizarSaldo(ahora)) return { CREDITO_AGOTADO, 0.0f };

  Mensaje mensaje;
  if (xQueueReceive(colaEventos, &mensaje, 0) == pdTRUE) return mensaje;
  if (estadoActual == ACTIVO && temperaturaC >= TEMP_CRITICA_C) return { TEMPERATURA_CRITICA, 0.0f };
  if (estadoActual == ACTIVO && temperaturaC >= TEMP_ALTA_C) return { TEMPERATURA_ALTA, 0.0f };
  if (estadoActual == ENFRIAMIENTO && temperaturaC >= TEMP_CRITICA_C) return { TEMPERATURA_CRITICA, 0.0f };
  if (estadoActual == ENFRIAMIENTO && temperaturaC <= TEMP_NORMAL_C) return { TEMPERATURA_NORMAL, 0.0f };
  if (estadoActual == ACTIVO && corrienteA <= CORRIENTE_MINIMA_A) {
    if (inicioSinUsoMs == 0) inicioSinUsoMs = ahora;
    else if (ahora - inicioSinUsoMs >= TIMEOUT_SIN_USO_MS) {
      inicioSinUsoMs = 0;
      return { TIMEOUT_SIN_USO, 0.0f };
    }
  } else inicioSinUsoMs = 0;
  if (ahora - ultimoDisplayMs >= PERIODO_DISPLAY_MS) {
    ultimoDisplayMs = ahora;
    return { ACTUALIZAR_DISPLAY, 0.0f };
  }
  return { CONTINUE, 0.0f };
}

Estado procesarRecarga(float creditoWh) {
  recargar(creditoWh);
  return estadoActual;
}

Estado procesarActualizacionDisplay() {
  actualizarDisplay();
  return estadoActual;
}

Estado procesarAutorizacion() {
  if (saldoWh > 0.0f) return ACTIVO;
  Serial.println("Saldo insuficiente: recargue con 'c <Wh>'.");
  return DISPONIBLE;
}

Estado procesarEstadoDisponible() {
  return DISPONIBLE;
}

Estado procesarEstadoDeshabilitado() {
  return DESHABILITADO;
}

Estado procesarEstadoEnfriamiento() {
  return ENFRIAMIENTO;
}

Estado procesarTemperaturaNormal(Evento &eventoTransicion) {
  if (saldoWh > 0.0f) return ACTIVO;
  eventoTransicion = CREDITO_AGOTADO;
  return DISPONIBLE;
}

void registrarEventoInesperado(Evento evento) {
  Serial.printf("[FSM] Evento no esperado: estado=%s, evento=%s\n", NOMBRE_ESTADO[estadoActual], NOMBRE_EVENTO[evento]);
}

void registrarEstadoInesperado() {
  Serial.println("[FSM] Estado no reconocido.");
}

void maquinaEstados() {
  const Mensaje mensaje = obtenerEvento();
  const Estado estadoAnterior = estadoActual;
  Evento eventoTransicion = mensaje.evento;

  switch (estadoActual) {
    case DISPONIBLE:
      switch (mensaje.evento) {
        case AUTORIZACION:
          estadoActual = procesarAutorizacion();
          break;
        case RECARGAR:
          estadoActual = procesarRecarga(mensaje.creditoWh);
          break;
        case DESHABILITADO_MANUAL:
          estadoActual = procesarEstadoDeshabilitado();
          break;
        case ACTUALIZAR_DISPLAY:
          estadoActual = procesarActualizacionDisplay();
          break;
        case CONTINUE:
          break;
        default:
          registrarEventoInesperado(mensaje.evento);
          break;
      }
      break;

    case ACTIVO:
      switch (mensaje.evento) {
        case FINALIZAR_USO:
          estadoActual = procesarEstadoDisponible();
          break;
        case RECARGAR:
          estadoActual = procesarRecarga(mensaje.creditoWh);
          break;
        case CREDITO_AGOTADO:
          estadoActual = procesarEstadoDisponible();
          break;
        case TIMEOUT_SIN_USO:
          estadoActual = procesarEstadoDisponible();
          break;
        case DESHABILITADO_MANUAL:
          estadoActual = procesarEstadoDeshabilitado();
          break;
        case TEMPERATURA_ALTA:
          estadoActual = procesarEstadoEnfriamiento();
          break;
        case TEMPERATURA_CRITICA:
          estadoActual = procesarEstadoEnfriamiento();
          break;
        case ACTUALIZAR_DISPLAY:
          estadoActual = procesarActualizacionDisplay();
          break;
        case CONTINUE:
          break;
        default:
          registrarEventoInesperado(mensaje.evento);
          break;
      }
      break;

    case DESHABILITADO:
      switch (mensaje.evento) {
        case RECARGAR:
          estadoActual = procesarRecarga(mensaje.creditoWh);
          break;
        case HABILITADO_MANUAL:
          estadoActual = procesarEstadoDisponible();
          break;
        case ACTUALIZAR_DISPLAY:
          estadoActual = procesarActualizacionDisplay();
          break;
        case CONTINUE:
          break;
        default:
          registrarEventoInesperado(mensaje.evento);
          break;
      }
      break;

    case ENFRIAMIENTO:
      switch (mensaje.evento) {
        case FINALIZAR_USO:
          estadoActual = procesarEstadoDisponible();
          break;
        case RECARGAR:
          estadoActual = procesarRecarga(mensaje.creditoWh);
          break;
        case CREDITO_AGOTADO:
          estadoActual = procesarEstadoDisponible();
          break;
        case TIMEOUT_SIN_USO:
          estadoActual = procesarEstadoDisponible();
          break;
        case DESHABILITADO_MANUAL:
          estadoActual = procesarEstadoDeshabilitado();
          break;
        case TEMPERATURA_NORMAL:
          estadoActual = procesarTemperaturaNormal(eventoTransicion);
          break;
        case TEMPERATURA_CRITICA:
          estadoActual = procesarEstadoDeshabilitado();
          break;
        case ACTUALIZAR_DISPLAY:
          estadoActual = procesarActualizacionDisplay();
          break;
        case CONTINUE:
          break;
        default:
          registrarEventoInesperado(mensaje.evento);
          break;
      }
      break;

    default:
      registrarEstadoInesperado();
      break;
  }

  finalizarTransicion(estadoAnterior, eventoTransicion);
}

bool encolar(Evento evento, float creditoWh = 0.0f) {
  Mensaje mensaje = { evento, creditoWh };
  if (xQueueSend(colaEventos, &mensaje, 0) == pdTRUE) return true;
  Serial.println("Comando rechazado: cola de eventos llena.");
  return false;
}

void procesarLinea(char *linea) {
  while (isspace((unsigned char)*linea)) ++linea;
  if (*linea == '\0') { Serial.println("Comando invalido."); return; }
  if (linea[0] == 'c' && isspace((unsigned char)linea[1])) {
    char *fin;
    const float wh = strtof(linea + 1, &fin);
    while (isspace((unsigned char)*fin)) ++fin;
    if (fin != linea + 1 && *fin == '\0' && isfinite(wh) && wh > 0.0f) encolar(RECARGAR, wh);
    else Serial.println("Recarga invalida: use c <Wh positivo>.");
    return;
  }
  if (linea[1] != '\0') { Serial.println("Comando invalido."); return; }
  switch (linea[0]) {
    case 'a': encolar(AUTORIZACION); break;
    case 'f': encolar(FINALIZAR_USO); break;
    case 'd': encolar(DESHABILITADO_MANUAL); break;
    case 'h': encolar(HABILITADO_MANUAL); break;
    default: Serial.println("Comando invalido."); break;
  }
}

void tareaSerial(void *) {
  char linea[48]; size_t longitud = 0;
  for (;;) {
    while (Serial.available()) {
      const char caracter = (char)Serial.read();
      if (caracter == '\r') continue;
      if (caracter == '\n') { linea[longitud] = '\0'; procesarLinea(linea); longitud = 0; }
      else if (longitud < sizeof(linea) - 1) linea[longitud++] = caracter;
      else { longitud = 0; Serial.println("Comando invalido: linea demasiado larga."); }
    }
    vTaskDelay(pdMS_TO_TICKS(PERIODO_SERIAL_MS));
  }
}

void tareaFSM(void *) {
  TickType_t ultimaEjecucion = xTaskGetTickCount();
  for (;;) { maquinaEstados(); vTaskDelayUntil(&ultimaEjecucion, pdMS_TO_TICKS(PERIODO_FSM_MS)); }
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_COOLER_REFRIGERACION, OUTPUT); pinMode(PIN_RELE_CARGA, OUTPUT); pinMode(PIN_LED_CARGA, OUTPUT);
  pinMode(PIN_CORRIENTE, INPUT); pinMode(PIN_TEMPERATURA, INPUT);
  estadoActual = DISPONIBLE;
  lcd.init(); lcd.backlight(); aplicarActuadores(); actualizarDisplay();
  ultimaMedicionCreditoMs = millis();
  colaEventos = xQueueCreate(LARGO_COLA_EVENTOS, sizeof(Mensaje)); configASSERT(colaEventos);
  Serial.println("Comandos por linea: c <Wh>, a, f, d, h");
  xTaskCreate(tareaSerial, "Serial", 3072, nullptr, 1, nullptr);
  xTaskCreate(tareaFSM, "FSM", 4096, nullptr, 2, nullptr);
}
void loop() { vTaskDelay(portMAX_DELAY); }
