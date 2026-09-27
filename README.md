# Energía — medición real de consumo en Android (sin root)

App Android que mide el consumo **real** del dispositivo leyendo el medidor de
batería por hardware, en lugar de fiarse del reparto estimado que muestra
Ajustes. Nace de una observación concreta: el sistema reportaba *«10 % en 3
minutos en primer plano y nada en segundo plano»* para una app ligera.

## El problema, cuantificado

El 10 % de una batería de 5100 mAh (19,74 Wh = 71 053 J) en 180 s exigiría
**39,5 W sostenidos**. Un teléfono en uso normal disipa 1–4 W. La cifra no es
una medida: es el resultado de repartir el gasto del periodo con un modelo
(`power_profile.xml`) sobre un nivel con resolución de **1 %**, cuyo escalón
vale ~711 J. A esa escala, un uso de 3 minutos es prácticamente invisible.

## Qué mide esta app

| Fuente | Unidad | Uso |
| --- | --- | --- |
| `BATTERY_PROPERTY_CURRENT_AVERAGE` | µA | Media del hardware en el intervalo. **Preferida.** |
| `BATTERY_PROPERTY_CURRENT_NOW` | µA | Instantánea. Respaldo si no hay media. |
| `EXTRA_VOLTAGE` (sticky broadcast) | mV | Para calcular potencia |
| `BATTERY_PROPERTY_CHARGE_COUNTER` | µAh | Coulomb counter: capacidad real y validación |
| `UsageStatsManager.queryEvents` | — | Atribución por app en primer plano |
| `PowerManager.isInteractive` | — | Reparto pantalla encendida / apagada |

Todo con APIs públicas y **sin permisos especiales** de batería (verificado en
AOSP: el servidor solo exige `BATTERY_STATS` para las propiedades 7–12, que son
ocultas; las 1–6 son libres).

## La matemática

```
P[mW] = I[mA] × V[V]
E[J] += ((P(t₀) + P(t₁)) / 2) × Δt          regla del trapecio
Q[mAh] = Σ ((I(t₀) + I(t₁)) / 2) × Δt / 3600
```

Se descarta un tramo cuando: hay carga en alguno de sus extremos, `Δt ≤ 0`,
`Δt > 120 s` (hueco: el teléfono estuvo apagado o el servicio murió) o el
contador de carga salta (recalibración del gauge). **No se extrapola**: un
hueco no se rellena inventando consumo.

Resultados de `verification/verificar_energia.py` (integración fina del perfil
como referencia, paso 0,2 s):

| Intervalo | `CURRENT_NOW` | `CURRENT_AVERAGE` |
| --- | --- | --- |
| 1 s | 0,107 % | 0,107 % |
| 5 s | 0,781 % | **0,529 %** |
| 15 s | 1,806 % | 1,108 % |
| 30 s | 3,129 % | 2,203 % |
| 60 s | 3,741 % | 3,485 % |
| 120 s | 5,292 % | 9,610 % |

De ahí la decisión de diseño: muestrear cada 5–30 s y preferir
`CURRENT_AVERAGE`. El propio monitoreo cuesta ~0,7 J/h a 5 s (0,001 % de
batería por hora), despreciable.

El contador de carga resulta aún más preciso donde existe (0,29 % a 30 min),
por eso se usa también para estimar la capacidad real a plena carga
(`full = contador / (nivel/100)`), útil para detectar baterías degradadas.

Reparto por estado de pantalla (5 min encendida + 15 apagada, con el panel
aportando 320 mA cuando está encendido):

| Estado | Energía | Tiempo | Potencia media |
| --- | --- | --- | --- |
| Pantalla encendida | 1523,30 J | 300 s | 5077,66 mW |
| Pantalla apagada | 3125,65 J | 895 s | 3492,34 mW |
| **Suma de los dos tramos** | 4648,94 J | — | — |
| Trapecio global | 4648,94 J | — | — |
| Referencia real (integración fina) | 4693,00 J | — | — |

