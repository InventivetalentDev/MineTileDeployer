package org.inventivetalent.minetile.deployer;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.inventivetalent.nbt.*;
import org.inventivetalent.nbt.stream.NBTInputStream;
import org.inventivetalent.nbt.stream.NBTOutputStream;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@CommandLine.Command(name = "MineTileDeployer",
					 description = "Utility to split up large worlds into individual MineTile containers for easy deploying",
					 abbreviateSynopsis = true,
					 versionProvider = VersionProvider.class,
					 showDefaultValues = true,
					 headerHeading = "@|bold,underline Usage|@:%n%n",
					 synopsisHeading = "%n",
					 descriptionHeading = "%n@|bold,underline Description|@:%n%n",
					 parameterListHeading = "%n@|bold,underline Parameters|@:%n",
					 optionListHeading = "%n@|bold,underline Options|@:%n")
public class Deployer implements Callable<Boolean> {

	static final String DEFAULT_NAME_FORMAT = "MineTile.%x.%z";

	@CommandLine.Option(names = { "-V", "--version" },
						versionHelp = true,
						description = "Display version info")
	boolean versionInfoRequested;

	@CommandLine.Option(names = { "-h", "--help" },
						usageHelp = true,
						description = "Display this help message")
	boolean usageHelpRequested;

	@CommandLine.Option(names = { "-i", "--input" },
						paramLabel = "FILE",
						description = "World Input (directory containing region, level.dat, etc.)")
	private File input = new File("./world");

	@CommandLine.Option(names = { "-o", "--output" },
						paramLabel = "FILE",
						description = "Output Directory")
	private File output = new File("./deploy");

	@CommandLine.Option(names = { "-m", "--mode" },
						paramLabel = "MODE",
						description = "Deployer Mode [COMPLETE, MINIMAL, SCRIPT, CONFIG]")
	private DeployMode mode = DeployMode.COMPLETE;

	@CommandLine.Option(names = { "-c", "--config" },
						paramLabel = "FILE",
						description = "Base Configuration to use for all MineTile containers (e.g. for Redis config)")
	private File baseConfig = new File("./config.yml");

	@CommandLine.Option(names = { "-s", "--serverBase" },
						paramLabel = "DIR",
						description = "Directory containing a base-server that will be copied to all containers")
	private File serverBase = new File("./server");

	@CommandLine.Option(names = { "--worldName" },
						paramLabel = "NAME",
						description = "Name of the world directory if not using the default 'world'")
	private String worldName = "world";

	@CommandLine.Option(names = { "--containerVersion" },
						paramLabel = "VERSION",
						description = "Version for the container plugin (see https://github.com/InventivetalentDev/MineTileContainer/releases)")
	private String containerVersion = "1.0.0-SNAPSHOT";

	@CommandLine.Option(names = { "--routerVersion" },
						paramLabel = "VERSION",
						description = "Version for the router plugin (see https://github.com/InventivetalentDev/MineTileRouter/releases)")
	private String routerVersion = "1.0.0-SNAPSHOT";

	@CommandLine.Option(names = { "--portStart" },
						paramLabel = "PORT",
						description = "Starting port when auto-increasing port number")
	private int portStart = 25622;

	@CommandLine.Option(names = { "--sequentialPorts" },
						description = "Use sequential port numbers for containers and automatically set them in the server.properties")
	private boolean sequentialPorts = true;

	@CommandLine.Option(names = { "-r", "--radius" },
						paramLabel = "RADIUS",
						description = "Radius of chunk-sections to split, starting from 0,0\n"
								+ "This will also determine the amount of clients generated")
	private int radius = 1;

	@CommandLine.Option(names = { "--centerX" },
						description = "X-Offset for the center tile in region sections (32x16 blocks)")
	private int centerX = 0;
	@CommandLine.Option(names = { "--centerZ" },
						description = "Z-Offset for the center tile in region sections (32x16 blocks)")
	private int centerZ = 0;

	@CommandLine.Option(names = { "--tileSize" },
						description = "Radius of the individual tiles in chunks (default: 16 -> 32x32 chunks -> 1 mca file)")
	private int tileSize = 16;

