# Validacion concentrada v0.1.0 (Codex local)

## Objetivo
Validar la rama `develop/android-v1-foundation` del repositorio privado `Frosst43/gestor_domestico` con una sola ejecucion de build + tests. No modificar `main`, no fusionar PR #1 ni publicar APK.

## Entorno
Equipo Android de desarrollo con JDK 17, Android SDK API 35 (build-tools 35) y Gradle 8.14. Trabajar en un directorio limpio del repo y en la rama indicada. Sin sudo ni instalaciones globales automaticas. No borrar la base de datos del telefono ni usar datos reales de usuarios.

## Ejecucion unica
1. Comprobar rama, commit, JDK, SDK y Gradle sin ejecutar suites.
2. Ejecutar **una sola vez** `gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug`.
3. Registrar codigo de salida, errores exactos, resultados unitarios, SHA-256 del APK solo si existe y el estado de la rama.
4. Si falla: no repetir ni corregir automaticamente. Devolver causa, archivo/linea y propuesta de cambio acotada para revision.
5. Si pasa: ofrecer el APK debug compilado al usuario y documentar que las pruebas unitarias no sustituyen una prueba en un telefono real.

## Criterios funcionales para probar manualmente una sola vez despues del PASS
- Crear lista, categoria y producto; editar nombre, cantidad y notas.
- Marcar comprado -> tick verde + tachado + al final; desmarcar -> vuelve a pendientes.
- Duplicar -> copia independiente con todos pendientes.
- Completar -> opciones visibles; reutilizar, archivar/recuperar, vaciar y eliminar con confirmacion.
- Comprobar que el contenido persiste al cerrar y reabrir la app.

No crear tests duplicados ni generar entregas de produccion a partir de un APK debug.
