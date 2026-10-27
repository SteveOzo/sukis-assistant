# Sukis: asistente de voz para la Galaxy Tab S6 Lite

Di **"Oye Sukis"** y pide lo que necesites:

- *"Oye Sukis, abre Spotify y reproduce Candy de Plan B"*
- *"Oye Sukis, dime el resultado del partido más reciente de Independiente del Valle"*
- *"Oye Sukis, ¿cómo va a estar el clima mañana?"*
- *"Oye Sukis, pon un temporizador de 10 minutos para el arroz"*
- *"Oye Sukis, recuérdame a las 6 sacar la basura"*
- *"Oye Sukis, agrega leche y huevos a la lista de compras"*
- *"Oye Sukis, pausa la música"* · *"sube el volumen"* · *"¿qué canción es esta?"*
- *"Oye Sukis, pon en YouTube cómo hacer encebollado"*
- *"Oye Sukis, despiértame mañana a las 6:30"*
- *"Oye Sukis, prende la luz de la sala"* (si usas Home Assistant)

Cuando Sukis te hace una pregunta, puedes responder sin volver a decir "Oye Sukis".

## Cómo funciona

```
Micrófono ──► Vosk (offline, en la tablet) detecta "Oye Sukis"
                 │
                 ▼  graba hasta que dejas de hablar
           Whisper en Groq (gratis) transcribe tu pedido
                 │
                 ▼
           IA con herramientas: rota entre modelos gratuitos
           Groq → Gemini → OpenRouter (:free) → OpenCode Zen
                 │  (si uno falla o llega a su límite, salta al siguiente)
                 ├─ play_music       Spotify (Web API Premium o modo básico)
                 ├─ web_search       Tavily / DuckDuckGo + read_webpage
                 ├─ get_weather      Open-Meteo
                 ├─ set_timer, set_alarm, reminders
                 ├─ open_app, play_youtube, open_maps, open_website
                 ├─ media_control, set_volume
                 ├─ lists, memory, send_whatsapp, device_status
                 └─ smart_home       Home Assistant (opcional)
                 │
                 ▼
           Voz Andrea (es-EC, femenina, neural) ──► altavoz
```

## Instalación

1. En la tablet abre **[Releases](../../releases/latest)** y descarga el archivo `sukis-N.apk`.
   Android te pedirá permitir instalar apps desde el navegador: acéptalo.
2. Abre **Sukis** y toca **Encender**. Acepta el permiso de micrófono y de notificaciones.
   La primera vez descarga el modelo de voz offline (~40 MB).
3. En la sección **Configuración pendiente** toca cada botón:
   - **Mostrar sobre otras apps**: necesario para que Sukis pueda abrir Spotify, YouTube, etc.
   - **Restricción de batería**: elige *Sin restricciones*. Además, en Ajustes de la tablet →
     *Batería* → *Límites de uso en segundo plano*, asegúrate de que Sukis **no** esté en
     "aplicaciones en suspensión".
4. Toca **Ajustes** y agrega las keys gratuitas (abajo). Todo se guarda solo en la tablet.

Las nuevas versiones se instalan encima de la anterior sin perder la configuración.

### API keys gratuitas

| Servicio | Para qué | Dónde |
|---|---|---|
| **Groq** (recomendado) | Transcribir tu voz (Whisper) y como cerebro principal | https://console.groq.com/keys |
| Google Gemini | Cerebro de respaldo | https://aistudio.google.com/apikey |
| OpenRouter | Modelos `:free` de respaldo (se eligen solos) | https://openrouter.ai/settings/keys |
| OpenCode Zen | Modelos gratuitos de respaldo | https://opencode.ai/auth |
| **Tavily** (recomendado) | Búsquedas precisas (deportes, noticias). 1000 gratis al mes | https://app.tavily.com |

Con solo **Groq** ya funciona todo. Sin Tavily, las búsquedas usan DuckDuckGo.

El orden de los modelos se puede cambiar en Ajustes (`proveedor:modelo`, uno por línea).
Si un proveedor cambia el nombre de un modelo, Sukis lo salta solo; puedes ver qué modelo
respondió en **Ver registro técnico**.

### Spotify Premium (reproducción exacta)

Sin conectar, Sukis le pide a Spotify "reproducir desde búsqueda" (funciona, pero a veces pone
una mezcla). Conectado, busca la canción exacta y la reproduce en la tablet:

1. Entra a https://developer.spotify.com/dashboard e inicia sesión.
2. **Create app**: nombre "Sukis", cualquier descripción.
   En **Redirect URIs** agrega exactamente `sukis://spotify-callback`.
   Marca **Web API** y **Android**. Guarda.
3. Copia el **Client ID** y pégalo en Ajustes de Sukis → **Conectar Spotify** → acepta.

### Voz

- **Edge (por defecto)**: voz neural `es-EC-AndreaNeural` gratis y sin cuenta. Usa el mismo servicio
  que el "Leer en voz alta" de Microsoft Edge. No es un servicio oficial, así que si Microsoft lo
  cambia Sukis usa automáticamente la voz de la tablet hasta que se actualice la app.
- **Azure Speech**: la misma voz por el servicio oficial (capa gratuita de 500 mil caracteres al
  mes; requiere cuenta de Azure).
- **Voz de la tablet**: offline. Para mejor calidad instala "Servicios de voz de Google" y descarga
  la voz en español.

### Si Sukis no te responde al decir "Oye Sukis"

En la pantalla principal aparece **Oigo: "…"** con lo que entiende el reconocedor offline.
Si al decir "Sukis" aparece otra palabra (por ejemplo "chuquis"), agrégala en
**Ajustes → Palabra de activación**. También puedes tocar el orbe o el botón **Hablar**.

Desactiva "Hey Google" / Bixby voice wake-up si te interfieren con el micrófono.

## Compilar

GitHub Actions compila la APK en cada push (`.github/workflows/build.yml`) y publica un Release
en cada push a `main`. Localmente: `./gradlew assembleRelease` (requiere Android SDK 35 y JDK 17).

La APK se firma con `app/sukis.keystore` (incluida en el repo) para que todas las versiones
se puedan instalar encima. Es una app personal; no uses esa llave para publicar en Play Store.

## Privacidad

- La palabra de activación se detecta **en la tablet**; no se envía audio hasta que dices "Oye Sukis".
- Después, el audio del pedido va a Groq (Whisper) y el texto al modelo de IA que responda.
- Las keys, listas, recordatorios y recuerdos se guardan solo en la tablet.
