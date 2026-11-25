package net.crashcraft.crashclaim.data.providers.sqlite.versions;

import co.aikar.idb.DB;
import java.sql.SQLException;
import net.crashcraft.crashclaim.data.providers.sqlite.DataVersion;

public class DataRev8 implements DataVersion {

    @Override
    public int getVersion() {
        return 8;
    }

    @Override
    public void executeUpgrade(int fromRevision) throws SQLException {
        DB.executeInsert("ALTER TABLE permission_set ADD COLUMN warps INTEGER NOT NULL DEFAULT 0");
    }
}
