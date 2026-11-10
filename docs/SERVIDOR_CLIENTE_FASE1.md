# Servidor/cliente - fase 1: guardado local y registro de cambios

## Estado real

Codigo experimental en rama separada. No hay servidor escuchando, transporte,
vinculacion ni intercambio entre telefonos todavia. No se anuncia conectado.
No se ha generado APK de esta rama ni validado Room en Android.
No fusionar con main o develop/android-v1-foundation por ahora.

El punto de partida 5c16d99 tiene BUILD_AND_UNIT_TESTS=PASS y 12/12 tests,
segun la salida del usuario. APK SHA-256:
91bdd1bcb041bdaab37bebf829830d5d6a0d472d5f09b38f465a10e9abc5b1be
Ese PASS no se transfiere a estos cambios nuevos.

## Correccion de formularios

ShoppingSession conserva navegacion, busqueda, formulario normal y alta rapida
mediante un ViewModel. No contiene Activity ni vistas. Los guardados no se
cancelan al girar la pantalla: se cierran o limpian los campos solo despues
del exito. Mientras se guarda, campos y botones de envio quedan bloqueados.
Los errores dejan el texto en el formulario. Cancelaciones no se disfrazan de
fallos de guardado. La restauracion tambien espera el resultado y mantiene
su confirmacion bloqueada mientras trabaja.

Alcance exacto: recreacion de Activity, no muerte del proceso, force-stop,
reinicio del telefono ni borradores persistentes en disco. No se serializan
notas grandes en el Bundle. La escritura del archivo de backup sigue ligada
al ciclo de vida de su pantalla y requiere una comprobacion especifica.

## Primer bloque de sincronizacion

- Identidad UUID local estable en la base de datos; no es una credencial.
- Journal append-only con secuencia monotona, tipo de entidad, ID, padre,
  operacion UPSERT/DELETE e instantanea de los campos necesarios.
- Triggers para listas, categorias y productos: registran las escrituras
  actuales sin que cada boton tenga que acordarse de notificar al sincronizador.
- Tambien registran borrados en cascada y comparten la transaccion de datos.
  Si la escritura falla, su evento tampoco se confirma.
- Migracion Room 1 -> 2 que agrega tablas y registra las compras existentes
  sin modificar las filas de compras. Instalaciones nuevas usan callback.
- Lectura transaccional por bloques, cursor y limite de 1 a 200 eventos.
  El cursor no es confirmacion de recepcion ni permiso para purgar eventos.

No se usan fechas de los moviles para resolver conflictos. updatedAt conserva
el valor del registro de origen, incluso en un borrado; el orden local lo da
sequence. La identidad de una operacion futura sera deviceId + sequence.
Una identidad de dispositivo NO autoriza a conectarse ni sustituye a TLS.

## Comprobacion ejecutada en esta sesion

Se compilo SyncSql.kt junto con tools/EmitSyncSql.kt con kotlinc-jvm 1.9.0,
JRE 21. El emisor produjo el SQL real que usaran los callbacks de Room.
Ese SQL se ejecuto con Python/SQLite 3.46.1: una suite, 6/6 PASS.

1. Migracion conserva las filas y registra la instantanea inicial.
2. Altas, edicion de comprado y borrados en cascada quedan en el journal.
3. Una transaccion fallida revierte datos y eventos juntos.
4. Falta de identidad bloquea una escritura en vez de perder el evento.
5. Lectura por paginas sin duplicar secuencias.
6. Identidad, journal y triggers sobreviven cerrar/reabrir la base.

Esto NO es una compilacion Android, ni prueba de Room generado, ni de dos
moviles. Quedan pendientes el build concentrado, la validacion de esquema
Room/migracion en Android y la prueba de rotacion/guardados.

Reproduccion solo en un entorno de desarrollo con kotlinc, Java y Python:

```bash
mkdir -p .local-tools/sync-check
kotlinc app/src/main/java/com/frosst/gestordomestico/SyncSql.kt tools/EmitSyncSql.kt -include-runtime -d .local-tools/sync-check/sql.jar
java -jar .local-tools/sync-check/sql.jar > .local-tools/sync-check/sql.txt
python3 scripts/check_sync_journal.py .local-tools/sync-check/sql.txt
```

No instalar herramientas ni repetir esta suite por rutina. El script de
validacion V1 existente exige su rama original; no saltarse esa proteccion
para compilar esta rama sin preparar primero su validacion especifica.

## Limites que deben resolverse antes de conectar

- No hay peers, ACKs, revision autoritativa del servidor, deduplicacion del
  receptor, resolucion de conflictos ni supresion de eco para cambios remotos.
- No hay TLS, emparejamiento, servicio servidor ni conectividad fuera de LAN.
- El journal no tiene purga automatica todavia y puede crecer. Puede conservar
  textos de versiones anteriores aunque el producto se haya borrado.
- Las copias JSON exportan solo compras, no identidad ni journal. No usar
  clonacion de la base completa para aprovisionar otro dispositivo.
- Una restauracion manual genera borrados/altas locales. Antes de habilitar
  grupos compartidos se debe bloquear o disenar explicitamente su propagacion.
- No se ha definido una migracion inversa 2 -> 1. No probar esta rama sobre
  datos importantes ni intentar volver al APK anterior borrando la aplicacion.

## Siguiente entrega funcional

Vinculacion autorizada y canal cifrado para dos dispositivos de la misma LAN,
con ambos abiertos como primer escenario. Despues, protocolo push/pull con
ACK persistente, deduplicacion, eliminaciones y conflictos. No habilitar el
listener hasta cerrar autenticacion y tratamiento de cambios remotos.
La conexion remota y el segundo plano vienen despues de ese primer enlace;
la app no debe confundir cambios pendientes con datos ya sincronizados.
