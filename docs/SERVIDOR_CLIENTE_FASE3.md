# Fase 3 - intercambio manual de compras por TLS

Estado: codigo experimental. Motor y transporte comprobados en JVM con dos bases
SQLite reales en archivos. Integracion Android escrita, SIN build Android ni dos
telefonos comprobados en esta fase. No fusionar ni anunciar V1 compartida terminada.

## Alcance implementado

- Mensaje autenticado SHOPPING-1 sobre el TLS con huella fijada de la fase 2.
  PAIR y PROBE no se convierten por si solos en permiso para transferir datos:
  el servidor debe habilitar explicitamente compartir todas sus listas.
- Primera copia servidor -> cliente vacio. Un cliente con compras previas se
  rechaza sin borrarlas. No se usa restoreBackup para sincronizar.
- Mutaciones en ambos sentidos: altas, ediciones, compras, archivo y eliminaciones.
  Se conservan cantidades, notas y fechas. Las listas archivadas tambien se comparten.
- Outbox persistente: antes de la red se guarda la peticion exacta con UUID de
  operacion y cursor del journal local. Tras un error se reenvia ESA peticion.
- El servidor guarda la aplicacion y su recibo en la misma transaccion. Un mismo
  UUID con el mismo contenido devuelve el recibo original; con otro contenido se
  rechaza. La respuesta se envia solo despues del commit.
- Revisiones autoritativas del servidor por registro y marcas de borrado. No se
  elige un ganador por la hora del telefono. Una edicion sobre una revision vieja
  produce conflicto; una fila borrada no se resucita con su identificador antiguo.
- Un lote conflictivo no aplica parcialmente sus mutaciones en el servidor.
  Conflicto y lote quedan persistidos en el cliente sin pisar su version local.
- Si se edita durante el intercambio, se conserva el cambio posterior y se envia
  en el siguiente lote. Si choca con un cambio remoto, se bloquea la aplicacion
  local del resultado y se guarda el conflicto, sin descartar el texto local.
- Cambios remotos bajo una bandera transaccional: no vuelven a entrar en el
  journal local como si fueran cambios nuevos. Los triggers del servidor si
  asignan revisiones autoritativas a los cambios aceptados.
- Eliminacion de una categoria/lista exige incluir los hijos conocidos. Si el
  servidor ha recibido un hijo nuevo que el cliente no conocia, se rechaza la
  eliminacion en vez de borrarlo por cascada sin consentimiento.
- Revalidacion de autorizacion despues de leer el payload y serializacion de
  la autorizacion de commit con la revocacion. No se mantiene ese bloqueo
  mientras se esperan bytes de red.

## Modelo de intercambio y limites deliberados

No se reproducen uno por uno todos los eventos historicos del journal. El cursor
identifica el punto local al preparar un envio; se compacta la diferencia entre
los datos actuales y la ultima instantanea confirmada. El servidor responde con
una instantanea completa y acotada, incluidas las marcas de borrado. Esta primera
implementacion NO es un motor paginado para bases arbitrariamente grandes.

Limites de este transporte (distintos de los de copia JSON):
- 2000 registros, contando marcas de borrado, y 2000 mutaciones por lote.
- 2 MiB por mensaje; 65536 bytes UTF-8 por campo de texto.
- 2048 recibos y 64 MiB de recibos por servidor. Al alcanzar un limite se rechaza
  la operacion y se conserva el lote; no se purgan ni truncan datos en silencio.
- La fase 2 conserva sus limites de conexiones, tiempos de espera, IPv4 privada,
  TLS y verificacion de identidad. No hay Internet remoto ni fallback inseguro.

## Persistencia Android

Nuevas entidades Room: gd_exchange_state, gd_exchange_versions,
gd_exchange_client y gd_exchange_receipts. Migracion explicita 2 -> 3 y callback
para instalaciones nuevas. Se conserva 1 -> 2: un APK v1 puede migrar 1 -> 2 -> 3.
El motor usa una interfaz transaccional SQL. RoomExchangeDatabase la conecta con
RoomDatabase.runInTransaction y SupportSQLiteDatabase, sin bibliotecas nuevas.

