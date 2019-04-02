package org.inventivetalent.minetile.deployer;

public enum DeployMode {

	/**
	 * Copy Everything
	 */
	COMPLETE(true, false, true, true),

	/**
	 * Only copy required that are different accross servers (world and config)
	 */
	MINIMAL(false, false, true, true),

	/**
	 * Update config and copy world data, but create a script for copying server files from a central location instead of copying it into the packages
	 */
	SCRIPT(false, true, true, true),

	/**
	 * Only create the config without copying world files
	 */
	CONFIG(false, false, true, false);

	public final boolean copyServer;
	public final boolean createServerScript;
	public final boolean updateConfig;
	public final boolean copyWorld;

	DeployMode(boolean copyServer, boolean createServerScript, boolean updateConfig, boolean copyWorld) {
		this.copyServer = copyServer;
		this.createServerScript = createServerScript;
		this.updateConfig = updateConfig;
		this.copyWorld = copyWorld;
	}

}
