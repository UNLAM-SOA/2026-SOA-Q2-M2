#include <Arduino.h>
#include <LiquidCrystal_I2C.h>
#include <math.h>
#include <ctype.h>
#include <stdlib.h>

// El rele representa la carga de 12 V; GPIO12 controla por PWM el cooler de refrigeracion.
constexpr byte PIN_COOLER_REFRIGERACION = 12, PIN_RELE_CARGA = 26, PIN_LED_CARGA = 27;
constexpr byte PIN_CORRIENTE = 32, PIN_TEMPERATURA = 35;
constexpr float TENSION_NOMINAL_CARGA_V = 12.0f, FACTOR_ACELERACION = 60.0f;
constexpr float CORRIENTE_MINIMA_A = 0.2f, CORRIENTE_MAXIMA_SIMULADA_A = 5.0f;
constexpr float TEMP_ALTA_C = 50.0f, TEMP_NORMAL_C = 30.0f, TEMP_CRITICA_C = 75.0f;
constexpr byte RESOLUCION_PWM_COOLER_BITS = 8;
constexpr uint32_t FRECUENCIA_PWM_COOLER_HZ = 5000;
constexpr int POTENCIA_COOLER_APAGADO = 0, POTENCIA_COOLER_MINIMA = 25, POTENCIA_COOLER_MAXIMA = 255; // 255 porque es un LED
constexpr unsigned long PERIODO_FSM_MS = 50, PERIODO_DISPLAY_MS = 500, TIMEOUT_SIN_USO_MS = 10000, PERIODO_SERIAL_MS = 20;
constexpr float MILISEGUNDOS_POR_HORA = 3600000.0f;
constexpr unsigned long VELOCIDAD_SERIAL_BPS = 115200;
constexpr int ADC_MAXIMO = 4095;
constexpr int ADC_LECTURA_MINIMA_VALIDA = 1, ADC_LECTURA_MAXIMA_VALIDA = ADC_MAXIMO - 1;
constexpr float BETA_NTC = 3950.0f, TEMPERATURA_REFERENCIA_NTC_K = 298.15f;
constexpr float DIFERENCIA_KELVIN_CELSIUS = 273.15f;
constexpr byte RELE_ACTIVO = LOW, RELE_INACTIVO = HIGH;  // Modulo Wokwi activo en bajo.
constexpr byte LCD_DIRECCION = 0x27, LCD_COLUMNAS = 16, LCD_FILAS = 2, LARGO_COLA_EVENTOS = 8;
constexpr byte LCD_COLUMNA_INICIAL = 0, LCD_FILA_ESTADO = 0, LCD_FILA_MEDICIONES = 1;
constexpr size_t TAMANO_BUFFER_LCD = LCD_COLUMNAS + 1, TAMANO_BUFFER_COMANDO = 48;
constexpr TickType_t ESPERA_COLA_SIN_BLOQUEO_TICKS = 0;
constexpr uint32_t TAMANO_PILA_TAREA_SERIAL = 3072, TAMANO_PILA_TAREA_FSM = 4096;
constexpr UBaseType_t PRIORIDAD_TAREA_SERIAL = 1, PRIORIDAD_TAREA_FSM = 2;

enum Estado { DISPONIBLE, ACTIVO, DESHABILITADO, ENFRIAMIENTO };
enum Evento { AUTORIZACION, FINALIZAR_USO, RECARGAR, CREDITO_AGOTADO, TIMEOUT_SIN_USO,
              DESHABILITADO_MANUAL, HABILITADO_MANUAL, TEMPERATURA_ALTA, TEMPERATURA_NORMAL,
              TEMPERATURA_CRITICA, ACTUALIZAR_DISPLAY, CONTINUE };
struct Mensaje { Evento evento; float creditoWh; };

// Zonas de temperatura: sirven para recordar que temperatura ya se informo.
enum ZonaTemperatura { ZONA_DESCONOCIDA, ZONA_NORMAL, ZONA_INTERMEDIA, ZONA_ALTA, ZONA_CRITICA };

