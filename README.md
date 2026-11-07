# Gestor domestico (Android)

Aplicacion de listas de compra universales. Estructura: **listas -> categorias -> productos**.

## Estado
Implementacion Android inicial en rama de desarrollo. Sin APK verificado ni pruebas en movil aun.

## Funciones incluidas en esta base
- Listas ilimitadas, categorias personalizadas y productos con cantidad.
- Marcar/desmarcar comprado: marca verde, texto tachado y productos al final.
- Progreso por lista y aviso de compra completada.
- Duplicar una lista con sus categorias y productos (la copia arranca pendiente).
- Archivar, recuperar, reutilizar, vaciar comprados, eliminar listas/categorias/productos.
- Confirmacion para operaciones destructivas.
- Datos locales Room/SQLite, sin permisos de Internet.

## Compilacion
Abrir la carpeta raiz en Android Studio, usando JDK 17 y Android SDK 35. Sincronizar Gradle y ejecutar el modulo `app`. Si se usa terminal, generar el Gradle wrapper desde una instalacion confiable con `gradle wrapper --gradle-version 8.14`; no se ha incluido un JAR binario de wrapper.

## Pendiente antes de calificarla como v1.0
- Compilacion y pruebas reales en dispositivo Android.
- Editar nombres, notas de productos, orden manual, buscador y mejoras de accesibilidad.
- Pruebas automatizadas y exportacion/copia de seguridad.
- Sincronizacion entre dos moviles, control de gastos y presupuestos: futuras versiones.

## Politica de datos
No subir bases de datos personales, credenciales ni archivos APK privados al repositorio.
