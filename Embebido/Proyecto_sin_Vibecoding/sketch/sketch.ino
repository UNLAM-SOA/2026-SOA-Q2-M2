#include <Arduino.h>
#include <Wire.h>
#include "rgb_lcd.h"
#include <math.h>
#include <ctype.h>
#include <stdlib.h>
#include <WiFi.h>
#include <ArduinoMqttClient.h>
#include <ArduinoJson.h>
#include "config.local.h"

// Delta (carga) por BC337; Creality (refrigeracion) por rele activo en alto.
constexpr byte PIN_PWM_CARGA = 25, PIN_RELE_REFRIGERACION = 26, PIN_LED_CARGA = 27;
constexpr byte PIN_CORRIENTE = 32, PIN_TEMPERATURA = 35;
constexpr float TENSION_NOMINAL_CARGA_V = 12.0f, FACTOR_ACELERACION = 60.0f;
constexpr float CORRIENTE_MINIMA_A = 0.2f;
constexpr float SENS_MV_A = 185.0f, FACTOR_ADC_ACS = 10.0f, MV_MIN_SENSOR = 1000.0f;
constexpr int MUESTRAS_ACS = 200;
constexpr int MUESTRAS_PROMEDIO_CORRIENTE = 16;
constexpr float I_COMP_RELE = 0.0f; // Reemplazar por el valor de "comp" del banco, si se midio.
constexpr float TEMP_ALTA_C = 50.0f, TEMP_NORMAL_C = 30.0f, TEMP_CRITICA_C = 75.0f;
constexpr float TEMP_SIMULADA_MIN_C = -40.0f, TEMP_SIMULADA_MAX_C = 125.0f;
constexpr byte RESOLUCION_PWM_CARGA_BITS = 8;
constexpr uint32_t FRECUENCIA_PWM_CARGA_HZ = 5000;
constexpr int POTENCIA_CARGA_APAGADA = 0, POTENCIA_CARGA_MAXIMA = 255;
constexpr unsigned long PERIODO_FSM_MS = 50, PERIODO_DISPLAY_MS = 500, TIMEOUT_SIN_USO_MS = 10000, PERIODO_SERIAL_MS = 20;
constexpr float MILISEGUNDOS_POR_HORA = 3600000.0f;
constexpr unsigned long VELOCIDAD_SERIAL_BPS = 115200;
constexpr int ADC_MAXIMO = 4095;
constexpr int ADC_LECTURA_MINIMA_VALIDA = 1, ADC_LECTURA_MAXIMA_VALIDA = ADC_MAXIMO - 1;
constexpr float BETA_NTC = 4275.0f, TEMPERATURA_REFERENCIA_NTC_K = 298.15f; // Grove Temp v1.1.
constexpr float DIFERENCIA_KELVIN_CELSIUS = 273.15f;
constexpr byte RELE_ACTIVO = HIGH, RELE_INACTIVO = LOW; // Igual al banco de pruebas fisico.
constexpr byte LCD_COLUMNAS = 16, LCD_FILAS = 2, LARGO_COLA_EVENTOS = 8;
constexpr byte LCD_COLUMNA_INICIAL = 0, LCD_FILA_ESTADO = 0, LCD_FILA_MEDICIONES = 1;
constexpr size_t TAMANO_BUFFER_LCD = LCD_COLUMNAS + 1, TAMANO_BUFFER_COMANDO = 48;
constexpr TickType_t ESPERA_COLA_SIN_BLOQUEO_TICKS = 0;
constexpr uint32_t TAMANO_PILA_TAREA_SERIAL = 3072, TAMANO_PILA_TAREA_FSM = 4096;
constexpr UBaseType_t PRIORIDAD_TAREA_SERIAL = 1, PRIORIDAD_TAREA_FSM = 2;

enum Estado { DISPONIBLE, ACTIVO, DESHABILITADO, ENFRIAMIENTO };
enum Evento { AUTORIZACION, FINALIZAR_USO, RECARGAR, CREDITO_AGOTADO, TIMEOUT_SIN_USO,
              DESHABILITADO_MANUAL, HABILITADO_MANUAL, TEMPERATURA_ALTA, TEMPERATURA_NORMAL,
              TEMPERATURA_CRITICA, ACTUALIZAR_DISPLAY, CONTINUE, AJUSTAR_PWM,
              SIMULAR_TEMPERATURA, USAR_SENSOR_TEMPERATURA };