// Nombres iguales al diagrama de estados (traza por Serial).
const char *NOMBRE_ESTADO[] = { "DISPONIBLE", "ACTIVO", "DESHABILITADO", "ENFRIAMIENTO" };
const char *NOMBRE_EVENTO[] = { "AUTORIZACION", "FINALIZAR_USO", "RECARGAR", "CREDITO_AGOTADO", "TIMEOUT_SIN_USO",
  "DESHABILITADO_MANUAL", "HABILITADO_MANUAL", "TEMPERATURA_ALTA", "TEMPERATURA_NORMAL", "TEMPERATURA_CRITICA",
  "ACTUALIZAR_DISPLAY", "CONTINUE" };
// Abreviaturas para que entren en las 16 columnas del LCD.
const char *NOMBRE_ESTADO_LCD[] = { "DISP", "ACT", "DESH", "ENFR" };

LiquidCrystal_I2C lcd(LCD_DIRECCION, LCD_COLUMNAS, LCD_FILAS);
QueueHandle_t colaEventos;
Estado estadoActual;
float saldoWh = 0.0f, temperaturaC = 0.0f, corrienteA = 0.0f;
unsigned long ultimoDisplayMs = 0, inicioSinUsoMs = 0, ultimaMedicionCreditoMs = 0;

// Memoria de los eventos: lo que se vio en el ciclo anterior.
ZonaTemperatura zonaAnterior = ZONA_DESCONOCIDA;
float saldoAnteriorWh = 0.0f;

float convertirTemperatura(int lectura) {
  lectura = constrain(lectura, ADC_LECTURA_MINIMA_VALIDA, ADC_LECTURA_MAXIMA_VALIDA);
  return 1.0f / (log(1.0f / (ADC_MAXIMO / (float)lectura - 1.0f)) / BETA_NTC + 1.0f / TEMPERATURA_REFERENCIA_NTC_K) - DIFERENCIA_KELVIN_CELSIUS;
}

void actualizarDisplay() {
  char linea[TAMANO_BUFFER_LCD];

  snprintf(linea, sizeof(linea), "%-4s %6.1fWh", NOMBRE_ESTADO_LCD[estadoActual], saldoWh);
  lcd.setCursor(LCD_COLUMNA_INICIAL, LCD_FILA_ESTADO);
  lcd.print(linea);

  snprintf(linea, sizeof(linea), "I:%3.1fA T:%4.1f", corrienteA, temperaturaC);
  lcd.setCursor(LCD_COLUMNA_INICIAL, LCD_FILA_MEDICIONES);
  lcd.print(linea);
}

// ---------------------------------------------------------------------------
// Acciones: no preguntan en que estado esta la maquina, solo hacen.
// ---------------------------------------------------------------------------

void encenderCarga() {
  digitalWrite(PIN_RELE_CARGA, RELE_ACTIVO);
  digitalWrite(PIN_LED_CARGA, HIGH);
}

void apagarCarga() {
  digitalWrite(PIN_RELE_CARGA, RELE_INACTIVO);
  digitalWrite(PIN_LED_CARGA, LOW);
}

void apagarCooler() {
  ledcWrite(PIN_COOLER_REFRIGERACION, POTENCIA_COOLER_APAGADO);
}

void regularCooler() {
  const long potencia = map((long)temperaturaC, (long)TEMP_ALTA_C, (long)TEMP_CRITICA_C,
                            POTENCIA_COOLER_MINIMA, POTENCIA_COOLER_MAXIMA);
  ledcWrite(PIN_COOLER_REFRIGERACION, constrain(potencia, POTENCIA_COOLER_MINIMA, POTENCIA_COOLER_MAXIMA));
}

// Consumo = 12 V x corriente x horas reales x 60. Solo con mas de 0,2 A.
void descontarSaldo() {
  const unsigned long ahora = millis();
  const unsigned long transcurridoMs = ahora - ultimaMedicionCreditoMs;
  ultimaMedicionCreditoMs = ahora;

  if (corrienteA <= CORRIENTE_MINIMA_A) {
    return;
  }

  const float consumoWh = TENSION_NOMINAL_CARGA_V * corrienteA * (transcurridoMs / MILISEGUNDOS_POR_HORA) * FACTOR_ACELERACION;
  if (consumoWh >= saldoWh) {
    saldoWh = 0.0f;
    Serial.println("Credito agotado automaticamente; carga desactivada.");
    return;
  }
  saldoWh -= consumoWh;
}

