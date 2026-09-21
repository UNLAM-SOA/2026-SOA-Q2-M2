#include <Arduino.h>
#include <math.h>
#include <LiquidCrystal_I2C.h>

// Pines Actuadores
#define PIN_TRANSISTOR_REFRIGERACION 12
#define PIN_RELE 26
#define PIN_LED 27

// Pines Sensores
#define PIN_SENSOR_CORRIENTE 32
#define PIN_SENSOR_TEMPERATURA 35

// Umbrales Temperatura
#define UMBRAL_TEMP_SUPERIOR_HABILITAR_REFRIGERACION 60
#define UMBRAL_TEMP_INFERIOR_DESHABILITAR_REFRIGERACION 30
#define UMBRAL_TEMP_CRITICA 75

// Constantes Sensor de Temperatura
#define BETA 3950

// Constantes Sensor de Corriente ACS712
#define SENSIBILIDAD 0.185

// Constantes Display
#define DISPLAY_COLUMNAS 16
#define DISPLAY_FILAS 2
#define DISPLAY_IC2_ADDRESS 0x27
#define DISPLAY_TIEMPO_ACTUALIZACION_TEMP_MS 100
#define DISPLAY_CODIGO_CARACTER_GRADOS 223

#define CANTIDAD_VERIFICACIONES 3

enum estado_sistema
{
  ESTADO_DISPONIBLE,
  ESTADO_ACTIVO,
  ESTADO_DESHABILITADO,
  ESTADO_ENFRIAMIENTO
};

String estado_sistema_desc[] = {
    "ESTADO_DISPONIBLE",
    "ESTADO_ACTIVO",
    "ESTADO_DESHABILITADO",
    "ESTADO_ENFRIAMIENTO"};

enum evento_sistema
{
  EVENTO_AUTORIZACION,
  EVENTO_FINALIZAR_USO,
  EVENTO_CREDITO_AGOTADO,
  EVENTO_TIMEOUT_SIN_USO,
  EVENTO_DESHABILITADO_MANUAL,
  EVENTO_HABILITADO_MANUAL,
  EVENTO_TEMPERATURA_ALTA,
  EVENTO_TEMPERATURA_NORMAL,
  EVENTO_TEMPERATURA_CRITICA,
  EVENTO_ACTUALIZAR_DISPLAY,
  EVENTO_CONTINUE
};

String evento_sistema_desc[] = {
    "EVENTO_AUTORIZACION",
    "EVENTO_FINALIZAR_USO",
    "EVENTO_CREDITO_AGOTADO",
    "EVENTO_TIMEOUT_SIN_USO",
    "EVENTO_DESHABILITADO_MANUAL",
    "EVENTO_HABILITADO_MANUAL",
    "EVENTO_TEMPERATURA_ALTA",
    "EVENTO_TEMPERATURA_NORMAL",
    "EVENTO_TEMPERATURA_CRITICA",
    "EVENTO_ACTUALIZAR_DISPLAY",
    "EVENTO_CONTINUE"};

enum estado_sensor_temperatura
{
  ESTADO_SENSOR_TEMP_INICIAL,
  ESTADO_SENSOR_TEMP_NORMAL,
  ESTADO_SENSOR_TEMP_ALTA,
  ESTADO_SENSOR_TEMP_CRITICA
};

estado_sistema estado_actual = ESTADO_DISPONIBLE;
evento_sistema evento_actual;
estado_sensor_temperatura estado_sensor_temperatura_actual = ESTADO_SENSOR_TEMP_INICIAL;

LiquidCrystal_I2C lcd(DISPLAY_IC2_ADDRESS, DISPLAY_COLUMNAS, DISPLAY_FILAS);

// Variables globales

float temperatura;
float corriente_disponible;

unsigned long tiempo_anterior;
unsigned long tiempo_actual;

// Inicializaciones

void iniciar_display()
{
  lcd.init();
  lcd.backlight();
  lcd.printf("TEMP - %cC", (char)DISPLAY_CODIGO_CARACTER_GRADOS, temperatura);
}

