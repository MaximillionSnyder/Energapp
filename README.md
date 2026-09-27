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

## Interfaz (versión 3.0)

Rediseño completo como **panel de instrumentos**: fondo casi negro en oscuro,
superficies apiladas con un filo de 1 dp, cifras con ancho tabular (no bailan
al cambiar de valor) y una pareja de colores con significado fijo — **aqua para
la pantalla encendida**, **ámbar para la apagada** — que se repite en el
medidor, el gráfico y las barras. Sigue al sistema en claro/oscuro, con paleta
propia: no se usa Material You porque el acento es parte de la lectura, no
decoración.

Tres pestañas en un **dock flotante** (la activa se expande con su nombre) y el
registro como pantalla secundaria:

| Pantalla | Contenido |
| --- | --- |
| **En vivo** | Medidor de aguja con la potencia instantánea, controles, gráfico de potencia y rejilla de lectura instantánea |
| **Análisis** | Reparto por estado de pantalla, calidad del muestreo y energía por aplicación, en una sola página |
| **Sistema** | El veredicto frente al medidor de Android —con el escalón del 1 % traducido a segundos de uso real—, lo que mide la app y su error medido |
| **Registro** | Diagnóstico local; se abre desde el icono de terminal de la cabecera, con punto rojo si la sesión anterior murió sin cerrarse |

El medidor es un arco de 240° con marcas cada 20°, aguja con muelle y halo; el
arco vira al ámbar por encima del 65 % de la escala. Los gráficos siguen
dibujados **a mano con `Canvas`** (`ui/charts/`): no añaden dependencias y son
los únicos que pintan en otro color los tramos con la pantalla apagada. La
línea **no se suaviza a propósito**: una medida que sube y baja se dibuja como
sube y baja, sin inventar curvatura.

La iconografía también es propia (`ui/glyphs/Glyphs.kt`), dibujada con `Canvas`
sobre una retícula de 24×24: 14 glifos (rayo, pulso, escudo, reloj, terminal,
papelera…) sin `material-icons-extended` y sin vectores XML que aquí no se
pudieran verificar.

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
  con Paparazzi. Por eso este rediseño se ha verificado compilando y midiendo el
  APK, no con capturas: la comprobación visual queda para el teléfono.

## Compilar

**La APK oficial se compila en GitHub Actions al etiquetar (ver más abajo), no
en local.** Lo de esta sección queda para verificar cambios en el propio equipo
o para reproducir el binario.

### Con Android Studio / Gradle

Requiere JDK 17 y el SDK de Android. El proyecto trae el **wrapper de Gradle
9.5.0** (`./gradlew`), así que no hace falta instalar Gradle. Crea
`local.properties` con la ruta del SDK:

```properties
sdk.dir=/ruta/a/tu/android-sdk
```

Y ejecuta:

```bash
./gradlew assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk (R8)
```

El release sale **firmado si le llegan las credenciales** por variables de
entorno (`ENERGIA_KEYSTORE`, `ENERGIA_KEYSTORE_PASSWORD`, `ENERGIA_KEY_ALIAS`,
`ENERGIA_KEY_PASSWORD`); sin ellas se compila sin firmar, de modo que cualquiera
puede verificar el proyecto con solo clonarlo.