void cambiarEstado(Estado nuevoEstado, Evento evento) {
  if (nuevoEstado == estadoActual) {
    return;
  }

  const Estado estadoAnterior = estadoActual;
  estadoActual = nuevoEstado;
  inicioSinUsoMs = 0;
  ultimaMedicionCreditoMs = millis();
  zonaAnterior = ZONA_DESCONOCIDA;  // Al entrar a un estado se vuelve a informar la temperatura actual.

  Serial.printf("[FSM] %s --%s--> %s\n", NOMBRE_ESTADO[estadoAnterior], NOMBRE_EVENTO[evento], NOMBRE_ESTADO[estadoActual]);
  actualizarDisplay();
}

void recargar(float creditoWh) {
  saldoWh += creditoWh;
  Serial.printf("Recarga confirmada: +%.2f Wh. Saldo: %.2f Wh\n", creditoWh, saldoWh);
  actualizarDisplay();
}

// ---------------------------------------------------------------------------
// Deteccion de eventos: solo miran sensores, saldo y tiempo. Nunca estadoActual.
// ---------------------------------------------------------------------------

void leerSensores() {
  temperaturaC = convertirTemperatura(analogRead(PIN_TEMPERATURA));
  corrienteA = analogRead(PIN_CORRIENTE) * CORRIENTE_MAXIMA_SIMULADA_A / ADC_MAXIMO;
}

// Avisa una sola vez, cuando el saldo pasa de positivo a cero.
bool detectarCreditoAgotado() {
  const bool seAgoto = saldoAnteriorWh > 0.0f && saldoWh <= 0.0f;
  saldoAnteriorWh = saldoWh;
  return seAgoto;
}

ZonaTemperatura calcularZonaTemperatura() {
  if (temperaturaC >= TEMP_CRITICA_C) {
    return ZONA_CRITICA;
  }
  if (temperaturaC >= TEMP_ALTA_C) {
    return ZONA_ALTA;
  }
  if (temperaturaC <= TEMP_NORMAL_C) {
    return ZONA_NORMAL;
  }
  return ZONA_INTERMEDIA;
}

// Avisa solo cuando la temperatura cambia de zona.
bool detectarCambioTemperatura(Evento &evento) {
  const ZonaTemperatura zona = calcularZonaTemperatura();
  if (zona == zonaAnterior) {
    return false;
  }
  zonaAnterior = zona;

  if (zona == ZONA_CRITICA) {
    evento = TEMPERATURA_CRITICA;
    return true;
  }
  if (zona == ZONA_ALTA) {
    evento = TEMPERATURA_ALTA;
    return true;
  }
  if (zona == ZONA_NORMAL) {
    evento = TEMPERATURA_NORMAL;
    return true;
  }
  return false;  // La zona intermedia (entre 30 y 50 grados) no genera evento.
}

// Avisa cada vez que se acumulan 10 s seguidos con corriente baja.
bool detectarTimeoutSinUso(unsigned long ahora) {
  if (corrienteA > CORRIENTE_MINIMA_A) {
    inicioSinUsoMs = 0;
    return false;
  }
  if (inicioSinUsoMs == 0) {
    inicioSinUsoMs = ahora;
    return false;
  }
  if (ahora - inicioSinUsoMs < TIMEOUT_SIN_USO_MS) {
    return false;
  }
  inicioSinUsoMs = 0;
  return true;
}

bool detectarRefrescoDisplay(unsigned long ahora) {
  if (ahora - ultimoDisplayMs < PERIODO_DISPLAY_MS) {
    return false;
  }
  ultimoDisplayMs = ahora;
  return true;
}

Mensaje obtenerEvento() {
  const unsigned long ahora = millis();
  Mensaje mensaje;
  Evento eventoTemperatura;

  leerSensores();

  if (detectarCreditoAgotado()) {
    return { CREDITO_AGOTADO, 0.0f };
  }
  if (xQueueReceive(colaEventos, &mensaje, ESPERA_COLA_SIN_BLOQUEO_TICKS) == pdTRUE) {
    return mensaje;
  }
  if (detectarCambioTemperatura(eventoTemperatura)) {
    return { eventoTemperatura, 0.0f };
  }
  if (detectarTimeoutSinUso(ahora)) {
    return { TIMEOUT_SIN_USO, 0.0f };
  }
  if (detectarRefrescoDisplay(ahora)) {
    return { ACTUALIZAR_DISPLAY, 0.0f };
  }
  return { CONTINUE, 0.0f };
}