void iniciar()
{
  Serial.begin(115200);

  pinMode(PIN_TRANSISTOR_REFRIGERACION, OUTPUT);
  pinMode(PIN_RELE, OUTPUT);
  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_SENSOR_CORRIENTE, INPUT);
  pinMode(PIN_SENSOR_TEMPERATURA, INPUT);

  iniciar_display();
}

// Log

void log_estado_evento()
{
  if (evento_actual == EVENTO_CONTINUE)
    return;

  Serial.printf("Estado: %s, Evento: %s\r\n", estado_sistema_desc[estado_actual].c_str(), evento_sistema_desc[evento_actual].c_str());
}

// Sensor de temperatura

float leer_temperatura()
{
  int lectura_sensor_temperatura = analogRead(PIN_SENSOR_TEMPERATURA);
  float temperatura_celsius = 1 / (log(1 / (4095.0 / lectura_sensor_temperatura - 1)) / BETA + 1.0 / 298.15) - 273.15;

  return temperatura_celsius;
}

void verificar_sensor_temperatura()
{
  temperatura = leer_temperatura();

  switch (estado_sensor_temperatura_actual)
  {
  case ESTADO_SENSOR_TEMP_INICIAL:
    if (temperatura >= UMBRAL_TEMP_CRITICA)
    {
      evento_actual = EVENTO_TEMPERATURA_CRITICA;
      estado_sensor_temperatura_actual = ESTADO_SENSOR_TEMP_CRITICA;
    }
    else if (temperatura >= UMBRAL_TEMP_SUPERIOR_HABILITAR_REFRIGERACION)
    {
      evento_actual = EVENTO_TEMPERATURA_ALTA;
      estado_sensor_temperatura_actual = ESTADO_SENSOR_TEMP_ALTA;
    }
    else if (temperatura <= UMBRAL_TEMP_INFERIOR_DESHABILITAR_REFRIGERACION)
    {
      evento_actual = EVENTO_TEMPERATURA_NORMAL;
      estado_sensor_temperatura_actual = ESTADO_SENSOR_TEMP_NORMAL;
    }
    break;
  case ESTADO_SENSOR_TEMP_NORMAL:
    if (temperatura >= UMBRAL_TEMP_SUPERIOR_HABILITAR_REFRIGERACION)
    {
      evento_actual = EVENTO_TEMPERATURA_ALTA;
      estado_sensor_temperatura_actual = ESTADO_SENSOR_TEMP_ALTA;
    }
    break;
  case ESTADO_SENSOR_TEMP_ALTA:
    if (temperatura >= UMBRAL_TEMP_CRITICA)
    {
      evento_actual = EVENTO_TEMPERATURA_CRITICA;
      estado_sensor_temperatura_actual = ESTADO_SENSOR_TEMP_CRITICA;
    }
    else if (temperatura <= UMBRAL_TEMP_INFERIOR_DESHABILITAR_REFRIGERACION)
    {
      evento_actual = EVENTO_TEMPERATURA_NORMAL;
      estado_sensor_temperatura_actual = ESTADO_SENSOR_TEMP_NORMAL;
    }
    break;
  case ESTADO_SENSOR_TEMP_CRITICA:
    if (temperatura < UMBRAL_TEMP_CRITICA && temperatura >= UMBRAL_TEMP_SUPERIOR_HABILITAR_REFRIGERACION)
    {
      evento_actual = EVENTO_TEMPERATURA_ALTA;
      estado_sensor_temperatura_actual = ESTADO_SENSOR_TEMP_ALTA;
    }
    else if (temperatura <= UMBRAL_TEMP_INFERIOR_DESHABILITAR_REFRIGERACION)
    {
      evento_actual = EVENTO_TEMPERATURA_NORMAL;
      estado_sensor_temperatura_actual = ESTADO_SENSOR_TEMP_NORMAL;
    }
    break;
  default:
    break;
  }
}

// Sensor de corriente

void verificar_sensor_corriente()
{
}

// Display

unsigned long tiempo_anterior_display;

void verificar_actualizacion_display()
{
  if (tiempo_actual - tiempo_anterior_display > DISPLAY_TIEMPO_ACTUALIZACION_TEMP_MS)
  {
    tiempo_anterior_display = tiempo_actual;
    evento_actual = EVENTO_ACTUALIZAR_DISPLAY;
  }
}

