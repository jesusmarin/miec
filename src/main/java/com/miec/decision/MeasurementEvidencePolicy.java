package com.miec.decision;

import com.miec.evidence.*;
import java.time.Instant;
import java.util.*;

/**
 * SOURCE-QUALITY-V1: atestación humana no equivale a verificación instrumental ni EV-2.
 * Solo admite muestras HUMAN_REPORT/UNVERIFIED. Una revisión aceptada no eleva su
 * suficiencia; conserva EV-1 salvo ausencia de pares o rechazo seleccionado (EV-0).
 * Ni la suficiencia ni una diferencia numérica constituyen una atribución causal.
 */
public final class MeasurementEvidencePolicy {
    /** Clasifica sin reloj y preserva limitaciones, revisiones ausentes y rechazos. */
    public EvidenceAssessment assess(ClosedSetEvaluation.Snapshot snapshot,Instant assessedAt) {
        Objects.requireNonNull(snapshot);Objects.requireNonNull(assessedAt);
        if(assessedAt.isBefore(snapshot.sealedAt())) throw new IllegalArgumentException("Evaluación anterior al cierre");
        var proofs=new TreeSet<UUID>();var limitations=new LinkedHashSet<String>();var uncertainties=new LinkedHashSet<String>();
        limitations.add("Comparación de pares explícitos; no acredita causalidad ni efecto poblacional");
        limitations.add("Fuentes humanas UNVERIFIED; no hay autenticación instrumental acreditada");
        boolean rejected=false;
        for(var pair:snapshot.pairs()) for(var sample:List.of(pair.baseline(),pair.post())) {
            if(!"HUMAN_REPORT".equals(sample.sourceType())||!"UNVERIFIED".equals(sample.quality()))
                throw new IllegalArgumentException("Fuente no soportada por SOURCE-QUALITY-V1");
            proofs.add(sample.evidenceId());var review=sample.review();
            if(review==null) uncertainties.add("Revisión seleccionada ausente para evidencia "+sample.evidenceId());
            else {
                limitations.add(review.limitations());
                if("REJECTED".equals(review.verdict())){rejected=true;uncertainties.add("Muestra rechazada por revisión "+review.id());}
            }
        }
        var level=snapshot.pairs().isEmpty()||rejected?EvidenceSufficiency.NOT_EVALUABLE:EvidenceSufficiency.INSUFFICIENT;
        return new EvidenceAssessment(level,List.copyOf(proofs),List.copyOf(limitations),List.copyOf(uncertainties),
                rejected?"Existe evidencia seleccionada rechazada":snapshot.pairs().isEmpty()?"No hay pares seleccionados":"La revisión humana no acredita suficiencia instrumental",assessedAt);
    }
}
