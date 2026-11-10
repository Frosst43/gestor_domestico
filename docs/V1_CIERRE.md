# Gestor Domestico - cierre de V1

## Alcance congelado

V1 local: listas -> categorias -> productos; edicion, cantidades y notas;
busqueda; comprado con tick verde, tachado y al final; progreso;
duplicar, reutilizar, vaciar comprados, archivar, recuperar y eliminar;
tema automatico/oscuro/claro; entrada rapida; copia/restauracion manual.
No incluye todavia comunicacion entre moviles, cuentas ni gastos.
No ampliar el alcance antes de validar este conjunto.

## Evidencia disponible

El usuario informo BUILD PASS y 3/3 tests para c13e16a e instalo ese APK.
Los cambios posteriores, incluido este cierre, NO tienen aun una compilacion
Android ni una prueba en dispositivo confirmadas. main sigue sin fusionar.
La version del proyecto no se ha cambiado artificialmente a 1.0 para darlo por terminado.

## Correcciones de esta revision

- Version y fechas de backup deben ser enteros exactos, sin truncar decimales
  ni permitir desbordamientos. Se rechazan fechas negativas tambien al exportar.
- Rechazo de bytes UTF-8 danados, contenido extra y anidamiento excesivo.
- Los textos largos que admite el editor no bloquean la copia por limites
  ocultos de 500/8192 caracteres. Se mantiene un maximo total de 20 MiB.
- Referencias de categorias/productos verificadas usando conjuntos.
- restoreBackup valida de nuevo una instantanea antes de borrar datos,
  manteniendo la transaccion Room y sin cambiar el esquema de base de datos.
- Cuatro pruebas focalizadas nuevas en BackupCodecTest: nueve en ese archivo
  y tres en ShoppingRulesTest (12 definidas, no declaradas PASS).

Limites de backup: 20 MiB, 5000 listas, 25000 categorias y 100000 productos.
El JSON no esta cifrado. Una copia vacia valida puede vaciar la base tras
confirmacion: no restaurar archivos de prueba sobre datos importantes.

## Una validacion local

Ejecutar desde el repositorio, con las herramientas que ya preparo Qwen:

```bash
bash scripts/validate_v1.sh
```

No instala herramientas, no borra datos, no hace clean, commit, push ni retry.
Guarda entorno, cambios locales, log, XML de tests, firma y SHA-256 del APK
bajo .local-tools/validation-v1-*. Si falta algo, informa y se detiene.
El script exige la rama develop/android-v1-foundation para evitar validar main
por error. Un PASS automatico NO cierra la comprobacion en el telefono.

## Comprobacion concentrada en Android

Actualizar la app existente con la misma firma, sin desinstalar. Usar solo
datos de prueba. Verificar una vez:

1. Cabecera sin solaparse y temas automatico, claro y oscuro.
2. Crear/editar y buscar; alta rapida de varios productos; marcar/desmarcar,
   tick y tachado, comprados al final; cerrar/reabrir conserva los datos.
3. Duplicar con todos pendientes; reutilizar, archivar/recuperar, vaciar y borrar
   solo la lista de prueba con confirmacion.
4. Exportar; cambiar un producto de prueba; abrir la copia y cancelar (sin
   cambios); abrir de nuevo y restaurar (recupera el contenido exportado).

Anotar tambien cualquier perdida de borrador al girar el movil o cambiar el
tema mientras se edita: el estado actual usa remember y requiere revision
antes de declarar estabilidad completa. Los fallos se corrigen de forma
acotada, sin repetir suites por rutina.

## Criterio de cierre

No fusionar, etiquetar v1.0 ni declarar V1 terminada sin build y pruebas
correctos, verificacion manual de datos y copia/restauracion, y resolucion de
los fallos bloqueantes encontrados. Tras esos resultados se prepara la
version final y se decide la integracion. La siguiente ampliacion funcional
es la comunicacion entre dos moviles, no la contabilidad domestica.