La suma de los tramos coincide **exactamente** con el trapecio global: el
reparto no pierde ni inventa energía. El error frente a la integración fina es
del 0,94 %.

## Límite honesto: la atribución por app

Sin root ni ser app de sistema **no existe API** para saber cuánto consume cada
app. `dumpsys batterystats` requiere el permiso `DUMP`, y `/sys/class/power_supply`
está bloqueado por un `neverallow` de SELinux para todo `coredomain` (las apps
incluidas). Lo que sí hace esta app:

1. mide el consumo **total real** del hardware, y
2. reparte esos julios por el tiempo que cada app estuvo en primer plano.

El resultado es *«energía gastada mientras X estaba delante»*: medible y
verificable. El resto se muestra como «sin atribuir» (pantalla apagada,
servicios del sistema), sin maquillarlo.

## Interfaz (versión 2.0)

Reescrita en **Jetpack Compose + Material 3** (ui 1.12.1, material3 1.4.0),
con arquitectura `ViewModel` + `StateFlow` + `collectAsStateWithLifecycle` y un
repositorio observable como fuente única de verdad. Cuatro pantallas con
navegación inferior:

| Pantalla | Contenido |
| --- | --- |
| **En vivo** | Consumo medido, gráfico de potencia, lectura instantánea, controles |
| **Historial** | Reparto de energía por estado de pantalla y progreso del muestreo |
| **Apps** | Energía por aplicación, con barra de proporción |
| **Sistema** | El veredicto frente al medidor de Android y el método usado |

Los gráficos están **dibujados a mano con `Canvas` de Compose**
(`ui/charts/Charts.kt`), sin librería. Motivos: no añaden dependencias ni peso,
y permiten pintar en otro color los tramos con la **pantalla apagada**, que es
justo la lectura que interesa.

### Tecnología descartada, y por qué

- **Vico 3.3.1** (librería de gráficos recomendada para este stack): **se intentó
  dos veces y se retiró**. Es un caso instructivo, con dos problemas distintos:
  1. *Metadata incompatible*: Vico trae metadata de Kotlin 2.4.0 y AGP 9.3.0 usa
     `kotlin-compiler-embeddable:2.2.10` (que solo lee hasta 2.3.0). Se resuelve
     declarando KGP explícitamente en el `buildscript` del proyecto raíz — AGP 9
     trata esa versión como **suelo**, no como techo — y así Vico **sí compila**.
  2. *API ininvocable desde Kotlin*: `rememberLine` es una **extensión de miembro
     sobre `LineCartesianLayer.Companion`**, y no se consiguió invocar ni
     importándola como función de nivel superior ni cualificándola
     (`LineCartesianLayer.rememberLine(...)`), tras cinco ciclos de compilación.
     El intento se abandonó por coste/beneficio, no por imposible.
  Los gráficos propios de `ui/charts/Charts.kt` se quedan: compilan, no añaden
  dependencias y son los únicos que pintan los tramos de pantalla apagada en
  otro color.
- **Renderizar la UI sin dispositivo** (para capturas en el PC): **imposible en
  Linux ARM64**. Se verificó a nivel de binario que `layoutlib` (motor de los
  previews de Android Studio y de los screenshot tests de AGP) publica nativos
  solo para `linux/x86-64`, `mac/x86_64`, `mac-arm64` y `win/x86-64` — no existe
  `linux-arm64`. Lo mismo pasa con Robolectric (`nativeruntime-dist-compat`) y
  con Paparazzi. La unica verificacion visual posible es instalar la APK en el
  telefono.
- **Iconos de la barra inferior**: se usa un recurso del sistema para las cuatro
  pestañas porque `material-icons-extended` no está disponible sin descarga;
  se distinguen por etiqueta y por el indicador de selección.

## Compilar

### Con Android Studio / Gradle

Requiere JDK 17 y el SDK de Android. Crea `local.properties`:

```properties
sdk.dir=/ruta/a/tu/android-sdk
```

Y ejecuta:

```bash
./gradlew assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
```

### Compilado y firmado en el propio teléfono (Termux, aarch64)

Este proyecto **se compiló y se firmó en el dispositivo**, sin PC. Receta
verificada, por si hay que repetirla:

| Pieza | Ruta / versión |
| --- | --- |
| JDK | `~/buildtools/jdk-17.0.20.1+1` (Temurin 17.0.20.1) |
| SDK | `~/buildtools/sdk` (plataforma `android-37.0`, build-tools 36 y 37) |
| Gradle | 9.5.0, ya en `~/.gradle/wrapper/dists/` |
| AGP | 9.3.0 |

```bash
cd ~/proyectos/energia-app
export JAVA_HOME=~/buildtools/jdk-17.0.20.1+1
export PATH="$JAVA_HOME/bin:$PATH"
~/.gradle/wrapper/dists/gradle-9.5.0-bin/*/gradle-9.5.0/bin/gradle \
    assembleDebug --no-daemon --console=plain
```

Tres escollos reales, con su solución:

1. **AGP 9 no admite el plugin `org.jetbrains.kotlin.android`.** Trae soporte
   de Kotlin incorporado; aplicarlo aborta la build. Este proyecto solo declara
   `com.android.application`.
2. **El `aapt2` que descarga AGP es x86-64** y no arranca en Android aarch64
   (`Daemon startup failed`). Se resuelve con un `aapt2` nativo compilado con el
   NDK y la propiedad `android.aapt2FromMavenOverride` en `gradle.properties`
   (ya incluida).
3. **La versión de Kotlin la manda AGP 9.3.0**: el compilador es
   `kotlin-compiler-embeddable:2.2.10` (verificado con `:app:dependencies
   --configuration kotlinCompilerClasspath`). Aunque el plugin Compose se declare
   como 2.4.10, eso no sube el compilador; para usar una KGP superior habría que
   añadirla explícitamente al classpath del buildscript.
4. **El APK hay que firmarlo a mano**: Gradle no lo firmó. Se firma con el
   `apksigner` del SDK, que es un script que necesita `java` en el `PATH`:
   ```bash
   sh ~/buildtools/sdk/build-tools/37.0.0/apksigner sign \
     --ks ~/.android/debug.keystore --ks-pass pass:android \
     --key-pass pass:android --ks-key-alias androiddebugkey \
     app/build/outputs/apk/debug/app-debug.apk
   ```

> `compileSdk` es 37 porque es la única plataforma instalada; `targetSdk` se
> fijó en **34** (la API real del dispositivo) para no depender de que Android 14
> acepte instalar un APK dirigido a un SDK posterior.

## Verificación

### Numérica

```bash
python3 verification/verificar_energia.py
```

Reproduce la fórmula de `EnergyModel.kt` sobre un perfil de carga con picos de
1 s y la compara con el nivel del sistema, con el coulomb counter y con el
reparto por estado de pantalla.

### De API (contra el framework del dispositivo)

Cada símbolo de Android que usa la app se comprobó contra el `framework.jar`
del propio Android 14 del dispositivo (clases en DEX, tabla de strings
parseada). Resultado: **21/21 clases y todos los miembros verificados**,
incluidos `isInteractive`, `unsafeCheckOpNoThrow`, `ACTIVITY_RESUMED`,
`ACTION_BATTERY_CHANGED` y las propiedades 1–5 de `BatteryManager`.

## Diagnóstico local (telemetría sin red)

La app no envía nada a ningún servidor: toda la telemetría vive en su
almacenamiento privado (`filesDir/diag/`, rotativa, 128 KB × 2) y se consulta
desde la pestaña **Registro**, con botones para actualizar, borrar y
**compartir como texto** por la hoja del sistema. Sin permisos nuevos.