Data.kt bloquea restauraciones JSON si la base ya es HOST o CLIENT. Una copia de
compras no incluye versiones, identidad, outbox ni recibos y no debe resetear un
grupo. La exportacion sigue disponible. Aun falta el flujo seguro para salir o
cambiar de grupo; olvidar la credencial NO borra el grupo ni el envio pendiente.
No hay downgrade 3 -> 2 -> 1 y no se usa fallback destructivo.

La pantalla Dispositivos incluye confirmaciones de compartir/recibir compras y
un boton Sincronizar compras ahora, con estados de pendiente/conflicto. Se mantiene
la sesion solo en esa pantalla; al salir o pasar al fondo se detiene. Las compras
se editan fuera de ella y se intercambian al volver y arrancar ambos extremos.
No es sincronizacion automatica ni un servidor permanente.

## Verificacion efectuada

Una compilacion Kotlin/JVM de los fuentes reales del codec, SQL, motor y transporte,
junto con tools/ExchangeContract.kt; una ejecucion del contrato. JDK 21,
kotlinc-jvm 1.9.0, destino JVM 17, SQLite 3.46.1. **12/12 escenarios PASS**:

1. Codec, Unicode, duplicados, limites y contenido sobrante.
2. Migracion SQL 2 -> 3 sin perder compras, identidad o journal.
3. Bootstrap por TLS y ausencia de eco en el journal.
4. Cambios disjuntos bidireccionales y convergencia.
5. Respuesta perdida tras commit, reinicio de ambos extremos, reenvio exacto,
   recibo persistente y reaplicacion idempotente de la respuesta en el cliente.
6. Edicion simultanea del mismo producto: ambas versiones se conservan.
7. Borrado frente a edicion offline; propagacion de cascadas sin resurreccion.
8. Borrado de padre con hijo nuevo desconocido: rechazo sin perdida.
9. Edicion durante un envio: conservacion y envio posterior.
10. Fallo inyectado antes del recibo: rollback de datos, revisiones y bandera.
11. Grupo incorrecto, UUID reutilizado con otro contenido, cliente inicial no vacio.
12. Certificado, credencial y revocacion en el endpoint real de compras.

El puente tools/exchange_sqlite_bridge.py solo es instrumental de laboratorio:
una conexion SQLite por archivo, PRAGMA foreign_keys=ON y BEGIN IMMEDIATE. Ejecuta
las mismas sentencias que usa el motor de produccion; no sustituye el motor por
una simulacion en memoria. Los sockets TLS son reales, en loopback. La clave de
certificado de prueba, credenciales y bases temporales se eliminan al terminar.
No se repitieron las suites anteriores 12/12 de la app local ni 8/8 de vinculacion.

Reproduccion voluntaria, no es necesario ejecutarla ahora en el ordenador del usuario:

```bash
bash scripts/check_shopping_exchange.sh
```

Requiere herramientas ya disponibles: Kotlin, Java/keytool, Python 3 y sha256sum.
No descarga ni instala nada. Conserva log, entorno y hashes en .local-tools.

## Lo que NO queda validado o terminado

- Compilacion de APK, KAPT/Room generado, migracion validada por Room, refresco de
  Compose, AndroidKeyStore y comportamiento sobre telefonos reales.
- El conflicto se detecta y conserva, pero falta la pantalla para elegir/combinar
  versiones y desbloquearlo. No resolverlo borrando datos, cambiando UUIDs a mano
  o olvidando credenciales. Un servidor pudo aceptar el lote antes de que surgiera
  un conflicto de reconciliacion con ediciones locales posteriores.
- Vinculacion por QR, comodidad al compartir la invitacion, disponibilidad al
  salir de la pantalla, reconexion automatica y comunicacion fuera de la LAN.
- Paginacion, retencion de recibos/journal, cambio seguro de grupo y recuperacion
  compartida. Los historiales y recibos retienen textos anteriores en datos
  privados de la aplicacion; la exportacion JSON sigue sin estar cifrada.

Siguiente bloque: interfaz y accion transaccional de resolucion de conflictos,
mejorar el emparejamiento, y una compilacion concentrada de la rama experimental.
Prueba en dos telefonos despues, con datos prescindibles y sin tocar la base V1.
