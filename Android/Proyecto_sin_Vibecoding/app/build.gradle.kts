import java.util.Properties
import groovy.json.JsonOutput.toJson

plugins {
    alias(libs.plugins.android.application)
}

val mqtt = Properties()
rootProject.file("mqtt.local.properties").inputStream().use { mqtt.load(it) }

android {
    buildFeatures {
        buildConfig = true
    }
    namespace = "com.example.controlenergetico"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        buildConfigField("String", "MQTT_HOST", toJson(mqtt.getProperty("MQTT_HOST", "").trim()))
        buildConfigField("int", "MQTT_PORT", mqtt.getProperty("MQTT_PORT", "1883").trim().toInt().toString())
        buildConfigField("String", "MQTT_PREFIX", toJson(mqtt.getProperty("MQTT_PREFIX", "").trim()))
        buildConfigField("String", "MQTT_USERNAME", toJson(mqtt.getProperty("MQTT_USERNAME", "")))
        buildConfigField("String", "MQTT_PASSWORD", toJson(mqtt.getProperty("MQTT_PASSWORD", "")))
        applicationId = "com.example.controlenergetico"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Cliente MQTT
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
