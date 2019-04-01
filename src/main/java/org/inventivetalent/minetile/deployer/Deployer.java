package org.inventivetalent.minetile.deployer;

import org.apache.commons.io.FileUtils;
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

@CommandLine.Command(description = "Utility to split up large worlds into individual MineTile containers for easy deploying",
					 versionProvider = VersionProvider.class,
					 showDefaultValues = true,
					 headerHeading = "@|bold,underline Usage|@:%n%n",
					 synopsisHeading = "%n",
					 descriptionHeading = "%n@|bold,underline Description|@:%n%n",
					 parameterListHeading = "%n@|bold,underline Parameters|@:%n",
					 optionListHeading = "%n@|bold,underline Options|@:%n",
					 header = "Record changes to the repository.")
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
						description = "World Input (directory containing region, level.dat, etc.)")
	private File input = new File("./world");

	@CommandLine.Option(names = { "-o", "--output" },
						description = "Output Directory")
	private File output = new File("./deploy");

	@CommandLine.Option(names = { "-c", "--config" },
						description = "Base Configuration to use for all MineTile containers (e.g. for Redis config)")
	private File baseConfig = new File("./config.yml");

	@CommandLine.Option(names = { "-s", "--serverBase" },
						description = "Directory containing a base-server that will be copied to all containers")
	private File serverBase = new File("./server");

	@CommandLine.Option(names = { "--worldName" },
						description = "Name of the world directory if not using the default 'world'")
	private String worldName = "world";

	@CommandLine.Option(names = { "--containerVersion" },
						description = "Version for the container plugin (see https://github.com/InventivetalentDev/MineTileContainer/releases)")
	private String containerVersion = "1.0.0-SNAPSHOT";

	@CommandLine.Option(names = { "--routerVersion" },
						description = "Version for the router plugin (see https://github.com/InventivetalentDev/MineTileRouter/releases)")
	private String routerVersion = "1.0.0-SNAPSHOT";//TODO

	@CommandLine.Option(names = { "--portStart" },
						description = "Starting port when auto-increasing port number")
	private int portStart = 25622;

	@CommandLine.Option(names = { "--sequentialPorts" },
						description = "Use sequential port numbers for containers and automatically set them in the server.properties")
	private boolean sequentialPorts = true;

	@CommandLine.Option(names = { "-r", "--radius" },
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
						description = "List of names for the generated containers - these will be used for the output directories")
	private String[] serverNames = new String[0];

	@CommandLine.Option(names = { "--namesFile" },
						description = "Alternative to --names to load container names from file")
	private File serverNamesFile;

	@CommandLine.Option(names = { "--hosts" },
						description = "List of hosts to use for the generated containers (e.g. 127.0.0.1)")
	private String[] serverHosts = new String[0];

	@CommandLine.Option(names = { "--hostsFile" },
						description = "Alternative to --hosts to load container host addresses from file")
	private File serverHostsFile;

	@CommandLine.Option(names = { "--gzip", "--zip" },
						description = "Whether to create a .tar.gz archive of the individual containers instead of regular directories")
	private boolean gzip = false;

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

	Executor      tileExecutor;
	AtomicInteger tileCounter = new AtomicInteger();

	@Override
	public Boolean call() throws Exception {
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
			System.err.println("There are less sever names set than the amount of generated containers. Will use 127.0.0.1 for leftovers.");
		}

		///// EXIT if dry-run
		if (dryRun) {
			System.out.println("Dry-Run - Exiting!");
			return true;
		}

		tileExecutor = Executors.newFixedThreadPool(threads);

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

		containersDir = new File(output, "containers");
		if (!containersDir.exists()) {
			containersDir.mkdir();
		}

		bungeeDir = new File(output, "bungee");
		if (!bungeeDir.exists()) {
			bungeeDir.mkdir();
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
		writeServerListEntry(new String[] { "ID", "Name", "Host", "Port", "X", "Z" });

		makeBungee();

		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				final int rx = x + centerX;
				final int rz = z + centerZ;

				tileExecutor.execute(new Runnable() {
					@Override
					public void run() {
						int c = tileCounter.getAndIncrement();
						String[] currentServerEntry = new String[6];
						System.out.println("[C] Working on " + rx + "," + rz + " (" + (c + 1) + "/" + totalCount + ")...");
						try {
							handleSection(rx, rz, c, currentServerEntry);

							writeServerListEntry(currentServerEntry);
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

			System.out.println();
			System.out.println("List of Servers written to " + serverListFile);
		}
	}

	private void makeBungee() throws IOException {
		File pluginDir = new File(bungeeDir, "plugins");
		if (!pluginDir.exists()) {
			pluginDir.mkdir();
		}
		File destPluginFile = new File(pluginDir, "MineTileRouter.jar");
		if (!destPluginFile.exists()) {
			FileUtils.copyFile(containerPluginFile, destPluginFile);
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

	private void handleSection(int x, int z, int c, String[] currentServerEntry) throws IOException {
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

		// Use server base if it exists
		if (serverBase != null && serverBase.exists()) {
			FileUtils.copyDirectory(serverBase, containerDir);
		}

		File propertiesFile = new File(containerDir, "server.properties");
		updateServerProperties(propertiesFile, x, z, c, currentServerEntry);

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
					copyMCAFile(sourceRegionFile, sx, sz, destRegionDir, rx, rz, c);
				}
			}
		}

		// Plugin jar + container config
		File pluginDir = new File(containerDir, "plugins");
		if (!pluginDir.exists()) {
			pluginDir.mkdir();
		}
		File destPluginFile = new File(pluginDir, "MineTileContainer.jar");
		if (!destPluginFile.exists()) {
			FileUtils.copyFile(containerPluginFile, destPluginFile);
		}
		File pluginDataDir = new File(pluginDir, "MineTileContainer");
		if (!pluginDataDir.exists()) {
			pluginDataDir.mkdir();
		}
		File pluginConfig = new File(pluginDataDir, "config.yml");
		writeConfigFor(pluginConfig, x, z, c, false, currentServerEntry);

		if (gzip) {
			System.out.println("Creating Tarball...");
			try (TarballMaker tarballMaker = new TarballMaker(new File(output, name + ".tar.gz"))) {
				tarballMaker.addRecursive(containerDir, "");
			}
			containerDir.deleteOnExit();
		}
	}

	void updateServerProperties(File propertiesFile, int x, int z, int c, String[] currentServerEntry) throws IOException {
		if (!propertiesFile.exists()) { return; }
		//TODO: fix

		try (FileInputStream in = new FileInputStream(propertiesFile)) {
			Properties properties = new Properties();
			properties.load(in);
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

	void copyMCAFile(File in, int tileX, int tileZ, File targetDir, int x, int z, int c) throws IOException {
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
