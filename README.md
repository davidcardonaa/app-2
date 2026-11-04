# Barrix Home

Pantalla de inicio ligera para Fire TV: saludo, hora y fecha, botones de Wi-Fi y Ajustes,
y tus apps en tarjetas grandes con el banner de cada app. Sin librerías externas.

Mando: **OK** abre la app. **OK largo** la fija en *Favoritas* o la quita.

## Compilar el APK

### Opción A: sin Android Studio (GitHub)
1. Crea un repositorio nuevo en GitHub y sube el contenido de esta carpeta (incluida `.github`).
2. Entra en la pestaña **Actions**. Al terminar el trabajo "Compilar APK" (unos minutos),
   abre la ejecución y descarga el archivo **barrix-home-apk**. Dentro está `app-debug.apk`.

### Opción B: Android Studio
Abre esta carpeta como proyecto, deja que sincronice y usa *Build > Build APK(s)*.

## Instalar en el Fire TV
Con la depuración ADB activada (la misma que usa Barrix):

    adb connect IP_DEL_FIRE_TV:5555
    adb install -r app-debug.apk

Después aparecerá **Barrix Home** en tus apps. Ábrela desde ahí.

## Aspecto (colores y foto)
El botón **Aspecto** de la pantalla de inicio abre un panel con tres cosas:
- **Color:** ocho colores (rojo, naranja, amarillo, verde, turquesa, azul, morado y rosa). El texto y los
  bordes siguen siendo blancos.
- **Color según la app:** si está en *Sí*, el fondo toma el color de la app que tengas enfocada
  (rojo con Netflix, azul con Prime Video…). En *No*, se queda siempre con el color elegido.
- **Foto de fondo:** se sube desde el panel web de Barrix (tarjeta "Fondo de Barrix Home") y aquí
  se activa o desactiva. Con foto puesta, el color según la app no se ve, porque manda la foto.

La foto se guarda en la carpeta propia de la app, así que no pide permisos. Hay que haber abierto
Barrix Home al menos una vez antes de subirla.

## Arranque automático
Al encender el Fire TV, Barrix Home se abre sola. Importante: **abre la app una vez a mano
después de instalarla**; Android no activa el aviso de arranque hasta entonces. Se prueba
reiniciando el Fire TV. Para quitarlo, desinstala la app.

Está pensado para Fire OS 7 (Android 9). En versiones más nuevas puede hacer falta
`adb shell appops set com.barrix.launcher SYSTEM_ALERT_WINDOW allow`.

## Sobre el botón Home
Amazon no deja cambiar oficialmente la pantalla de inicio. Lo normal es abrir Barrix Home
desde la lista de apps. Si quieres que el botón Home la abra, hay que probar en tu modelo
con una app de reasignación de botones; no está garantizado.

Para volver a lo de antes basta con desinstalarla:

    adb uninstall com.barrix.launcher

## Aviso
Este proyecto se escribió sin poder compilarlo ni probarlo en un Fire TV real. Si la
compilación da algún error, copia el mensaje y se corrige.
