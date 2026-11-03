package com.miec.action;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;

/** Único algoritmo MVP-PLAN-01 compartido por aprobación y admisión; mantiene las huellas publicadas. */
public final class PlannedContentFingerprint {
    /** Utilidad no instanciable. */
    private PlannedContentFingerprint() { }
    /** Calcula SHA-256 de los campos aprobados con longitudes UTF-8 y distinción entre null y vacío. */
    public static String of(ResultSet rs) throws SQLException {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            var fields=new ArrayList<String>(); fields.add("MVP-PLAN-01");
            for(String column:new String[]{"plan_id","cycle_id","objective_ids","hypothesis_ids","actions",
                    "expected_outcomes","risk_assessment_id","rollback_strategy","authorization_requirements",
                    "version_id","created_by","diagnosis_id"}) fields.add(rs.getString(column));
            for(String field:fields) {
                byte[] bytes=field==null?new byte[0]:field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(field==null?-1:bytes.length).array());digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 no disponible",impossible); }
    }
}
