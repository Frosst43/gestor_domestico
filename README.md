# Gestor doméstico

Aplicación Android para compras universales, no limitada a alimentación.

**Organización:** listas independientes → categorías → productos. No existe un límite artificial de listas ni de categorías. Nombres como «Compra semanal», «Componentes PC» y «Necesidades casa» son ejemplos, no listas obligatorias.

## Base de desarrollo 0.1.0

- Crear y renombrar listas y categorías.
- Apariencia automática según Android, u opción manual de tema oscuro/claro, guardada localmente.
- Cabecera compatible con la barra de estado de Android; títulos largos abreviados sin solaparse.
- Mensajes específicos cuando una búsqueda no devuelve resultados.
- Crear y editar productos (nombre, cantidad/unidad, notas).
- Buscar listas, categorías y productos (por nombre o notas).
- Al completar una compra, acceso visible a reutilizar, archivar o eliminar.
- Marcar comprado con casilla verde y tachado visible: los comprados pasan al final de su categoría.
- Progreso y aviso de compra completada solo cuando hay productos y todos están comprados.
- Duplicar lista: se copian sus categorías y productos, que comienzan todos pendientes.
- Archivar/recuperar listas, reutilizar (desmarcar), vaciar comprados y eliminar.
- Confirmación antes de borrar listas, categorías o productos y antes de limpiar/restablecer.
- Datos locales Room/SQLite sin acceso a Internet.

## Compilación

Abrir la carpeta raíz en Android Studio con JDK 17 y SDK 35. Para la línea de comandos se necesita Gradle 8.14 (la rama todavía no incorpora los binarios del wrapper); ejecutar `gradle :app:testDebugUnitTest :app:assembleDebug`. El flujo GitHub Actions realiza exactamente esta compilación y publica un APK debug como artefacto **solo si termina correctamente**.

## Seguridad y restricciones

La app no necesita permisos de red. El respaldo automático de Android está deshabilitado por el momento: desinstalar o borrar datos de la app elimina la base local. Antes de uso con datos importantes implementaremos exportación/restauración y copia de seguridad verificable.

## Pendiente

Compilación y prueba con dispositivo real; mejoras de accesibilidad, buscador y recuperación/exportación; sincronización entre dos móviles, gastos, presupuestos y estadísticas para versiones futuras. No fusionar en `main` sin validación.
