# Servidor/cliente - fase 2: vinculacion y canal TLS LAN

## Estado exacto

Implementados el transporte TLS, la vinculacion de un cliente, una pantalla
Android experimental y el almacenamiento de credenciales. No se han compilado
la app Android, la pantalla ni el adaptador AndroidKeyStore en esta sesion.
No hay APK nuevo, merge, release ni conexion comprobada entre telefonos reales.
Las compras NO se transmiten ni sincronizan: solo se aceptan PAIR y PROBE.

Se conserva la rama develop/android-v1-foundation en 5c16d99. Este bloque vive
en feature/server-client-foundation y conserva la migracion Room 1 -> 2 de la
fase anterior. No altera las tablas de compras ni aplica el journal remoto.

## Implementacion

- LinkProtocol: invitacion versionada, UUID, huella SHA-256 de certificado,
  secreto aleatorio de 256 bits, IPv4 privada y puerto. Sin DNS, IP publica,
  bind comodin, HTTP ni fallback sin cifrar. Loopback solo por opcion explicita
  para los tests JVM, nunca activada desde Android.
- PairingGate: una ventana de 5 minutos con reloj monotono, cinco fallos maximo,
  un cliente autorizado y revocacion. Reintentar la misma solicitud con la misma
  identidad y credencial es idempotente durante la ventana; no admite otro cliente.
- LanTransport: sockets TLS 1.2/1.3 y suites AEAD. Se compara el certificado
  contra la huella de la invitacion ANTES de transmitir credenciales. La identidad
  del servidor depende de recibir esa invitacion por un canal de confianza; no
  se aprende el certificado aceptando una conexion desconocida.
- El cliente genera y guarda su credencial antes de solicitar vinculacion.
  Si se pierde la respuesta, puede comprobarla con PROBE sin crear otra identidad.
  El servidor conserva solo el hash de esa credencial y persiste antes de responder.
- Mensajes limitados a 4096 bytes, dos trabajadores, cola de dos y plazo total de
  diez segundos por conexion. No se promete resistencia absoluta a denegacion de servicio.
- AndroidLinkSecurity: certificado propio y clave privada en AndroidKeyStore;
  estado cifrado AES-GCM, escritura AtomicFile y directorio noBackupFilesDir.
  Los errores de lectura/cifrado NO se convierten en un estado vacio autorizado.
- LinkActivity: servidor/cliente, crear invitacion, comprobar enlace, detener y
  olvidar/revocar. Boton Dispositivos en MainActivity. La Activity no esta exportada.
- Se declara INTERNET. No se cambian minSdk/targetSdk ni las dependencias.

## Limites de la primera pantalla

La transferencia de invitacion es manual y provisional, sin QR, descubrimiento
mDNS ni contactos. El texto es secreto y no debe publicarse en capturas, logs o
repositorios. Los toString de invitacion/cliente no muestran sus secretos.

Solo funciona con esta pantalla visible en ambos dispositivos. Salir, bloquear
el movil o pasar la app al fondo detiene conexiones e invalida la invitacion.
Por tanto, compartir el texto cambiando a otra app en el servidor requiere
volver a arrancar y crear otra invitacion. Resolveremos esa ergonomia con QR o
un flujo de transferencia propio; no se presenta como UX final.

No hay servicio permanente, arranque automatico, apertura de router, acceso
remoto por Internet, notificaciones push ni promesa de disponibilidad 24/7.
Un estado 'vinculado' guardado no significa 'conectado ahora'. Solo una prueba
PROBE reciente verifica el enlace; no verifica sincronizacion de compras.

Se limita a un cliente por servidor. Una segunda vinculacion exige revocar la
anterior. 'Olvidar servidor' en el cliente no revoca la credencial en el servidor:
hay que revocar tambien alli. Revocar no borra compras.

No se restauran identidades, credenciales ni journal desde la copia JSON de compras.
No instalar la rama experimental sobre datos importantes: sigue pendiente la
validacion Android de Room 2 y no hay downgrade 2 -> 1.

## Evidencia obtenida en esta sesion

Una compilacion kotlinc-jvm 1.9.0/JRE 21 y una ejecucion del contrato de red:
**8/8 escenarios PASS**. Se compilaron los tres archivos reales del transporte,
sin dobles para TLS, y se abrieron conexiones SSLSocket/SSLServerSocket en
127.0.0.1 con un certificado temporal de laboratorio.

1. Invitacion, limites LAN y secretos omitidos en toString.
2. Caducidad monotona y cierre tras cinco intentos fallidos.
3. Fallo de persistencia, reintento idempotente y autorizacion de un solo cliente.
4. Mensajes sobredimensionados o incompletos rechazados.
5. Vinculacion real TLS y comprobaciones TLS 1.3/1.2.
6. Certificado, token, identidad incorrectos, texto sin TLS y SYNC rechazados.
7. Reinicio del servidor conservando un peer en un almacen de prueba en disco;
   rechazo de otro cliente con invitacion antigua y de una llamada cancelada.
8. Credencial anterior rechazada despues de revocar.

El almacen en disco del test NO es AndroidLinkStore: su cifrado, AtomicFile,
AndroidKeyStore, la pantalla y el ciclo de vida requieren pruebas Android.
No mezclar estos 8 escenarios con los 12 tests de la base o los 6 de SQLite.
No se ejecutaron otra vez esas suites anteriores.

Reproduccion, con Java, keytool y kotlinc ya disponibles:

```bash
bash scripts/check_lan_link.sh
```

No descarga herramientas. El certificado privado de test se crea en un temporal
y se elimina al terminar. No se reutiliza en dispositivos ni se sube a GitHub.

## Siguiente bloque necesario

Aplicar cambios remotos de manera transaccional, con ID de operacion, ACK
persistente, deduplicacion, control de versiones/conflictos y supresion de eco.
Definir el intercambio inicial y el efecto de restaurar copias dentro de un grupo.
Hasta completar esas reglas, no exponer ningun endpoint de datos de compras.

## Referencias de implementacion

- https://developer.android.com/privacy-and-security/security-ssl
- https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec
- https://developer.android.com/privacy-and-security/keystore
- https://developer.android.com/privacy-and-security/local-network-permission

El proyecto sigue con targetSdk 35. Al elevarlo a 37 o superior hay que integrar
ACCESS_LOCAL_NETWORK y revisar los permisos segun la documentacion oficial.
Los fallos por permisos o restricciones OEM se muestran como conexion fallida,
no se eluden abriendo puertos, aceptando certificados cualquiera o desactivando TLS.
