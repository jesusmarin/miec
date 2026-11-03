package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.*;
import java.util.*;

/** Sustituye únicamente las unicidades autogeneradas por ciclo de H2; conserva todas las referencias históricas. */
public class V021__scope_operational_receipts_by_round extends BaseJavaMigration {
    /** Flyway identifica la versión a partir del nombre de esta clase inmutable. */
    public V021__scope_operational_receipts_by_round(){ }
    /** Checksum explícito de esta migración Java, que no se debe modificar una vez publicada. */
    @Override public Integer getChecksum(){return 2026110301;}
    /** Instala primero el reemplazo por ronda/legado y después retira solo la unicidad antigua. */
    @Override public void migrate(Context context) throws Exception {
        var db=context.getConnection();
        if(!"H2".equals(db.getMetaData().getDatabaseProductName()))throw new SQLException("V021 requiere el perfil H2; PostgreSQL necesita validación de migraciones propia");
        execute(db,"ALTER TABLE evaluation ADD CONSTRAINT uq_evaluation_round_plan UNIQUE(evaluation_id,plan_id,cycle_id)");
        execute(db,"""
            CREATE TABLE round_evaluation_binding (
                round_id UUID PRIMARY KEY, cycle_id UUID NOT NULL, plan_id UUID NOT NULL, evaluation_id UUID NOT NULL UNIQUE,
                UNIQUE(round_id,evaluation_id,cycle_id),
                FOREIGN KEY(round_id,plan_id,cycle_id) REFERENCES round_plan_binding(round_id,plan_id,cycle_id),
                FOREIGN KEY(evaluation_id,plan_id,cycle_id) REFERENCES evaluation(evaluation_id,plan_id,cycle_id)
            )
            """);
        execute(db,"INSERT INTO round_evaluation_binding SELECT round_id,cycle_id,plan_id,evaluation_id FROM round_context");
        scope(db,"evaluation_admission","evaluation_id");scope(db,"human_decision","decision_id");
        execute(db,"ALTER TABLE evaluation_admission ADD CONSTRAINT uq_admission_round_context UNIQUE(evaluation_id,round_id,cycle_id)");
        execute(db,"ALTER TABLE human_decision ADD CONSTRAINT fk_human_same_round_admission FOREIGN KEY(evaluation_id,round_id,cycle_id) REFERENCES evaluation_admission(evaluation_id,round_id,cycle_id)");
    }
    /** Asigna solo contextos explícitos ya acreditados; los demás conservan su identidad legada por ciclo. */
    private void scope(Connection db,String table,String identity) throws SQLException {
        execute(db,"ALTER TABLE "+table+" ADD COLUMN round_id UUID");
        execute(db,"ALTER TABLE "+table+" ADD COLUMN legacy_cycle_id UUID");
        execute(db,"UPDATE "+table+" t SET round_id=(SELECT r.round_id FROM round_context r WHERE r."+identity+"=t."+identity+")");
        execute(db,"UPDATE "+table+" SET legacy_cycle_id=cycle_id WHERE round_id IS NULL");
        execute(db,"ALTER TABLE "+table+" ADD CONSTRAINT uq_"+table+"_round UNIQUE(round_id)");
        execute(db,"ALTER TABLE "+table+" ADD CONSTRAINT uq_"+table+"_legacy UNIQUE(legacy_cycle_id)");
        execute(db,"ALTER TABLE "+table+" ADD CONSTRAINT ck_"+table+"_scope CHECK((round_id IS NULL AND legacy_cycle_id IS NOT NULL AND legacy_cycle_id=cycle_id) OR (round_id IS NOT NULL AND legacy_cycle_id IS NULL))");
        execute(db,"ALTER TABLE "+table+" ADD CONSTRAINT fk_"+table+"_round FOREIGN KEY(round_id,evaluation_id,cycle_id) REFERENCES round_evaluation_binding(round_id,evaluation_id,cycle_id)");
        var names=new ArrayList<String>();
        try(var query=db.prepareStatement("""
            SELECT t.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS t
            JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE k ON t.CONSTRAINT_CATALOG=k.CONSTRAINT_CATALOG
                AND t.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME=k.CONSTRAINT_NAME
            WHERE t.TABLE_SCHEMA=CURRENT_SCHEMA() AND t.TABLE_NAME=? AND t.CONSTRAINT_TYPE='UNIQUE'
            GROUP BY t.CONSTRAINT_NAME HAVING COUNT(*)=1 AND MAX(k.COLUMN_NAME)='CYCLE_ID'
            """)){
            query.setString(1,table.toUpperCase(Locale.ROOT));
            try(var rows=query.executeQuery()){while(rows.next())names.add(rows.getString(1));}
        }
        if(names.size()!=1)throw new SQLException("No se identificó inequívocamente la unicidad legada de "+table);
        execute(db,"ALTER TABLE "+table+" DROP CONSTRAINT \""+names.getFirst().replace("\"","\"\"")+"\"");
    }
    /** Ejecuta SQL fijo de migración; los nombres dinámicos proceden solo del catálogo o constantes internas. */
    private void execute(Connection db,String sql) throws SQLException {try(var statement=db.createStatement()){statement.execute(sql);}}
}