	@CommandLine.Option(names = { "--names" },
						split = ",",
						description = "List of names for the generated containers - these will be used for the output directories")
	private String[] serverNames = new String[0];

	@CommandLine.Option(names = { "--namesFile" },
						description = "Alternative to --names to load container names from file")
	private File serverNamesFile;

	@CommandLine.Option(names = { "--hosts" },
						split = ",",
						description = "List of hosts to use for the generated containers (e.g. 127.0.0.1)")
	private String[] serverHosts = new String[0];

	@CommandLine.Option(names = { "--hostsFile" },
						description = "Alternative to --hosts to load container host addresses from file")
	private File serverHostsFile;

	@CommandLine.Option(names = { "--overwriteGameRules" },
						description = "Whether to enable protective game rules (e.g. mobGriefing:false, doWeatherCycle:false, etc.) - custom values can be specified in a ./gamerules.yml file")
	private boolean overwriteGameRules = false;

	@CommandLine.Option(names={"--deleteEmptyTiles"},
						description = "Delete empty tile directories (without any world sections)")
	private boolean deleteEmptyTiles=true;

	@CommandLine.Option(names = { "--gzip", "--zip" },
						description = "Whether to create a .tar.gz archive of the individual containers instead of regular directories")
	private boolean gzip = false;

	@CommandLine.Option(names = { "--perHostDirectories", "--perHostDirs" },
						description = "Whether to move containers with the same host address into the same directory")
	private boolean perHostDirectories = false;

	@CommandLine.Option(names = { "--scriptServerBase" },
						description = "Directory for the init.sh script to copy server files from when running in SCRIPT mode")
	private String scriptServerBase = "";

	@CommandLine.Option(names = { "--scriptServerDownload" },
						description = "Directory for the init.sh script to download server .jar from when running in SCRIPT mode")
	private String scriptServerDownload = "https://papermc.io/ci/job/Paper/lastSuccessfulBuild/artifact/paperclip.jar";

	@CommandLine.Option(names = { "--threads" },
						description = "Number of threads to use for creating and copying tile data")
	private int threads = 4;

	@CommandLine.Option(names = { "-d", "--dry-run" },
						description = "Only output information about how the given options will affect the output, without generating any files")
	private boolean dryRun = false;

	/// Internal stuff
	File containerPluginFile = new File("./MineTileContainer.jar");
	File routerPluginFile    = new File("./MineTileRouter.jar");

	Map<String, Object> baseConfigData = new HashMap<>();
	File                regionDirectory;
	File                levelFile;
	File                containersDir;
	File                bungeeDir;
	File                serverListFile = new File("./servers.csv");
	int                 totalCount     = 1;

	Map<String, String> gameRuleOverrides = new HashMap<String, String>() {{
		put("doDaylightCycle", "false");
		put("commandBlockOutput", "false");
		put("doEntityDrops", "false");
		put("doFireTick", "false");
		put("doLimitedCrafting", "true");
		put("doMobLoot", "false");
		put("doMobSpawning", "false");
		put("doTileDrops", "false");
		put("doWeatherCycle", "false");
		put("mobGriefing", "false");
		put("spectatorsGenerateChunks", "false");
	}};

	Executor      tileExecutor;
	AtomicInteger tileCounter = new AtomicInteger();