enum ZonaTemperatura { ZONA_DESCONOCIDA, ZONA_NORMAL, ZONA_INTERMEDIA, ZONA_ALTA, ZONA_CRITICA };
struct Mensaje {
  Evento evento;
  float creditoWh;
  char id[41] = {};  // Vacio = evento local/Serial.
  int porcentaje = 0;
  float temperaturaSimulada = 0.0f;
};
struct Instantanea {
  Estado estado;
  float saldo, corriente, temperatura;
  bool carga;
  uint32_t pwm, actividadMs;
  uint32_t pwmCarga;
  int porcentajeCarga;
  bool temperaturaSimulada;
};
enum TipoPublicacion { CONFIRMACION, NOTIFICACION };
struct Publicacion {
  TipoPublicacion tipo;
  Instantanea datos;
  char id[41];
  const char *resultado;
  const char *motivo;
  Evento evento;
  Estado anterior;
};
struct ComandoRecordado {
  bool ocupado = false;
  Mensaje mensaje;
  Publicacion respuesta;
};

struct ComandoParseado {
  Mensaje mensaje = {};
  const char *error = nullptr;
};

// Reader acotado: permite comprobar basura posterior al objeto JSON.
struct LectorComando {
  const char *datos;
  size_t longitud, posicion = 0;
  int read() { return posicion < longitud ? (unsigned char)datos[posicion++] : -1; }
  size_t readBytes(char *salida, size_t cantidad) {
    size_t copiados = 0;
    while (copiados < cantidad && posicion < longitud) salida[copiados++] = datos[posicion++];
    return copiados;
  }
};

// Declaraciones explicitas para los tipos definidos en este mismo sketch.
inline ComandoParseado parsearComando(const char *entrada, size_t longitud, bool retained);
void agregarDatos(JsonDocument &json, const Instantanea &datos);
bool publicarRespuesta(const Publicacion &p);
Instantanea tomarInstantanea();
void enviarPublicacion(const Publicacion &p);
void confirmarComando(const Mensaje &mensaje, const char *resultado, const char *motivo, bool recordar = true);
bool responderDuplicado(const Mensaje &mensaje);
void observarEvento(const Mensaje &mensaje, Estado anterior);
void cambiarEstado(Estado nuevoEstado, Evento evento);
ZonaTemperatura calcularZonaTemperatura();
bool detectarCambioTemperatura(Evento &evento);
Mensaje obtenerEvento();
void procesarAutorizacion(Evento evento);
void procesarEstadoDisponible(Evento evento);
void procesarEstadoActivo(Evento evento);
void procesarEstadoDeshabilitado(Evento evento);
void procesarEstadoEnfriamiento(Evento evento);
bool encolar(Evento evento, float creditoWh = 0.0f);
bool encolarMensaje(const Mensaje &mensaje);
void actualizarInstantanea();
void tareaRed(void *);

// Nombres iguales al diagrama de estados (traza por Serial).
const char *NOMBRE_ESTADO[] = { "DISPONIBLE", "ACTIVO", "DESHABILITADO", "ENFRIAMIENTO" };
const char *NOMBRE_EVENTO[] = { "AUTORIZACION", "FINALIZAR_USO", "RECARGAR", "CREDITO_AGOTADO", "TIMEOUT_SIN_USO",
  "DESHABILITADO_MANUAL", "HABILITADO_MANUAL", "TEMPERATURA_ALTA", "TEMPERATURA_NORMAL", "TEMPERATURA_CRITICA",
  "ACTUALIZAR_DISPLAY", "CONTINUE", "AJUSTAR_PWM", "SIMULAR_TEMPERATURA", "USAR_SENSOR_TEMPERATURA" };
// Abreviaturas para que entren en las 16 columnas del LCD.
const char *NOMBRE_ESTADO_LCD[] = { "DISP", "ACT", "DESH", "ENFR" };

rgb_lcd lcd;
QueueHandle_t colaEventos;
Estado estadoActual;
float saldoWh = 0.0f, temperaturaC = 0.0f, corrienteA = 0.0f;
float offsetMv = 2550.0f; // Se calibra al iniciar con ambas salidas apagadas, como en el banco.
int porcentajeCarga = 100;
// Solo tareaFSM modifica la fuente de temperatura; Serial envia mensajes por cola.
bool simularTemperatura = false;
float temperaturaSimuladaC = 25.0f;
unsigned long ultimoDisplayMs = 0, inicioSinUsoMs = 0, ultimaMedicionCreditoMs = 0;

// Memoria de los eventos: lo que se vio en el ciclo anterior.
ZonaTemperatura zonaAnterior = ZONA_DESCONOCIDA;
float saldoAnteriorWh = 0.0f;