// Generación de eventos

int indice = 0;
void (*verificar[CANTIDAD_VERIFICACIONES])() = {verificar_sensor_temperatura, verificar_actualizacion_display, verificar_sensor_corriente};

void generar_evento()
{
  evento_actual = EVENTO_CONTINUE;

  verificar[indice]();
  indice = ++indice % CANTIDAD_VERIFICACIONES;
  tiempo_anterior = tiempo_actual;
}

void actualizar_display()
{
  lcd.setCursor(5, 0);
  lcd.printf("%.1f %cC", temperatura, (char)DISPLAY_CODIGO_CARACTER_GRADOS);
}

void iniciar_servicio()
{
  digitalWrite(PIN_RELE, HIGH);
  digitalWrite(PIN_LED, HIGH);
}

void finalizar_servicio()
{
  digitalWrite(PIN_RELE, LOW);
  digitalWrite(PIN_LED, LOW);
}

void refrigerar()
{
}

void finalizar_refrigeracion()
{
}

void fsm()
{
  generar_evento();

  log_estado_evento();

  switch (estado_actual)
  {
  case ESTADO_DISPONIBLE:
    switch (evento_actual)
    {
    case EVENTO_DESHABILITADO_MANUAL:
      estado_actual = ESTADO_DESHABILITADO;
      break;
    case EVENTO_AUTORIZACION:
      iniciar_servicio();
      estado_actual = ESTADO_ACTIVO;
      break;
    case EVENTO_CONTINUE:
      break;
    default:
      break;
    }
    break;
  case ESTADO_ACTIVO:
    switch (evento_actual)
    {
    case EVENTO_FINALIZAR_USO:
      finalizar_servicio();
      estado_actual = ESTADO_DISPONIBLE;
      break;
    case EVENTO_CREDITO_AGOTADO:
      finalizar_servicio();
      estado_actual = ESTADO_DISPONIBLE;
      break;
    case EVENTO_TIMEOUT_SIN_USO:
      finalizar_servicio();
      estado_actual = ESTADO_DISPONIBLE;
      break;
    case EVENTO_TEMPERATURA_ALTA:
      refrigerar();
      estado_actual = ESTADO_ENFRIAMIENTO;
      break;
    case EVENTO_DESHABILITADO_MANUAL:
      finalizar_servicio();
      estado_actual = ESTADO_DESHABILITADO;
      break;
    case EVENTO_ACTUALIZAR_DISPLAY:
      actualizar_display();
      break;
    case EVENTO_CONTINUE:
      break;
    default:
      break;
    }
    break;
  case ESTADO_DESHABILITADO:
    switch (evento_actual)
    {
    case EVENTO_HABILITADO_MANUAL:
      estado_actual = ESTADO_DISPONIBLE;
      break;
    case EVENTO_CONTINUE:
      break;
    default:
      break;
    }
    break;
  case ESTADO_ENFRIAMIENTO:
    switch (evento_actual)
    {
    case EVENTO_FINALIZAR_USO:
      finalizar_servicio();
      estado_actual = ESTADO_DISPONIBLE;
      break;
    case EVENTO_CREDITO_AGOTADO:
      finalizar_servicio();
      estado_actual = ESTADO_DISPONIBLE;
      break;
    case EVENTO_TIMEOUT_SIN_USO:
      finalizar_servicio();
      estado_actual = ESTADO_DISPONIBLE;
      break;
    case EVENTO_TEMPERATURA_NORMAL:
      finalizar_refrigeracion();
      estado_actual = ESTADO_ACTIVO;
      break;
    case EVENTO_DESHABILITADO_MANUAL:
      finalizar_servicio();
      estado_actual = ESTADO_DESHABILITADO;
      break;
    case EVENTO_TEMPERATURA_CRITICA:
      finalizar_servicio();
      estado_actual = ESTADO_DESHABILITADO;
      break;
    case EVENTO_ACTUALIZAR_DISPLAY:
      actualizar_display();
      break;
    case EVENTO_CONTINUE:
      break;
    default:
      break;
    }
  default:
    break;
  }
}

void setup()
{
  iniciar();
}

void loop()
{
  fsm();
}