package com.miec.orchestration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import static com.miec.orchestration.LifecycleException.Kind.BAD_REQUEST;

/** Posicion de lectura versionada y ligada al contexto; no es una credencial ni una autorizacion. */
final class NavigationCursor {
    /** Captura la clave inmutable del registro que ancla la pagina siguiente. */
    record Position(Instant createdAt, UUID id) { }
    /** Evita crear un codec con estado o secretos de sesion. */
    private NavigationCursor() { }

    /** Identifica ambito y filtros normalizados; limit puede cambiar entre paginas. */
    static String context(String... values) {
        try {
            var text = new StringBuilder();
            for (String value : values) text.append(Objects.toString(value, "")).append('\0');
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 no disponible", impossible); }
    }

    /** Codifica exclusivamente el ancla, la version del contrato y la huella de filtros. */
    static String encode(String context, Instant createdAt, UUID id) {
        String value = "MVP09-1\n" + context + "\n" + createdAt + "\n" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Rechaza tokens malformados o de otro contexto antes de acceder al ancla persistida. */
    static Position decode(String token, String context) {
        if (token == null) return null;
        if (token.isEmpty() || token.length() > 512) throw invalid();
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\n", -1);
            if (parts.length != 4 || !"MVP09-1".equals(parts[0]) || !context.equals(parts[1])) throw invalid();
            return new Position(Instant.parse(parts[2]), UUID.fromString(parts[3]));
        } catch (IllegalArgumentException | DateTimeException malformed) { throw invalid(); }
    }

    /** Error estable sin incluir el contenido rechazado o detalles SQL. */
    static LifecycleException invalid() {
        return new LifecycleException(BAD_REQUEST, "INVALID_CURSOR", "El cursor no es valido para este ambito y sus filtros");
    }
}