// ---------------------------------------------------------------------------
// Transiciones
// ---------------------------------------------------------------------------

void procesarRecarga(float creditoWh) {
  recargar(creditoWh);
}

void procesarActualizacionDisplay() {
  actualizarDisplay();
}

void procesarAutorizacion(Evento evento) {
  if (saldoWh > 0.0f) {
    cambiarEstado(ACTIVO, evento);
    return;
  }
  Serial.println("Saldo insuficiente: recargue con 'c <Wh>'.");
}

void procesarEstadoDisponible(Evento evento) {
  cambiarEstado(DISPONIBLE, evento);
}

void procesarEstadoActivo(Evento evento) {
  cambiarEstado(ACTIVO, evento);
}

void procesarEstadoDeshabilitado(Evento evento) {
  cambiarEstado(DESHABILITADO, evento);
}

void procesarEstadoEnfriamiento(Evento evento) {
  cambiarEstado(ENFRIAMIENTO, evento);
}

void registrarEstadoInesperado() {
  Serial.println("[FSM] Estado no reconocido.");
}

void maquinaEstados() {
  const Mensaje mensaje = obtenerEvento();

  switch (estadoActual) {
    case DISPONIBLE:
      switch (mensaje.evento) {
        case CONTINUE:
          apagarCarga();
          apagarCooler();
          break;
        case AUTORIZACION:
          procesarAutorizacion(mensaje.evento);
          break;
        case RECARGAR:
          procesarRecarga(mensaje.creditoWh);
          break;
        case DESHABILITADO_MANUAL:
          procesarEstadoDeshabilitado(mensaje.evento);
          break;
        case ACTUALIZAR_DISPLAY:
          procesarActualizacionDisplay();
          break;
        default:
          break;  // Evento no configurado en este estado: no se hace nada.
      }
      break;

    case ACTIVO:
      switch (mensaje.evento) {
        case CONTINUE:
          encenderCarga();
          apagarCooler();
          descontarSaldo();
          break;
        case FINALIZAR_USO:
          procesarEstadoDisponible(mensaje.evento);
          break;
        case RECARGAR:
          procesarRecarga(mensaje.creditoWh);
          break;
        case CREDITO_AGOTADO:
          procesarEstadoDisponible(mensaje.evento);
          break;
        case TIMEOUT_SIN_USO:
          procesarEstadoDisponible(mensaje.evento);
          break;
        case DESHABILITADO_MANUAL:
          procesarEstadoDeshabilitado(mensaje.evento);
          break;
        case TEMPERATURA_ALTA:
          procesarEstadoEnfriamiento(mensaje.evento);
          break;
        case TEMPERATURA_CRITICA:
          procesarEstadoDeshabilitado(mensaje.evento);
          break;
        case ACTUALIZAR_DISPLAY:
          procesarActualizacionDisplay();
          break;
        default:
          break;  // Evento no configurado en este estado: no se hace nada.
      }
      break;

    case DESHABILITADO:
      switch (mensaje.evento) {
        case CONTINUE:
          apagarCarga();
          apagarCooler();
          break;
        case RECARGAR:
          procesarRecarga(mensaje.creditoWh);
          break;
        case HABILITADO_MANUAL:
          procesarEstadoDisponible(mensaje.evento);
          break;
        case ACTUALIZAR_DISPLAY:
          procesarActualizacionDisplay();
          break;
        default:
          break;  // Evento no configurado en este estado: no se hace nada.
      }
      break;

    case ENFRIAMIENTO:
      switch (mensaje.evento) {
        case CONTINUE:
          encenderCarga();
          regularCooler();
          descontarSaldo();
          break;
        case FINALIZAR_USO:
          procesarEstadoDisponible(mensaje.evento);
          break;
        case RECARGAR:
          procesarRecarga(mensaje.creditoWh);
          break;
        case CREDITO_AGOTADO:
          procesarEstadoDisponible(mensaje.evento);
          break;
        case TIMEOUT_SIN_USO:
          procesarEstadoDisponible(mensaje.evento);
          break;
        case DESHABILITADO_MANUAL:
          procesarEstadoDeshabilitado(mensaje.evento);
          break;
        case TEMPERATURA_NORMAL:
          procesarEstadoActivo(mensaje.evento);
          break;
        case TEMPERATURA_CRITICA:
          procesarEstadoDeshabilitado(mensaje.evento);
          break;
        case ACTUALIZAR_DISPLAY:
          procesarActualizacionDisplay();
          break;
        default:
          break;  // Evento no configurado en este estado: no se hace nada.
      }
      break;

    default:
      registrarEstadoInesperado();
      break;
  }
}