### Compilado y firmado en el propio teléfono (Termux, aarch64) — histórico

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
./gradlew assembleDebug --no-daemon --console=plain
```

Tres escollos reales, con su solución:

1. **AGP 9 no admite el plugin `org.jetbrains.kotlin.android`.** Trae soporte
   de Kotlin incorporado; aplicarlo aborta la build. Este proyecto solo declara
   `com.android.application`.
2. **El `aapt2` que descarga AGP es x86-64** y no arranca en Android aarch64
   (`Daemon startup failed`). Se resuelve con un `aapt2` nativo compilado con el
   NDK y la propiedad `android.aapt2FromMavenOverride`, que en este equipo vive
   en `~/.gradle/gradle.properties` (ver la nota de abajo).
3. **La versión de Kotlin la manda AGP 9.3.0**: el compilador es
   `kotlin-compiler-embeddable:2.2.10` (verificado con `:app:dependencies
   --configuration kotlinCompilerClasspath`). Aunque el plugin Compose se declare
   como 2.4.10, eso no sube el compilador; para usar una KGP superior habría que
   añadirla explícitamente al classpath del buildscript.
4. **El APK de depuración conviene verificarlo**: en este Gradle salió sin
   firmar, y Android no instala un paquete sin firma. Se firma con el
   `apksigner` del SDK, que es un script que necesita `java` en el `PATH`:
   ```bash
   sh ~/buildtools/sdk/build-tools/37.0.0/apksigner sign \
     --ks ~/.android/debug.keystore --ks-pass pass:android \
     --key-pass pass:android --ks-key-alias androiddebugkey \
     app/build/outputs/apk/debug/app-debug.apk
   ```
   (El release no tiene este problema: Gradle lo firma con las variables de
   entorno de firma, y en GitHub Actions con los secretos del repositorio.)

5. **R8 y los grupos de Compose.** Sin esto, `assembleRelease` muere con
   «Could not find org.jetbrains.kotlin:compose-group-mapping:2.2.10». AGP 9
   registra la tarea `produceReleaseComposeMapping` y pide ese artefacto en la
   versión del Kotlin embebido (2.2.10), que **nunca se publicó** en Maven
   Central (arranca en la 2.3.0-Beta1). El `build.gradle.kts` fuerza esa
   dependencia a la 2.4.10 — la misma del plugin de Compose de la app — que sí
   existe, y el release compila.

> El override de `aapt2` **no** está en el `gradle.properties` del repositorio,
> sino en `~/.gradle/gradle.properties` de este equipo: así GitHub Actions
> (x86-64, con el `aapt2` de Maven) compila el mismo código sin tocar nada.

> `compileSdk` es 37 porque es la única plataforma instalada; `targetSdk` se
> fijó en **34** (la API real del dispositivo) para no depender de que Android 14
> acepte instalar un APK dirigido a un SDK posterior.

## Release y CI (GitHub Actions)

`.github/workflows/android.yml` es el **único sitio donde se compila la app**.
Lo único que necesita son los secretos de firma, que ya están cargados en el
repositorio (`ENERGIA_KEYSTORE_BASE64`, `ENERGIA_KEYSTORE_PASSWORD`,
`ENERGIA_KEY_ALIAS`, `ENERGIA_KEY_PASSWORD`).

| Evento | Qué hace |
| --- | --- |
| tag `v*` | `assembleRelease` (R8 + firma), artifact `energia-release` y **Release de GitHub con la APK adjunta** |
| manual (Actions ▸ Android ▸ Run workflow) | Lo mismo, pero sin publicar Release (para probar el flujo sin etiquetar) |

Antes de compilar, el flujo **exige que el tag coincida con el `versionName`**
de `app/build.gradle.kts` y aborta con un mensaje claro si no: así no se publica
una `v3.0` que en realidad lleve dentro la 2.0. El procedimiento de release es,
entonces: subir `versionCode` y `versionName` en una pull request a `main`,
fusionarla, y etiquetar el commit resultante.

```bash
git tag v3.0
git push origin v3.0        # dispara la Release con la APK firmada
```

### El keystore

La firma es la identidad de la app: Android solo acepta una actualización si
viene firmada con la misma clave. El keystore de release **no está en el
repositorio** (`.gitignore` lo cubre); vive en `~/keystores/energia-release.jks`
de este equipo, con sus credenciales en `~/keystores/CREDENCIALES.txt` (permisos
600) y copiado en los secretos de GitHub. Guárdalo con copia de seguridad: sin
él no se pueden publicar actualizaciones que Android acepte.

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

**Compilado y firmado.** `BUILD SUCCESSFUL` en las dos variantes (el
binario que se distribuye es el del release de GitHub Actions):

- **debug**: 29 MB (normal en Compose de depuración), firmado con el
  certificado de depuración;
- **release con R8**: **2,1 MB**, un solo `classes.dex`, firmado con el
  certificado propio del proyecto (`CN=Energia`, firma v3) y sin errores de R8;
- los 17 archivos Kotlin compilan (un aviso: `unsafeCheckOpNoThrow` deprecado);
- `aapt2 dump badging`: paquete `dev.haklab.energia`, versión `3.0`
  (versionCode 3), `minSdk 29`, `targetSdk 34`, actividad lanzable
  `MainActivity`, etiqueta «Energía»;
- `resources.arsc` sin comprimir (requisito de Android 11+);
- icono adaptativo empaquetado (`mipmap-anydpi-v26/ic_launcher.xml`), ya con el
  rayo aqua sobre el fondo del panel.

Lo que **no** se puede comprobar aquí: que la app se instale y funcione en el
teléfono. Termux no tiene uid de shell, así que no puede instalar ni listar
paquetes. Esa prueba queda para el usuario; el APK del release de GitHub
Actions ya sale firmado y listo para instalar.

## Estructura

| Archivo | Responsabilidad |
| --- | --- |
| `BatterySampler.kt` | Lee el medidor, normaliza unidades y resuelve el signo de la corriente |
| `EnergyModel.kt` | Integración trapezoidal, descarte de tramos, reparto por pantalla, capacidad estimada |
| `UsageAttribution.kt` | Segmentos de primer plano y reparto de la energía medida |
| `MonitorService.kt` | Foreground service (tipo `specialUse`), wake lock, estado observable y salud de la sesión |
| `EnergiaApp.kt` | `Application`: instala la telemetría y el capturador de crashes antes de todo |
| `diag/Diag.kt` | Registro local rotativo, captura de crashes y detección de sesión anómala |
| `MainActivity.kt` | Armazón: cabecera con estado, dock de pestañas, navegación animada y permiso de notificaciones |
| `ui/theme/Theme.kt` | Paleta del panel (claro/oscuro), tipografía de cifras tabulares y formas |
| `ui/components/Panel.kt` | Panel, cifra, tile, pastilla, barra de proporción y punto que late |
| `ui/charts/Charts.kt` | Gráfico de potencia por estado de pantalla y barra apilada |
| `ui/charts/Gauge.kt` | Medidor de aguja con halo, marcas y zona alta en ámbar |
| `ui/glyphs/Glyphs.kt` | Los 14 glifos, dibujados con `Canvas` sobre retícula de 24×24 |
| `ui/screens/Pantallas.kt` | Las cuatro pantallas: En vivo, Análisis, Sistema y Registro |
| `ui/EnergyViewModel.kt` / `ui/Format.kt` | Estado y acciones de la UI; formato de magnitudes |

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
