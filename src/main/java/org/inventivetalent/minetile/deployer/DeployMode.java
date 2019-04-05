package org.inventivetalent.minetile.deployer;

public enum DeployMode {

	/**
	 * Copy Everything
	 */
	COMPLETE(true, false, true, true, true),

	/**
	 * Only copy required that are different across servers (world and config)
	 */
	MINIMAL(false, false, true, true, false),

	/**
	 * Update config and copy world data, but create a script for copying server files from a central location instead of copying it into the packages
	 */
	SCRIPT(false, true, true, true, false),

	/**
	 * Only create the config without copying world files
	 */
	CONFIG(false, false, true, false, false);

	public final boolean copyServer;
	public final boolean createInitScript;
	public final boolean updateConfig;
	public final boolean copyWorld;
	public final boolean copyPlugins;

	DeployMode(boolean copyServer, boolean createInitScript, boolean updateConfig, boolean copyWorld, boolean copyPlugins) {
		this.copyServer = copyServer;
		this.createInitScript = createInitScript;
		this.updateConfig = updateConfig;
		this.copyWorld = copyWorld;
		this.copyPlugins = copyPlugins;
	}

}
