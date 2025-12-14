package net.crashcraft.crashclaim.data.providers.sqlite.versions;

import co.aikar.idb.DB;
import java.sql.SQLException;
import net.crashcraft.crashclaim.data.providers.sqlite.DataVersion;

public class DataRev7 implements DataVersion {

    @Override
    public int getVersion() {
        return 7;
    }

    @Override
    public void executeUpgrade(int fromRevision) throws SQLException {
        DB.executeInsert("CREATE TABLE banned_players(data_id INTEGER NOT NULL, banned_uuid TEXT NOT NULL, PRIMARY KEY(data_id, banned_uuid))");
    }
}