	@Override
	public Boolean call() throws Exception {
		System.out.println("Use --help argument to show options");

		if (input == null || !input.exists()) {
			System.err.println("Input Directory " + input + " not found");
			return false;
		}
		regionDirectory = new File(input, "region");
		if (!regionDirectory.exists()) {
			System.err.println("World directory does not contain a region directory");
			return false;
		}
		levelFile = new File(input, "level.dat");
		if (output == null) {
			System.err.println("Output Directory is null!");
			return false;
		}
		if (!output.exists()) {
			System.out.println("Output Directory does not exist, creating a new one");
			output.mkdir();
		}

		if (baseConfig == null || !baseConfig.exists()) {
			System.err.println("Base configuration not found. This is not recommended! Continuing anyway.");
		} else {
			System.out.println("Loading base configuration");
			try (InputStream configIn = new FileInputStream(baseConfig)) {
				baseConfigData = new Yaml().load(configIn);
			} catch (IOException e) {
				System.err.println("Failed to load base config");
				e.printStackTrace();
				return false;
			}
			System.out.println(baseConfigData);
		}

		if (mode == null) {
			mode = DeployMode.COMPLETE;
		}
		System.out.println("Running in " + mode.name() + " mode");

		System.out.println();

		if (radius == 0) {
			System.err.println("Radius is set to 0");
			return false;
		}
		System.out.println("Radius is " + radius);
		for (int i = 0; i <= radius; i++) {
			totalCount += i * 8;
		}
		System.out.println("Will generate " + totalCount + " containers");

		if (tileSize % 16 != 0) {
			System.err.println("tileSize should be a multiple of 16");
			return false;
		}
		System.out.println("Tile Size Radius is " + (tileSize / 32) + " regions / " + tileSize + " chunks / " + (tileSize * 16) + " blocks");
		System.out.println("Each tile will contain a " + (tileSize * 2 / 32) + "x" + (tileSize * 2 / 32) + " regions / " + (tileSize * 2) + "x" + (tileSize * 2) + " chunks / " + (tileSize * 16 * 2) + "x" + (tileSize * 16 * 2) + " blocks section");

		int totalSize = tileSize * 2 * radius * 2;
		System.out.println("Total Map Size will be " + totalSize + "x" + totalSize + " chunks / ~" + (totalSize * 16) + "x" + (totalSize * 16) + " blocks");

		System.out.println();

		if (serverNamesFile != null && serverNamesFile.exists()) {
			try {
				serverNames = loadLinesFromFile(serverNamesFile);
			} catch (IOException e) {
				System.err.println("Failed to load server names from file");
				e.printStackTrace();
			}
		}
		if (serverNames.length == 0) {
			System.err.println("There are no server names set. Will use incremental names.");
		} else if (serverNames.length < totalCount) {
			System.err.println("There are less sever names set than the amount of generated containers. Will use incremental names for leftovers.");
		}

		if (serverHostsFile != null && serverHostsFile.exists()) {
			try {
				serverHosts = loadLinesFromFile(serverHostsFile);
			} catch (IOException e) {
				System.err.println("Failed to load hosts from file");
				e.printStackTrace();
			}
		}
		if (serverHosts.length == 0) {
			System.err.println("There are no server hosts set. Will use 127.0.0.1");
		} else if (serverHosts.length < totalCount) {
			System.err.println("There are less sever names set than the amount of generated containers.");
		}

		File gameRuleFile = new File("./gamerules.yml");
		if (overwriteGameRules) {
			if (gameRuleFile.exists()) {
				try (FileReader reader = new FileReader(gameRuleFile)) {
					Map<String, String> fileRules = new Yaml().load(reader);
					gameRuleOverrides.putAll(fileRules);
				}
			}

			System.out.println("Game Rule Overrides:");
			gameRuleOverrides.forEach((k, v) -> System.out.println(k + ": " + v));
			System.out.println();
		}

		///// EXIT if dry-run
		if (dryRun) {
			System.out.println("Dry-Run - Exiting!");
			return true;
		}

		tileExecutor = Executors.newFixedThreadPool(threads);

		if (mode.copyPlugins) {
			if (containerPluginFile == null || !containerPluginFile.exists()) {
				System.err.println("Container Plugin File not found - Downloading...");
				try {
					FileUtils.copyURLToFile(new URL("https://github.com/InventivetalentDev/MineTileContainer/releases/download/" + containerVersion + "/container-" + containerVersion + ".jar"), containerPluginFile);
				} catch (IOException e) {
					throw new RuntimeException("Failed to download container plugin", e);
				}
			}
			if (routerPluginFile == null || !routerPluginFile.exists()) {
				System.err.println("Router Plugin File not found - Downloading...");
				try {
					FileUtils.copyURLToFile(new URL("https://github.com/InventivetalentDev/MineTileRouter/releases/download/" + routerVersion + "/router-" + routerVersion + ".jar"), routerPluginFile);
				} catch (IOException e) {
					throw new RuntimeException("Failed to download router plugin", e);
				}
			}
		}

		containersDir = new File(output, "containers");
		if (!containersDir.exists()) {
			containersDir.mkdir();
		}

		bungeeDir = new File(output, "bungee");
		if (!bungeeDir.exists()) {
			bungeeDir.mkdir();
		}

		if (perHostDirectories) {
			for (int i = 0; i < serverHosts.length; i++) {
				new File(containersDir, serverHosts[i]).mkdir();
			}
		}

		try {
			Thread.sleep(500);
			System.out.println();
			System.out.println("Will begin generation in 5 seconds. Press Ctrl+C to cancel...");
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			System.err.println("Sleep interrupted");
			e.printStackTrace();
			return false;
		}
		System.out.println("Starting!");
		System.out.println();

		// Header
		if (serverListFile.exists()) {
			serverListFile.delete();
		}
		writeServerListEntry(new String[] { "ID", "Name", "Host", "Port", "X", "Z", "# Regions", "# Chunks" });

		makeBungee();

		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				final int rx = x + centerX;
				final int rz = z + centerZ;

				final int c = tileCounter.getAndIncrement();

				tileExecutor.execute(new Runnable() {
					@Override
					public void run() {
						String[] currentServerEntry = new String[8];
						System.out.println("[C] Working on " + rx + "," + rz + " (" + (c + 1) + "/" + totalCount + ")...");
						try {
							int regionCount  = handleSection(rx, rz, c, currentServerEntry);

							if(!mode.copyWorld||regionCount>0) {
								writeServerListEntry(currentServerEntry);
							}
						} catch (Exception e) {
							System.err.println("Exception on " + rx + "," + rz + "");
							e.printStackTrace();
						}

						checkIfDone(tileCounter.decrementAndGet());

					}
				});

			}
		}

		return true;
	}

	void checkIfDone(int i) {
		if (i <= 0) {

			System.out.println();
			System.out.println("Done!");

			System.out.println("Bungeecord and router plugin are in      " + bungeeDir);
			System.out.println("Containers with plugin and world are in  " + containersDir);

			if (mode.createInitScript) {
				System.out.println();
				System.out.println("init.sh script has been added to all containers. Make sure to run it before starting the servers!");
			}

			System.out.println();
			System.out.println("List of Servers written to " + serverListFile);

			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			System.exit(0);
		}
	}

	private void makeBungee() throws IOException {
		File pluginDir = new File(bungeeDir, "plugins");
		if (!pluginDir.exists()) {
			pluginDir.mkdir();
		}
		File destPluginFile = new File(pluginDir, "MineTileRouter.jar");
		if (!destPluginFile.exists() && mode.copyPlugins) {
			FileUtils.copyFile(routerPluginFile, destPluginFile);
		}
		File pluginDataDir = new File(pluginDir, "MineTileRouter");
		if (!pluginDataDir.exists()) {
			pluginDataDir.mkdir();
		}
		File pluginConfig = new File(pluginDataDir, "config.yml");
		if (!pluginConfig.exists()) {
			writeConfigFor(pluginConfig, 0, 0, 0, true, null);
		}
	}

	private int handleSection(int x, int z, final int c, String[] currentServerEntry) throws IOException {
		System.out.println("Section #" + c);

		String name = DEFAULT_NAME_FORMAT;
		if (serverNames.length > 0) {
			name = serverNames[c % serverNames.length];
		}
		name = name
				.replace("%x", "" + x)
				.replace("%z", "" + z);

		currentServerEntry[1] = name;
		currentServerEntry[4] = "" + x;
		currentServerEntry[5] = "" + z;

		File containerDir = new File(containersDir, name);
		containerDir.mkdir();

		if (mode.copyServer) {
			// Use server base if it exists
			if (serverBase != null && serverBase.exists()) {
				FileUtils.copyDirectory(serverBase, containerDir);
			}
		}

		if (mode.createInitScript) {
			List<String> lines = IOUtils.readLines(getClass().getResourceAsStream("/templates/containerInitScript.sh"), "utf8");
			try (PrintWriter writer = new PrintWriter(new FileWriter(new File(containerDir, "init.sh")))) {
				for (String l : lines) {
					l = l.replaceAll("--CONTAINER_NAME--", name);
					l = l.replaceAll("--CONTAINER_VERSION--", containerVersion);
					l = l.replaceAll("--SERVER_DOWNLOAD--", scriptServerDownload);
					l = l.replaceAll("--SERVER_BASE--", scriptServerBase);
					writer.println(l);
				}

				writer.println("# Container init script, generated by MineTile Deployer on " + new Date().toString() + " #");
			}
		}

		File propertiesFile = new File(containerDir, "server.properties");
		if(mode.updateConfig){
			updateServerProperties(propertiesFile, x, z, c, currentServerEntry);
		}

		int regionCounter = 0;
		int chunkCounter = 0;
		if (mode.copyWorld) {
			File worldDir = new File(containerDir, worldName);
			if (!worldDir.exists()) {
				worldDir.mkdir();
			}
			File levelFile = new File(worldDir, "level.dat");
			try {
				writeLevelFile(levelFile, x, z, c);
			} catch (Exception e) {
				System.err.println("Failed to write new level.dat file");
				e.printStackTrace();
			}

			File destRegionDir = new File(worldDir, "region");
			if (!destRegionDir.exists()) {
				destRegionDir.mkdir();
			}

			int tileSizeMca = (int) Math.ceil(tileSize / 32.0D);
			int tileSizeMca2 = tileSizeMca * 2;
			System.out.println("Copying and shifting " + tileSizeMca2 + "x" + tileSizeMca2 + " (" + (tileSizeMca2 * tileSizeMca2) + ") mca files...");

			// Surrounding chunks in every direction
			//		tileSizeMca += 1;
			//		tileSizeMca2 += 2;

			//TODO: should probably multiply the x&z inputs instead of just adding

			int rx = tileSizeMca2 * x;
			int rz = tileSizeMca2 * z;



			int rC = 0;
			for (int sx = -tileSizeMca - 1; sx <= tileSizeMca; sx++) {
				for (int sz = -tileSizeMca - 1; sz <= tileSizeMca; sz++) {
					int xx = rx + sx;
					int zz = rz + sz;

					System.out.println("[R]  [" + x + "," + z + "] " + xx + "," + zz + " -> " + sx + "," + sz + " (" + (++rC) + "/" + (tileSizeMca2 * tileSizeMca2) + ")");

					File sourceRegionFile = new File(regionDirectory, "r." + xx + "." + zz + ".mca");
					if (!sourceRegionFile.exists()) {
						System.err.println("Region File for " + xx + "," + zz + " not found. Skipping!");
					} else {
						int r = copyMCAFile(sourceRegionFile, sx, sz, destRegionDir, rx, rz, c);
						chunkCounter += r;
						regionCounter++;
					}
				}
			}

			currentServerEntry[6] = "" + regionCounter;
			currentServerEntry[7] = "" + chunkCounter;
		}

		if (mode.updateConfig) {
			// Plugin jar + container config
			File pluginDir = new File(containerDir, "plugins");
			if (!pluginDir.exists()) {
				pluginDir.mkdir();
			}
			File destPluginFile = new File(pluginDir, "MineTileContainer.jar");
			if (!destPluginFile.exists() && mode.copyPlugins) {
				FileUtils.copyFile(containerPluginFile, destPluginFile);
			}
			File pluginDataDir = new File(pluginDir, "MineTileContainer");
			if (!pluginDataDir.exists()) {
				pluginDataDir.mkdir();
			}
			File pluginConfig = new File(pluginDataDir, "config.yml");
			writeConfigFor(pluginConfig, x, z, c, false, currentServerEntry);
		}

		if (gzip) {
			System.out.println("Creating Tarball...");
			File tarFile = new File(containersDir, name + ".tar.gz");
			try (TarballMaker tarballMaker = new TarballMaker(tarFile)) {
				tarballMaker.addRecursive(containerDir, "");
			}

			if (perHostDirectories) {
				FileUtils.moveFileToDirectory(tarFile, new File(containersDir, currentServerEntry[2]), true);
			}

			containerDir.delete();
		} else if (perHostDirectories) {
			FileUtils.moveDirectoryToDirectory(containerDir, new File(containersDir, currentServerEntry[2]), true);
		}

		if (mode.copyWorld && regionCounter == 0) {
			// Delete empty container
			containerDir.delete();
		}

		return regionCounter;
	}

	void updateServerProperties(File propertiesFile, int x, int z, int c, String[] currentServerEntry) throws IOException {
		currentServerEntry[3] = "25565";

		Properties properties = new Properties();
		try (FileInputStream in = new FileInputStream(propertiesFile)) {
			properties.load(in);
		}
		if (sequentialPorts) {
			String port = "" + (portStart + c);
			currentServerEntry[3] = port;
			properties.setProperty("server-port", port);
		} else {
			currentServerEntry[3] = properties.getProperty("server-port");
		}

		try (FileOutputStream out = new FileOutputStream(propertiesFile)) {
			properties.store(out, null);
		}
	}

	void writeLevelFile(File newLevelFile, int x, int z, int c) throws Exception {
		CompoundTag dataTag = new CompoundTag("Data");

		if (levelFile.exists()) {// use the input world's level.dat as a base
			try (NBTInputStream nbtIn = new NBTInputStream(new FileInputStream(levelFile), true)) {
				CompoundTag rootTag = (CompoundTag) nbtIn.readNBTTag();
				if (rootTag != null) {
					dataTag = rootTag.getCompound("Data");
				}
			}
		}

		dataTag.set("version", new IntTag("version", 19133));
		dataTag.set("LevelName", "MineTile_x" + x + "_z" + z);

		// make sure to only generate void
		dataTag.set("generatorName", "flat");
		dataTag.set("MapFeatures", new ByteTag("MapFeatures", (byte) 0));

		CompoundTag generatorOptionsTag = dataTag.getOrCreateCompound("generatorOptions");
		if (generatorOptionsTag == null) { generatorOptionsTag = new CompoundTag("generatorOptions"); }
		ListTag<CompoundTag> layersTag = generatorOptionsTag.getOrCreateList("layers", CompoundTag.class);
		if (layersTag == null) { layersTag = new ListTag<>("layers"); }
		CompoundTag layerTag = new CompoundTag();
		layerTag.set("block", "minecraft:air");
		layerTag.set("height", new ByteTag("height", (byte) 1));
		layersTag.add(layerTag);
		generatorOptionsTag.set("layers", layersTag);
		generatorOptionsTag.set("biome", "minecraft:the_void");
		generatorOptionsTag.set("structures", new CompoundTag("structures"));
		dataTag.set("generatorOptions", generatorOptionsTag);

		if (overwriteGameRules) {
			CompoundTag gameRulesTag = dataTag.getOrCreateCompound("GameRules");
			if (gameRulesTag == null) { gameRulesTag = new CompoundTag("GameRules"); }
			final CompoundTag finalGameRulesTag = gameRulesTag;
			gameRuleOverrides.forEach((k, v) -> finalGameRulesTag.set(k, new StringTag(k, v)));
			dataTag.set("GameRules", finalGameRulesTag);
		}

		CompoundTag newRootTag = new CompoundTag();
		newRootTag.set("Data", dataTag);
		try (NBTOutputStream nbtOut = new NBTOutputStream(new FileOutputStream(newLevelFile), true)) {
			nbtOut.writeTag(newRootTag);
		}
	}

	void writeConfigFor(File pluginConfig, int x, int z, int c, boolean bungee, String[] currentServerEntry) throws IOException {
		Map<String, Object> containerConfig = new HashMap<String, Object>(baseConfigData);
		if (!bungee) {
			String host = "127.0.0.1";
			if (serverHosts.length > 0) {
				host = serverHosts[c % serverHosts.length];
			}
			currentServerEntry[2] = host;

			Map<String, Object> serverConfig = (Map<String, Object>) containerConfig.getOrDefault("server", new HashMap<String, Object>());
			if (!serverConfig.containsKey("host")) {
				serverConfig.put("host", host);
			}
			containerConfig.put("server", serverConfig);

			Map<String, Object> tileConfig = (Map<String, Object>) containerConfig.getOrDefault("tile", new HashMap<String, Object>());
			tileConfig.put("x", x);
			tileConfig.put("z", z);
			containerConfig.put("tile", tileConfig);

			UUID id = UUID.randomUUID();
			currentServerEntry[0] = id.toString();
			containerConfig.put("serverId", id.toString());
		}

		try (FileWriter writer = new FileWriter(pluginConfig)) {
			new Yaml().dump(containerConfig, writer);
		}
	}

	int copyMCAFile(File in, int tileX, int tileZ, File targetDir, int x, int z, int c) throws IOException {
		int r = 0;

		File out = new File(targetDir, "r." + tileX + "." + tileZ + ".mca");
		if (out.exists()) {
			out.delete();
		}

		//		FileUtils.copyFile(in, out);

		try (RegionFile regionInFile = new RegionFile(in)) {
			try (RegionFile regionOutFile = new RegionFile(out)) {
				for (int cX = 0; cX < 32; cX++) {
					for (int cZ = 0; cZ < 32; cZ++) {
						//				int offset = getOffset(offsets, cX, cZ);
						//				int sector = offset >> 8;
						//				raf.seek(sector * SECTOR_BYTES);
						//				int length = raf.readInt();

						//				int offset = regionInFile.getOffset(cX, cZ);
						//				regionOutFile.setOffset(cX, cZ, offset);

						try (DataInputStream inStream = regionInFile.getChunkDataInputStream(cX, cZ)) {
							if (inStream != null) {
								try (DataOutputStream outStream = regionOutFile.getChunkDataOutputStream(cX, cZ)) {
									if (outStream != null) {
										try (NBTInputStream nbtIn = new NBTInputStream(inStream)) {
											CompoundTag rootTag = (CompoundTag) nbtIn.readNBTTag();
											if (rootTag != null) {
												CompoundTag levelTag = rootTag.getCompound("Level");
												try (NBTOutputStream nbtOut = new NBTOutputStream(outStream)) {
													if (levelTag != null) {
														levelTag.set("xPos", cX + tileX * 32);
														levelTag.set("zPos", cZ + tileZ * 32);

														//// TODO: fix entity loations

														if (levelTag.has("Entities")) {
															ListTag entitiesList = levelTag.getList("Entities");
															entitiesList.forEach((entity) -> {
																ListTag<DoubleTag> posList = ((CompoundTag) entity).getList("Pos", DoubleTag.class);
																posList.set(0, new DoubleTag(posList.get(0).getValue() - x * 32 * 16));
																posList.set(2, new DoubleTag(posList.get(2).getValue() - z * 32 * 16));
																((CompoundTag) entity).set("Pos", posList);
															});
															levelTag.set("Entities", entitiesList);
														}

														if (levelTag.has("TileEntities")) {
															ListTag tileEntitiesList = levelTag.getList("TileEntities");
															tileEntitiesList.forEach((entity) -> {
																((CompoundTag) entity).set("x", ((CompoundTag) entity).get("x").getAsDouble() - x * 32 * 16);
																((CompoundTag) entity).set("z", ((CompoundTag) entity).get("z").getAsDouble() - z * 32 * 16);
															});
															levelTag.set("TileEntities", tileEntitiesList);
														}

														if (levelTag.has("TileTicks")) {
															ListTag tileTicksList = levelTag.getList("TileTicks");
															tileTicksList.forEach((tick) -> {
																((CompoundTag) tick).set("x", ((CompoundTag) tick).get("x").getAsDouble() - x * 32 * 16);
																((CompoundTag) tick).set("z", ((CompoundTag) tick).get("z").getAsDouble() - z * 32 * 16);
															});
															levelTag.set("TileTicks", tileTicksList);
														}

														rootTag.set("Level", levelTag);
														nbtOut.writeTag(rootTag);

														r++;
													}
												} catch (Exception e) {
													e.printStackTrace();
												}
											}
										} catch (Exception e) {
											e.printStackTrace();
										}
									}
								}
							}
						}
					}
				}
			}
		}

		return r;
	}

	String[] loadLinesFromFile(File file) throws IOException {
		List<String> lines = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
			String line;
			while ((line = reader.readLine()) != null) {
				lines.add(line);
			}
		}
		return lines.toArray(new String[0]);
	}

	void writeServerListEntry(String[] entry) throws IOException {
		try (PrintWriter writer = new PrintWriter(new FileWriter(serverListFile, true))) {
			writer.println(String.join(",", entry));
		}
	}

}