// Compartidas por colas; el cliente MQTT pertenece exclusivamente a tareaRed.
QueueHandle_t colaInstantanea, colaTelemetria, colaPublicaciones;
char idArranque[17];
WiFiClient transporte;
MqttClient mqtt(transporte);
char topicComandos[128], topicTelemetria[128], topicEventos[128], topicAck[128], topicEstado[128];
uint32_t publicacionesPerdidas = 0; // Solo escritura desde FSM.

// Cache utilizada solamente en setup (antes de las tareas) y en tareaFSM.
ComandoRecordado comandosRecordados[32];
size_t proximoComando = 0;
uint32_t ultimaTelemetria = 0;

float convertirTemperatura(int lectura) {
  lectura = constrain(lectura, ADC_LECTURA_MINIMA_VALIDA, ADC_LECTURA_MAXIMA_VALIDA);
  return 1.0f / (log(ADC_MAXIMO / (float)lectura - 1.0f) / BETA_NTC + 1.0f / TEMPERATURA_REFERENCIA_NTC_K) - DIFERENCIA_KELVIN_CELSIUS;
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
  ledcWrite(PIN_PWM_CARGA, map(porcentajeCarga, 0, 100, POTENCIA_CARGA_APAGADA, POTENCIA_CARGA_MAXIMA));
  digitalWrite(PIN_LED_CARGA, porcentajeCarga > 0 ? HIGH : LOW);
}

void apagarCarga() {
  ledcWrite(PIN_PWM_CARGA, POTENCIA_CARGA_APAGADA);
  digitalWrite(PIN_LED_CARGA, LOW);
}

void apagarCooler() {
  digitalWrite(PIN_RELE_REFRIGERACION, RELE_INACTIVO);
}

