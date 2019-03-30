package org.inventivetalent.minetile.deployer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.IOUtils;

import java.io.*;

// based on https://stackoverflow.com/questions/13461393/compress-directory-to-tar-gz-with-commons-compress
public class TarballMaker implements Closeable, AutoCloseable {

	private FileOutputStream           fileOutputStream;
	private BufferedOutputStream       bufferedOutputStream;
	private GzipCompressorOutputStream gzipCompressorOutputStream;
	private TarArchiveOutputStream     tarArchiveOutputStream;

	public TarballMaker(File output) throws IOException {
		fileOutputStream = new FileOutputStream(output);
		bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
		gzipCompressorOutputStream = new GzipCompressorOutputStream(bufferedOutputStream);
		tarArchiveOutputStream = new TarArchiveOutputStream(gzipCompressorOutputStream);
	}

	public void addRecursive(File file, String base) throws IOException {
		String entryName = base + file.getName();
		TarArchiveEntry entry = new TarArchiveEntry(file, entryName);
		this.tarArchiveOutputStream.putArchiveEntry(entry);

		if (file.isFile()) {
			IOUtils.copy(new FileInputStream(file), this.tarArchiveOutputStream);
			this.tarArchiveOutputStream.closeArchiveEntry();
		} else {
			this.tarArchiveOutputStream.closeArchiveEntry();
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					addRecursive(child.getAbsoluteFile(), entryName + "/");
				}
			}
		}
	}

	@Override
	public void close() throws IOException {
		if (this.tarArchiveOutputStream != null) { this.tarArchiveOutputStream.close(); }
	}

}
