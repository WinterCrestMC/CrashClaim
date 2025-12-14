package net.crashcraft.crashclaim.data.providers.sqlite.versions;

import co.aikar.idb.DB;
import java.sql.SQLException;
import net.crashcraft.crashclaim.data.providers.sqlite.DataVersion;

public class DataRev6 implements DataVersion {

    @Override
    public int getVersion() {
        return 6;
    }

    @Override
    public void executeUpgrade(int fromRevision) throws SQLException {
        DB.executeInsert("ALTER TABLE permission_set ADD COLUMN pvp INTEGER NOT NULL DEFAULT 0");
    }
}