void regularCooler() {
  digitalWrite(PIN_RELE_REFRIGERACION, RELE_ACTIVO); // El rele permite encendido/apagado, sin PWM.
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

float leerAcsMv() {
  long suma = 0;
  for (int i = 0; i < MUESTRAS_ACS; ++i) suma += analogReadMilliVolts(PIN_CORRIENTE);
  return (float)suma / MUESTRAS_ACS * FACTOR_ADC_ACS;
}

void calibrarCorriente() {
  Serial.println("Calibrando ACS712: rele OFF, PWM 0...");
  delay(1500);
  float suma = 0.0f;
  for (int i = 0; i < 10; ++i) suma += leerAcsMv();
  const float mv = suma / 10;
  if (mv < MV_MIN_SENSOR) {
    Serial.printf("ACS ERROR: %.0f mV; revisar alimentacion y reiniciar para calibrar.\n", mv);
    return;
  }
  offsetMv = mv;
  Serial.printf("Offset ACS712 = %.0f mV\n", offsetMv);
}

float leerTemperatura() {
  if (simularTemperatura) return temperaturaSimuladaC;
  long sumaTemperatura = 0;
  for (int i = 0; i < 16; ++i) sumaTemperatura += analogRead(PIN_TEMPERATURA);
  return convertirTemperatura(sumaTemperatura / 16);
}

float leerCorriente() {
  // Promedio movil entre ciclos: 16 mediciones abarcan unos 800 ms.
  static float muestrasMv[MUESTRAS_PROMEDIO_CORRIENTE] = {};
  static int siguiente = 0, cantidad = 0;
  const float acsMv = leerAcsMv();
  if (acsMv < MV_MIN_SENSOR) {
    // Una lectura invalida no debe mantener corriente de muestras anteriores.
    siguiente = 0;
    cantidad = 0;
    return 0.0f;
  }

  muestrasMv[siguiente] = acsMv;
  siguiente = (siguiente + 1) % MUESTRAS_PROMEDIO_CORRIENTE;
  if (cantidad < MUESTRAS_PROMEDIO_CORRIENTE) ++cantidad;
  float sumaMv = 0.0f;
  for (int i = 0; i < cantidad; ++i) sumaMv += muestrasMv[i];
  const float promedioMv = sumaMv / cantidad;
  const float compensacion = digitalRead(PIN_RELE_REFRIGERACION) == RELE_ACTIVO ? I_COMP_RELE : 0.0f;
  // Promediar antes de limitar a cero evita sesgar el ruido hacia valores positivos.
  return fmaxf(0.0f, (promedioMv - offsetMv) / SENS_MV_A - compensacion);
}

void leerSensores() {
  temperaturaC = leerTemperatura();
  corrienteA = leerCorriente();
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
  const Estado estadoAntes = estadoActual;
  if (mensaje.id[0] && responderDuplicado(mensaje)) return;
  if (mensaje.id[0] && mensaje.evento == RECARGAR && !isfinite(saldoWh + mensaje.creditoWh)) {
    confirmarComando(mensaje, "rejected", "invalid_format");
    return;
  }
  if (mensaje.evento == AJUSTAR_PWM) {
    porcentajeCarga = mensaje.porcentaje;
    if (estadoActual == ACTIVO || estadoActual == ENFRIAMIENTO) encenderCarga();
    Serial.printf("Carga configurada: %d %% (PWM real: %lu/255).\n", porcentajeCarga, (unsigned long)ledcRead(PIN_PWM_CARGA));
    observarEvento(mensaje, estadoAntes);
    return;
  }
  if (mensaje.evento == SIMULAR_TEMPERATURA || mensaje.evento == USAR_SENSOR_TEMPERATURA) {
    simularTemperatura = mensaje.evento == SIMULAR_TEMPERATURA;
    if (simularTemperatura) temperaturaSimuladaC = mensaje.temperaturaSimulada;
    temperaturaC = leerTemperatura();
    // La deteccion normal de zonas genera el evento termico en el siguiente ciclo.
    Serial.printf("Temperatura %s: %.1f C.\n", simularTemperatura ? "simulada" : "del sensor", temperaturaC);
    actualizarDisplay();
    const Instantanea datos = tomarInstantanea();
    xQueueOverwrite(colaTelemetria, &datos);
    observarEvento(mensaje, estadoAntes);
    return;
  }

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
  observarEvento(mensaje, estadoAntes);
}

bool encolar(Evento evento, float creditoWh) {
  Mensaje mensaje = { evento, creditoWh };
  return encolarMensaje(mensaje);
}

bool encolarMensaje(const Mensaje &mensaje) {
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

  // Aceptar espacios al final, incluso en los comandos de una sola letra.
  char *ultimo = linea + strlen(linea);
  while (ultimo > linea && isspace((unsigned char)ultimo[-1])) *--ultimo = '\0';

  if ((linea[0] == 'p' || linea[0] == 't') && isspace((unsigned char)linea[1])) {
    char *argumento = linea + 1;
    while (isspace((unsigned char)*argumento)) ++argumento;
    if (linea[0] == 't' && !strcmp(argumento, "real")) {
      encolar(USAR_SENSOR_TEMPERATURA);
      return;
    }
    char *fin;
    const float valor = strtof(argumento, &fin);
    const bool numeroValido = fin != argumento && *fin == '\0' && isfinite(valor);
    Mensaje mensaje = {};
    if (linea[0] == 'p') {
      if (!numeroValido || valor < 0 || valor > 100 || valor != floorf(valor)) {
        Serial.println("PWM invalido: use p <porcentaje entero de 0 a 100>.");
        return;
      }
      mensaje.evento = AJUSTAR_PWM;
      mensaje.porcentaje = (int)valor;
    } else {
      if (!numeroValido || valor < TEMP_SIMULADA_MIN_C || valor > TEMP_SIMULADA_MAX_C) {
        Serial.println("Temperatura invalida: use t <C entre -40 y 125> o t real.");
        return;
      }
      mensaje.evento = SIMULAR_TEMPERATURA;
      mensaje.temperaturaSimulada = valor;
    }
    encolarMensaje(mensaje);
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
  bool descartarLinea = false;

  for (;;) {
    while (Serial.available()) {
      const char caracter = (char)Serial.read();
      if (caracter == '\r') {
        continue;
      }
      if (caracter == '\n') {
        if (!descartarLinea) {
          linea[longitud] = '\0';
          procesarLinea(linea);
        }
        longitud = 0;
        descartarLinea = false;
      } else if (descartarLinea) {
        continue;
      } else if (longitud < sizeof(linea) - 1) {
        linea[longitud++] = caracter;
      } else {
        longitud = 0;
        descartarLinea = true;
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
    actualizarInstantanea();
    vTaskDelayUntil(&ultimaEjecucion, pdMS_TO_TICKS(PERIODO_FSM_MS));
  }
}

void setup() {
  Serial.begin(VELOCIDAD_SERIAL_BPS);

  ledcAttach(PIN_PWM_CARGA, FRECUENCIA_PWM_CARGA_HZ, RESOLUCION_PWM_CARGA_BITS);
  pinMode(PIN_RELE_REFRIGERACION, OUTPUT);
  pinMode(PIN_LED_CARGA, OUTPUT);
  pinMode(PIN_CORRIENTE, INPUT);
  pinMode(PIN_TEMPERATURA, INPUT);
  analogReadResolution(12);
  analogSetPinAttenuation(PIN_CORRIENTE, ADC_11db);
  analogSetPinAttenuation(PIN_TEMPERATURA, ADC_11db);

  estadoActual = DISPONIBLE;
  apagarCarga();
  apagarCooler();
  calibrarCorriente();
  leerSensores();

  Wire.begin(21, 22);
  lcd.begin(LCD_COLUMNAS, LCD_FILAS);
  lcd.setRGB(0, 80, 255);
  actualizarDisplay();

  ultimaMedicionCreditoMs = millis();
  colaEventos = xQueueCreate(LARGO_COLA_EVENTOS, sizeof(Mensaje));
  configASSERT(colaEventos);

  colaInstantanea = xQueueCreate(1, sizeof(Instantanea));
  colaTelemetria = xQueueCreate(1, sizeof(Instantanea));
  colaPublicaciones = xQueueCreate(32, sizeof(Publicacion));
  configASSERT(colaInstantanea && colaTelemetria && colaPublicaciones);
  snprintf(idArranque, sizeof(idArranque), "%08lx%08lx", (unsigned long)esp_random(), (unsigned long)esp_random());
  actualizarInstantanea();
  Serial.println("Comandos por linea: c <Wh>, a, f, d, h, p <0..100>, t <-40..125>, t real");
  xTaskCreate(tareaSerial, "Serial", TAMANO_PILA_TAREA_SERIAL, nullptr, PRIORIDAD_TAREA_SERIAL, nullptr);
  xTaskCreate(tareaFSM, "FSM", TAMANO_PILA_TAREA_FSM, nullptr, PRIORIDAD_TAREA_FSM, nullptr);
  xTaskCreate(tareaRed, "Red", 8192, nullptr, 1, nullptr);
}

void loop() {
  vTaskDelay(portMAX_DELAY);
}

// ---------------------------------------------------------------------------
// MQTT: validacion de comandos, red y publicaciones.
// ---------------------------------------------------------------------------

// ArduinoJson admite algunas extensiones (claves sin comillas, numeros con +).
// Validar tokens JSON estandar antes de delegar estructura y tipos a la biblioteca.
inline bool tokensJsonValidos(const char *s, size_t n) {
  auto digito = [](char c) { return c >= '0' && c <= '9'; };
  auto separador = [](char c) { return c == ' ' || c == '\r' || c == '\n' || c == '\t' || c == ',' || c == '}' || c == ']'; };
  for (size_t i = 0; i < n;) {
    const char c = s[i];
    if (separador(c) || c == '{' || c == '[' || c == ':') { ++i; continue; }
    if (c == '"') {
      ++i;
      bool cerrado = false;
      while (i < n) {
        const unsigned char ch = s[i++];
        if (ch == '"') { cerrado = true; break; }
        if (ch < 0x20) return false;
        if (ch == '\\') {
          if (i == n) return false;
          const char escape = s[i++];
          if (escape == 'u') {
            for (int k = 0; k < 4; ++k) {
              if (i == n) return false;
              const char h = s[i++];
              if (!digito(h) && !(h >= 'a' && h <= 'f') && !(h >= 'A' && h <= 'F')) return false;
            }
          } else if (!strchr("\"\\/bfnrt", escape)) return false;
        }
      }
      if (!cerrado) return false;
    } else if (c == '-' || digito(c)) {
      if (s[i] == '-') ++i;
      if (i == n) return false;
      if (s[i] == '0') ++i;
      else {
        if (s[i] < '1' || s[i] > '9') return false;
        while (i < n && digito(s[i])) ++i;
      }
      if (i < n && s[i] == '.') {
        ++i; if (i == n || !digito(s[i])) return false;
        while (i < n && digito(s[i])) ++i;
      }
      if (i < n && (s[i] == 'e' || s[i] == 'E')) {
        ++i; if (i < n && (s[i] == '+' || s[i] == '-')) ++i;
        if (i == n || !digito(s[i])) return false;
        while (i < n && digito(s[i])) ++i;
      }
      if (i < n && !separador(s[i])) return false;
    } else {
      const char *literal = c == 't' ? "true" : c == 'f' ? "false" : c == 'n' ? "null" : nullptr;
      if (!literal) return false;
      const size_t largo = strlen(literal);
      if (i + largo > n || strncmp(s + i, literal, largo)) return false;
      i += largo;
      if (i < n && !separador(s[i])) return false;
    }
  }
  return true;
}

inline bool idValido(const JsonString &id) {
  if (!id.c_str() || !id.size() || id.size() > 40) return false;
  for (size_t i = 0; i < id.size(); ++i) {
    const char c = id.c_str()[i];
    if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
          (c >= '0' && c <= '9') || c == '_' || c == '-')) return false;
  }
  return true;
}

inline ComandoParseado parsearComando(const char *entrada, size_t longitud, bool retained) {
  ComandoParseado salida;
  if (!longitud || longitud > 256) { salida.error = retained ? "retained_command" : "invalid_format"; return salida; }
  JsonDocument json;
  LectorComando lector{entrada, longitud};
  bool invalido = !tokensJsonValidos(entrada, longitud) || deserializeJson(json, lector) || !json.is<JsonObject>();
  for (size_t i = lector.posicion; i < longitud; ++i) {
    const char c = entrada[i];
    if (c != ' ' && c != '\r' && c != '\n' && c != '\t') invalido = true;
  }
  const JsonString id = json["id"].as<JsonString>();
  if (idValido(id)) strlcpy(salida.mensaje.id, id.c_str(), sizeof(salida.mensaje.id));
  if (retained) { salida.error = "retained_command"; return salida; }
  const JsonString comando = json["command"].as<JsonString>();
  bool clavesValidas = true;
  if (json.is<JsonObject>()) for (JsonPair kv : json.as<JsonObject>()) {
    const JsonString key = kv.key();
    if (!((key.size() == 2 && (!strcmp(key.c_str(), "id") || !strcmp(key.c_str(), "wh"))) ||
          (key.size() == 7 && (!strcmp(key.c_str(), "command") || !strcmp(key.c_str(), "percent"))))) clavesValidas = false;
  }
  const float wh = json["wh"].is<float>() ? json["wh"].as<float>() : 0.0f;
  const double porcentaje = json["percent"].is<double>() ? json["percent"].as<double>() : -1.0;
  const char c = comando.size() == 1 ? comando.c_str()[0] : '\0';
  if (invalido || !salida.mensaje.id[0] || !clavesValidas || !c || !strchr("cafdhp", c) ||
      (c == 'c' ? !json["wh"].is<float>() || !isfinite(wh) || wh <= 0 : !json["wh"].isUnbound()) ||
      (c == 'p' ? !isfinite(porcentaje) || porcentaje < 0 || porcentaje > 100 ||
                  porcentaje != floor(porcentaje) : !json["percent"].isUnbound())) {
    salida.error = "invalid_format"; return salida;
  }
  salida.mensaje.evento = c == 'c' ? RECARGAR : c == 'a' ? AUTORIZACION :
                          c == 'f' ? FINALIZAR_USO : c == 'd' ? DESHABILITADO_MANUAL :
                          c == 'h' ? HABILITADO_MANUAL : AJUSTAR_PWM;
  salida.mensaje.creditoWh = wh;
  salida.mensaje.porcentaje = c == 'p' ? (int)porcentaje : 0;
  return salida;
}

void agregarDatos(JsonDocument &json, const Instantanea &datos) {
  json["state"] = NOMBRE_ESTADO[datos.estado];
  json["balanceWh"] = datos.saldo;
  json["currentA"] = datos.corriente;
  json["temperatureC"] = datos.temperatura;
  json["temperatureSimulated"] = datos.temperaturaSimulada;
  json["loadOn"] = datos.carga;
  json["coolerPwm"] = datos.pwm;
  json["loadPwm"] = datos.pwmCarga;
  json["loadPercent"] = datos.porcentajeCarga;
  json["uptimeMs"] = datos.actividadMs;
  json["bootId"] = idArranque;
}

bool publicarJson(const char *topic, JsonDocument &json, uint8_t qos) {
  // Stream con longitud conocida: no depende del buffer de 256 bytes de MQTT.
  if (!mqtt.beginMessage(topic, (unsigned long)measureJson(json), false, qos)) return false;
  serializeJson(json, mqtt);
  return mqtt.endMessage() == 1;
}

bool publicarRespuesta(const Publicacion &p) {
  JsonDocument json;
  agregarDatos(json, p.datos);
  if (p.tipo == CONFIRMACION) {
    if (p.id[0]) json["id"] = p.id;
    else json["id"] = nullptr;
    json["result"] = p.resultado;
    json["reason"] = p.motivo;
    json["source"] = "esp32";
    return publicarJson(topicAck, json, 1);
  }
  json["event"] = NOMBRE_EVENTO[p.evento];
  json["previousState"] = NOMBRE_ESTADO[p.anterior];
  json["alert"] = p.evento == CREDITO_AGOTADO || p.evento == TEMPERATURA_ALTA || p.evento == TEMPERATURA_CRITICA;
  return publicarJson(topicEventos, json, 1);
}

void rechazarRed(const char *id, const char *motivo) {
  Publicacion p = {};
  p.tipo = CONFIRMACION;
  if (id) strlcpy(p.id, id, sizeof(p.id));
  p.resultado = "rejected";
  p.motivo = motivo;
  xQueuePeek(colaInstantanea, &p.datos, 0);
  // El callback puede ejecutarse dentro de endMessage(): diferir la publicacion.
  xQueueSend(colaPublicaciones, &p, 0);
}

void recibirComando(int longitud) {
  // No publicar dentro del callback: endMessage() llama poll() y puede reentrar.
  const bool retained = mqtt.messageRetain() == 1;
  char entrada[257];
  size_t usados = 0;
  const uint32_t inicio = millis();
  while (usados < (size_t)longitud && millis() - inicio < 1000) {
    const int c = mqtt.read();
    if (c < 0) { vTaskDelay(pdMS_TO_TICKS(1)); continue; }
    if (usados < sizeof(entrada) - 1) entrada[usados] = (char)c;
    ++usados;
  }
  entrada[min(usados, sizeof(entrada) - 1)] = '\0';
  if (usados != (size_t)longitud) { rechazarRed(nullptr, "invalid_format"); mqtt.stop(); return; }
  const ComandoParseado comando = parsearComando(entrada, (size_t)longitud, retained);
  if (comando.error) { rechazarRed(comando.mensaje.id, comando.error); return; }
  if (xQueueSend(colaEventos, &comando.mensaje, 0) != pdTRUE) rechazarRed(comando.mensaje.id, "queue_full");
}

void tareaRed(void *) {
  snprintf(topicComandos, sizeof(topicComandos), "%s/device/commands", POWERSHARE_MQTT_PREFIX);
  snprintf(topicTelemetria, sizeof(topicTelemetria), "%s/device/telemetry", POWERSHARE_MQTT_PREFIX);
  snprintf(topicEventos, sizeof(topicEventos), "%s/device/events", POWERSHARE_MQTT_PREFIX);
  snprintf(topicAck, sizeof(topicAck), "%s/device/ack", POWERSHARE_MQTT_PREFIX);
  snprintf(topicEstado, sizeof(topicEstado), "%s/device/status", POWERSHARE_MQTT_PREFIX);
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  transporte.setConnectionTimeout(1000);
  transporte.setTimeout(1000);
  mqtt.setId(POWERSHARE_MQTT_USER "-" POWERSHARE_MQTT_PREFIX);
  mqtt.setUsernamePassword(POWERSHARE_MQTT_USER, POWERSHARE_MQTT_PASSWORD);
  mqtt.setCleanSession(true);
  mqtt.onMessage(recibirComando);
  mqtt.setKeepAliveInterval(10000);
  mqtt.setConnectionTimeout(1000);
  mqtt.beginWill(topicEstado, (unsigned short)7, true, 1);
  mqtt.print("offline"); mqtt.endWill();
  uint32_t ultimoWifi = millis() - 10000, ultimoMqtt = millis() - 2000;
  bool conectadoAntes = false;
  for (;;) {
    if (WiFi.status() != WL_CONNECTED) {
      if (conectadoAntes) { transporte.stop(); conectadoAntes = false; }
      if (millis() - ultimoWifi >= 10000) {
        ultimoWifi = millis();
        WiFi.begin(POWERSHARE_WIFI_SSID, POWERSHARE_WIFI_PASSWORD);
      }
    } else if (!mqtt.connected()) {
      conectadoAntes = false;
      if (millis() - ultimoMqtt >= 2000) {
        ultimoMqtt = millis();
        if (mqtt.connect(POWERSHARE_MQTT_HOST, POWERSHARE_MQTT_PORT) && mqtt.subscribe(topicComandos, 1)) {
          mqtt.beginMessage(topicEstado, true, 1); mqtt.print("online"); mqtt.endMessage();
          conectadoAntes = true;
          Instantanea datos;
          if (xQueuePeek(colaInstantanea, &datos, 0) == pdTRUE) xQueueOverwrite(colaTelemetria, &datos);
          Serial.println("[MQTT] Conectado y suscripto.");
        } else { mqtt.stop(); Serial.println("[MQTT] Sin conexion; reintentando."); }
      }
    } else {
      mqtt.poll();
      // Una publicacion por vuelta: evita monopolizar la CPU y deja procesar comandos.
      Publicacion p;
      if (xQueueReceive(colaPublicaciones, &p, 0) == pdTRUE && !publicarRespuesta(p)) mqtt.stop();
      Instantanea datos;
      if (mqtt.connected() && xQueueReceive(colaTelemetria, &datos, 0) == pdTRUE) {
        JsonDocument json; agregarDatos(json, datos);
        publicarJson(topicTelemetria, json, 0);
      }
    }
    if (!mqtt.connected()) {
      // No reproducir eventos viejos tras una desconexion; las respuestas quedan en cache FSM.
      xQueueReset(colaPublicaciones);
    }
    vTaskDelay(pdMS_TO_TICKS(10));
  }
}

Instantanea tomarInstantanea() {
  const uint32_t pwmCarga = ledcRead(PIN_PWM_CARGA);
  return {estadoActual, saldoWh, corrienteA, temperaturaC,
          pwmCarga > 0,
          digitalRead(PIN_RELE_REFRIGERACION) == RELE_ACTIVO ? 255U : 0U, millis(),
          pwmCarga, porcentajeCarga, simularTemperatura};
}

void actualizarInstantanea() {
  const Instantanea datos = tomarInstantanea();
  xQueueOverwrite(colaInstantanea, &datos);
  if (millis() - ultimaTelemetria >= 1000) {
    ultimaTelemetria = millis();
    xQueueOverwrite(colaTelemetria, &datos);
  }
}

void enviarPublicacion(const Publicacion &p) {
  if (xQueueSend(colaPublicaciones, &p, 0) != pdTRUE) {
    ++publicacionesPerdidas;
    Serial.printf("[MQTT] Cola de salida llena (%lu); FSM continua. Reintentar mismo id para recuperar ack.\n", (unsigned long)publicacionesPerdidas);
  }
}

void confirmarComando(const Mensaje &mensaje, const char *resultado, const char *motivo, bool recordar) {
  Publicacion p = {};
  p.tipo = CONFIRMACION;
  p.datos = tomarInstantanea();
  strlcpy(p.id, mensaje.id, sizeof(p.id));
  p.resultado = resultado;
  p.motivo = motivo;
  if (recordar) {
    comandosRecordados[proximoComando].ocupado = true;
    comandosRecordados[proximoComando].mensaje = mensaje;
    comandosRecordados[proximoComando].respuesta = p;
    proximoComando = (proximoComando + 1) % 32;
  }
  enviarPublicacion(p);
  xQueueOverwrite(colaTelemetria, &p.datos);
}

bool responderDuplicado(const Mensaje &mensaje) {
  for (const auto &entrada : comandosRecordados) {
    if (entrada.ocupado && !strcmp(entrada.mensaje.id, mensaje.id)) {
      if (entrada.mensaje.evento != mensaje.evento || entrada.mensaje.creditoWh != mensaje.creditoWh ||
          entrada.mensaje.porcentaje != mensaje.porcentaje) {
        confirmarComando(mensaje, "rejected", "id_conflict", false);
      } else {
        enviarPublicacion(entrada.respuesta);
        const Instantanea datos = tomarInstantanea();
        xQueueOverwrite(colaTelemetria, &datos);
      }
      return true;
    }
  }
  return false;
}

void observarEvento(const Mensaje &mensaje, Estado anterior) {
  if (mensaje.id[0]) {
    // Describe el resultado de la tabla original; no agrega transiciones ni salidas.
    const bool aplicable = mensaje.evento == RECARGAR || mensaje.evento == AJUSTAR_PWM ||
      (mensaje.evento == AUTORIZACION && anterior == DISPONIBLE) ||
      (mensaje.evento == FINALIZAR_USO && (anterior == ACTIVO || anterior == ENFRIAMIENTO)) ||
      (mensaje.evento == DESHABILITADO_MANUAL && anterior != DESHABILITADO) ||
      (mensaje.evento == HABILITADO_MANUAL && anterior == DESHABILITADO);
    const bool sinSaldo = mensaje.evento == AUTORIZACION && anterior == DISPONIBLE && saldoWh <= 0;
    confirmarComando(mensaje, aplicable && !sinSaldo ? "applied" : "rejected",
                     sinSaldo ? "no_credit" : aplicable ? "ok" : "invalid_state");
  }
  if (anterior != estadoActual || mensaje.evento == CREDITO_AGOTADO ||
      mensaje.evento == TEMPERATURA_ALTA || mensaje.evento == TEMPERATURA_NORMAL || mensaje.evento == TEMPERATURA_CRITICA) {
    Publicacion p = {};
    p.tipo = NOTIFICACION;
    p.datos = tomarInstantanea();
    p.evento = mensaje.evento;
    p.anterior = anterior;
    enviarPublicacion(p);
  }
}