| Señal | Cómo se captura |
| --- | --- |
| **Crashes** | `Thread.setDefaultUncaughtExceptionHandler` escribe la traza completa (hilo, dispositivo, versión) de forma **síncrona** antes de delegar en el manejador del sistema. |
| **Salud del servicio** | Arranques, paradas, ticks retrasados (> 2× intervalo), huecos de muestreo y reinicios por carga. |
| **Errores de lectura** | Ausencia de corriente por hardware, voltaje o broadcast sticky; excepciones de `getIntProperty`; decisión del signo del fuel gauge. |
| **Tramos descartados** | Cada descarte con su motivo y duración, vía `EnergyModel.lastDiscardReason` (hueco, carga, sin corriente, salto del contador). |
| **Sesión anómala** | El servicio marca la sesión al arrancar y la cierra al parar; si el proceso muere antes (crash o kill del sistema), el siguiente arranque lo detecta y avisa con una tarjeta roja en la pestaña Registro. |

Los ticks normales **no** se registran (evita desgaste de flash): solo las
anomalías. La pestaña además muestra el pulso de la medición (última lectura,
muestras, tamaño del registro) y avisa si el último tick está retrasado.

## Estado de compilación

**Compilado y firmado en el dispositivo.** `BUILD SUCCESSFUL`, sin errores de
Kotlin (un solo aviso: `unsafeCheckOpNoThrow` está deprecado). Verificado:

- los 14 archivos Kotlin compilan (`compileDebugKotlin`), incluidos los de
  Compose y el diagnóstico;
- APK de 29 MB (normal en Compose de depuración), 9 dex;
- firma v3 válida (certificado de depuración de Android) — sin firma, Android
  no instala el paquete;
- `aapt2 dump badging`: paquete `dev.haklab.energia`, `minSdk 29`,
  `targetSdk 34`, actividad lanzable `MainActivity`, etiqueta «Energía»;
- `resources.arsc` sin comprimir (requisito de Android 11+);
- icono adaptativo empaquetado (`mipmap-anydpi-v26/ic_launcher.xml`) y
  referenciado en el manifiesto.

Lo que **no** se pudo comprobar aquí: que la app se instale y funcione en el
teléfono. Termux no tiene uid de shell, así que no puede instalar ni listar
paquetes. Esa prueba queda para el usuario.

## Estructura

| Archivo | Responsabilidad |
| --- | --- |
| `BatterySampler.kt` | Lee el medidor, normaliza unidades y resuelve el signo de la corriente |
| `EnergyModel.kt` | Integración trapezoidal, descarte de tramos, reparto por pantalla, capacidad estimada |
| `UsageAttribution.kt` | Segmentos de primer plano y reparto de la energía medida |
| `MonitorService.kt` | Foreground service (tipo `specialUse`), wake lock, estado observable y salud de la sesión |
| `MainActivity.kt` | Medida en vivo, veredicto frente al sistema, reparto por pantalla y por app |
| `EnergiaApp.kt` | `Application`: instala la telemetría y el capturador de crashes antes de todo |
| `diag/Diag.kt` | Registro local rotativo, captura de crashes y detección de sesión anómala |

## Decisiones que conviene conocer

- **Signo de la corriente.** El javadoc dice que `CURRENT_NOW` es positivo
  cuando la corriente *entra* en la batería. Algunos fuel gauges de OEM lo
  invierten, así que `BatterySampler` lo decide en runtime con evidencia
  física: si el sistema dice «descargando» y la corriente «entra», el gauge
  está invertido.
- **`ACTION_BATTERY_CHANGED` es sticky pero solo para receptores registrados
  en runtime.** Declararlo en el manifiesto no recibe nada.
- **Android 14 exige tipo de foreground service.** No hay categoría para
  «medición de energía»: se usa `specialUse` con su permiso y subtipo.
- **El nivel del sistema no se usa para integrar**, solo para mostrar y para el
  veredicto. Es la fuente del error que motiva la app.
