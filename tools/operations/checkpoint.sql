-- Checkpoint de laboratorio: solo lectura de tablas public y estado de secuencias.
-- Ejecutar con psql -X -qAt; requiere ausencia coordinada de escritores.
-- MD5 compara contenido, no acredita autenticidad ni sustituye SHA-256 del dump.
\set ON_ERROR_STOP on
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL TIME ZONE 'UTC';
SELECT format(
    'SELECT %L || ''|'' || count(*) || ''|'' || coalesce(md5(string_agg(h, '''' ORDER BY h)), md5('''')) FROM (SELECT md5(to_jsonb(t)::text) h FROM %I.%I t) r;',
    'table:' || schemaname || '.' || tablename, schemaname, tablename
)
FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename
\gexec
SELECT format(
    'SELECT %L || ''|'' || last_value || ''|'' || is_called FROM %I.%I;',
    'sequence:' || schemaname || '.' || sequencename, schemaname, sequencename
)
FROM pg_sequences WHERE schemaname = 'public' ORDER BY sequencename
\gexec
COMMIT;
