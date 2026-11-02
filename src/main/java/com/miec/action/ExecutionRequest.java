package com.miec.action;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Solicitud inmutable UTF8-V1. Su representación canónica conserva exactamente
 * el cuerpo UTF-8; no equipara documentos JSON con distinto orden o espacios.
 * El adaptador debe enviar bodyBytes(), no reconstruir el cuerpo desde otro estado.
 */
public record ExecutionRequest(String destinationId,String mediaType,String body) {
    /** Valida tamaño y normaliza metadatos sin alterar el contenido que se enviará. */
    public ExecutionRequest {
        destinationId=ExecutionRecoveryAccess.text(destinationId,255);
        mediaType=ExecutionRecoveryAccess.text(mediaType,255).toLowerCase(Locale.ROOT);
        Objects.requireNonNull(body);
        if(body.getBytes(StandardCharsets.UTF_8).length>1_048_576) throw new IllegalArgumentException("Cuerpo superior a 1 MiB");
    }

    /** Devuelve bytes nuevos para que el adaptador no pueda modificar el objeto original. */
    public byte[] bodyBytes() { return body.getBytes(StandardCharsets.UTF_8); }

    /** Calcula SHA-256 con versión y campos UTF-8 enmarcados por longitud, sin ambigüedad de separadores. */
    public String fingerprint() {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(String value:new String[]{"UTF8-V1",destinationId,mediaType,body}) {
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 no disponible",impossible); }
    }
}
