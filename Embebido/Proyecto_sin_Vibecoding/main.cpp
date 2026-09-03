// Definicion de pines y conexiones
#include <HardwareSerial.h>
#include <math.h>
#include <Arduino.h>


#define LED_RELE 27
#define RELE 26
#define LED_TEMPERATURA 25
#define POTENCIOMETRO 34
#define SENSOR_TEMPERATURA 35
#define SCL_LED 22
#define SDA_LED 21
#define PWM_FRECUENCIA 5000
#define PWM_RESOLUCION 8
#define CANALTEMPERATURA 0 
#define TEMP_MAX_COOLER 60
#define TEMP_MIN_COOLER 25
#define POTENCIA_MAX_COOLER 255
#define POTENCIA_INI_COOLER 26
#define POTENCIA_MIN_COOLER 0 



float temp;

void setup() {
  Serial.begin(115200);
  //Pines OUTPUT
  pinMode(RELE,OUTPUT);
  pinMode(LED_RELE,OUTPUT);

  //Pines INPUT
  pinMode(SENSOR_TEMPERATURA,INPUT);
  pinMode(POTENCIOMETRO,INPUT);

  //Se inicializa el led de temperatura
  ledcSetup(CANALTEMPERATURA, PWM_FRECUENCIA, PWM_RESOLUCION);
  ledcAttachPin(LED_TEMPERATURA, CANALTEMPERATURA);
}
//Tendriamoos que chequearla, la saque de claude code
float leerTemperaturaC() {
  int adc = analogRead(SENSOR_TEMPERATURA);

  if (adc < 1) adc = 1;
  if (adc > 4094) adc = 4094;

  float r = 1.0f / (4095.0f / (float)adc - 1.0f);
  return 1.0f / (log(r) / 3950.0f + 1.0f / 298.15f) - 273.15f;
}

void habilitarCarga(bool habilitar) {
  digitalWrite(RELE, habilitar ? HIGH : LOW);
  digitalWrite(LED_RELE, habilitar ? HIGH : LOW);
}
void controlarTemperatura(){
  temp = leerTemperaturaC();

  if( temp >= 65){
    //Apagar todo por relay
    //Funcion ShutDown o devuelve un valor y el main distribuye decide? Iria por la segunda para no sobrecargar la funcion.
    ledcWrite(CANALTEMPERATURA,POTENCIA_MAX_COOLER);
  }
  else if(temp < 25)
    ledcWrite(CANALTEMPERATURA,POTENCIA_MIN_COOLER);
  else
    ledcWrite(CANALTEMPERATURA,map((int)temp, TEMP_MIN_COOLER, TEMP_MAX_COOLER, POTENCIA_INI_COOLER, POTENCIA_MAX_COOLER));
  delay(100);

}

void loop() {
 
  //Aca deberia ir la logica condicional para saber si el tomacorrientes deberia ser activado
  /* while( ! HayPago ){
    continue;
  } */
  //Mientras no hay pago, no analizo ni evaluo nada, me quedo en el while.

  //Inicia el consumo de energia
  habilitarCarga(true);
  delay(1000);
  habilitarCarga(false);
  //Control de temperatura 
  controlarTemperatura();
  delay(1000);

  
  //Prende relay cuando alguien enchufa algo


}