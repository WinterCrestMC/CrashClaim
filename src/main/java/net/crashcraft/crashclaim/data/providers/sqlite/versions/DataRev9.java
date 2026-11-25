package net.crashcraft.crashclaim.data.providers.sqlite.versions;

import co.aikar.idb.DB;
import co.aikar.idb.DbStatement;
import java.sql.SQLException;
import net.crashcraft.crashclaim.data.providers.sqlite.DataVersion;

public class DataRev9 implements DataVersion {

    private static final String REFERENCED_CLAIM_DATA = """
            SELECT data FROM claims
            UNION
            SELECT data FROM subclaims
            """;

    @Override
    public int getVersion() {
        return 9;
    }

    @Override
    public void executeUpgrade(int fromRevision) throws SQLException {
        boolean cleaned = DB.createTransaction(statement -> {
            statement.executeUpdateQuery("""
                    INSERT OR IGNORE INTO players(id, uuid)
                    VALUES (-1, '00000000-0000-0000-0000-000000000000')
                    """);

            statement.executeUpdateQuery("""
                    DELETE FROM subclaims
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM claim_data
                        INNER JOIN claimworlds ON claimworlds.id = claim_data.world
                        WHERE claim_data.id = subclaims.data
                    ) OR NOT EXISTS (
                        SELECT 1
                        FROM claims
                        INNER JOIN claim_data ON claim_data.id = claims.data
                        INNER JOIN claimworlds ON claimworlds.id = claim_data.world
                        INNER JOIN players ON players.id = claims.players_id
                        WHERE claims.id = subclaims.claim_id
                    )
                    """);

            statement.executeUpdateQuery("""
                    DELETE FROM claims
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM claim_data
                        INNER JOIN claimworlds ON claimworlds.id = claim_data.world
                        WHERE claim_data.id = claims.data
                    ) OR NOT EXISTS (
                        SELECT 1
                        FROM players
                        WHERE players.id = claims.players_id
                    )
                    """);

            removeInvalidDependentData(statement, "permission_containers", "data_id");
            removeInvalidDependentData(statement, "permission_set", "data_id");
            removeInvalidDependentData(statement, "contributions", "data_id");
            removeInvalidDependentData(statement, "banned_players", "data_id");

            statement.executeUpdateQuery("""
                    DELETE FROM permission_containers
                    WHERE NOT EXISTS (SELECT 1 FROM players WHERE players.id = permission_containers.player_id)
                       OR NOT EXISTS (SELECT 1 FROM permissioncontainers WHERE permissioncontainers.id = permission_containers.container)
                    """);
            statement.executeUpdateQuery("""
                    DELETE FROM permission_set
                    WHERE NOT EXISTS (SELECT 1 FROM players WHERE players.id = permission_set.players_id)
                    """);
            statement.executeUpdateQuery("""
                    DELETE FROM contributions
                    WHERE NOT EXISTS (SELECT 1 FROM players WHERE players.id = contributions.players_id)
                    """);

            statement.executeUpdateQuery("""
                    DELETE FROM claim_data
                    WHERE id NOT IN (
                    """ + REFERENCED_CLAIM_DATA + ")");

            return true;
        });

        if (!cleaned) {
            throw new SQLException("Failed to clean orphaned claim data");
        }
    }

    private void removeInvalidDependentData(
            DbStatement statement,
            String table,
            String dataColumn
    ) throws SQLException {
        statement.executeUpdateQuery(
                "DELETE FROM " + table + " WHERE " + dataColumn + " NOT IN (" + REFERENCED_CLAIM_DATA + ")"
        );
    }
}