bool encolar(Evento evento, float creditoWh = 0.0f) {
  Mensaje mensaje = { evento, creditoWh };
  if (xQueueSend(colaEventos, &mensaje, ESPERA_COLA_SIN_BLOQUEO_TICKS) == pdTRUE) {
    return true;
  }
  Serial.println("Comando rechazado: cola de eventos llena.");
  return false;
}

void procesarLinea(char *linea) {
  while (isspace((unsigned char)*linea)) {
    ++linea;
  }
  if (*linea == '\0') {
    Serial.println("Comando invalido.");
    return;
  }

  if (linea[0] == 'c' && isspace((unsigned char)linea[1])) {
    char *fin;
    const float wh = strtof(linea + 1, &fin);
    while (isspace((unsigned char)*fin)) {
      ++fin;
    }
    if (fin != linea + 1 && *fin == '\0' && isfinite(wh) && wh > 0.0f) {
      encolar(RECARGAR, wh);
    } else {
      Serial.println("Recarga invalida: use c <Wh positivo>.");
    }
    return;
  }

  if (linea[1] != '\0') {
    Serial.println("Comando invalido.");
    return;
  }

  switch (linea[0]) {
    case 'a':
      encolar(AUTORIZACION);
      break;
    case 'f':
      encolar(FINALIZAR_USO);
      break;
    case 'd':
      encolar(DESHABILITADO_MANUAL);
      break;
    case 'h':
      encolar(HABILITADO_MANUAL);
      break;
    default:
      Serial.println("Comando invalido.");
      break;
  }
}

void tareaSerial(void *) {
  char linea[TAMANO_BUFFER_COMANDO];
  size_t longitud = 0;

  for (;;) {
    while (Serial.available()) {
      const char caracter = (char)Serial.read();
      if (caracter == '\r') {
        continue;
      }
      if (caracter == '\n') {
        linea[longitud] = '\0';
        procesarLinea(linea);
        longitud = 0;
      } else if (longitud < sizeof(linea) - 1) {
        linea[longitud++] = caracter;
      } else {
        longitud = 0;
        Serial.println("Comando invalido: linea demasiado larga.");
      }
    }
    vTaskDelay(pdMS_TO_TICKS(PERIODO_SERIAL_MS));
  }
}

void tareaFSM(void *) {
  TickType_t ultimaEjecucion = xTaskGetTickCount();

  for (;;) {
    maquinaEstados();
    vTaskDelayUntil(&ultimaEjecucion, pdMS_TO_TICKS(PERIODO_FSM_MS));
  }
}

void setup() {
  Serial.begin(VELOCIDAD_SERIAL_BPS);

  ledcAttach(PIN_COOLER_REFRIGERACION, FRECUENCIA_PWM_COOLER_HZ, RESOLUCION_PWM_COOLER_BITS);
  pinMode(PIN_RELE_CARGA, OUTPUT);
  pinMode(PIN_LED_CARGA, OUTPUT);
  pinMode(PIN_CORRIENTE, INPUT);
  pinMode(PIN_TEMPERATURA, INPUT);

  estadoActual = DISPONIBLE;
  apagarCarga();
  apagarCooler();

  lcd.init();
  lcd.backlight();
  actualizarDisplay();

  ultimaMedicionCreditoMs = millis();
  colaEventos = xQueueCreate(LARGO_COLA_EVENTOS, sizeof(Mensaje));
  configASSERT(colaEventos);

  Serial.println("Comandos por linea: c <Wh>, a, f, d, h");
  xTaskCreate(tareaSerial, "Serial", TAMANO_PILA_TAREA_SERIAL, nullptr, PRIORIDAD_TAREA_SERIAL, nullptr);
  xTaskCreate(tareaFSM, "FSM", TAMANO_PILA_TAREA_FSM, nullptr, PRIORIDAD_TAREA_FSM, nullptr);
}

void loop() {
  vTaskDelay(portMAX_DELAY);
}
