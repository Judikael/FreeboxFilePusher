package eu.gaki.ffp.compress;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.gaki.ffp.http.HttpFolderListener;

/**
 * The Class FilesCompresor.
 */
public class FilesCompresor {

	/** The Constant LOGGER. */
	private static final Logger LOGGER = LoggerFactory.getLogger(HttpFolderListener.class);

	/** The Constant TBZIP2. */
	private static final String TBZIP2 = ".tbz2";

	/** The configuration. */
	private final Properties configuration;
	
	/** The executor. */
	private Executor executor = Executors.newFixedThreadPool(2);

	/** The in progress. */
	private final Set<Path> inProgress = new HashSet<Path>();
	
	/**
	 * Instantiates a new files compressor.
	 *
	 * @param configuration
	 *            the configuration
	 */
	public FilesCompresor(final Properties configuration) {
		this.configuration = configuration;
	}

	/**
	 * Compress.
	 *
	 * @param pathToCompress
	 *            the path or file to compress
	 */
	public synchronized void compress(final Path pathToCompress) {

		if (!inProgress.contains(pathToCompress) && !Files.exists(computeTarBZip2Name(pathToCompress))) {
			inProgress.add(pathToCompress);
			executor.execute(() -> doCompress(pathToCompress));
		} 
		
	}

	/**
	 * Compress.
	 *
	 * @param pathToCompress
	 *            the path or file to compress
	 */
	private void doCompress(final Path pathToCompress) {

		// Tar and BZip2
		LOGGER.info("Start compress: " + pathToCompress);
		final Instant startDate = Instant.now();
		Stream<Path> walkFiltered = null;
		final TarArchiveOutputStream tarOutputStream;
		final Path tarBZip2File = computeTarBZip2Name(pathToCompress);
		try (final OutputStream tarBZip2OutputStream = Files.newOutputStream(tarBZip2File, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				final OutputStream tarBZip2BufferedOutputStream = new BufferedOutputStream(tarBZip2OutputStream);
				final OutputStream bZip2OutputStream = new BZip2CompressorOutputStream(tarBZip2BufferedOutputStream, 3);
				Stream<Path> walk = Files.walk(pathToCompress, FileVisitOption.FOLLOW_LINKS);) {

			final String property = configuration.getProperty("ffp.compress.folder", "true");
			if (Boolean.valueOf(property)) {
				tarOutputStream = new TarArchiveOutputStream(bZip2OutputStream);
			} else {
				tarOutputStream = new TarArchiveOutputStream(tarBZip2BufferedOutputStream);
			}
			
			try {
				
				tarOutputStream.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
				tarOutputStream.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
				final Collection<String> excludeExtention = getExcludeExtensions();

				walkFiltered = walk.filter(p -> {
					final String name = p.getFileName().toString();
					final String extension = FilenameUtils.getExtension(name);
					boolean include = false;

					if (Files.isDirectory(p)) {
						// Exclude the root folder to compress (we tar sub path of
						// the root folder)
						include = !p.equals(pathToCompress);
					} else {
						// Exclude some file extension
						include = !excludeExtention.contains(extension);
					}

					return include;
				});

				walkFiltered.forEach(t -> {
					try {
						// Add the file to the TAR
						final Path relativePath = pathToCompress.relativize(t);
						final TarArchiveEntry entry = new TarArchiveEntry(t.toFile(), relativePath.normalize().toString());
						tarOutputStream.putArchiveEntry(entry);

						if (!Files.isDirectory(t)) {
							// Copy the file
							Files.copy(t, tarOutputStream);
						}

						tarOutputStream.closeArchiveEntry();
					} catch (final IOException e) {
						LOGGER.error(e.getMessage(), e);
					}
				});
				
				// We have finish
				FileUtils.deleteDirectory(pathToCompress.toFile());
				
			} finally {
				if (tarOutputStream != null) {
					tarOutputStream.close();
				}
			}

		} catch (final IOException e) {
			LOGGER.error(e.getMessage(), e);
		} finally {
			if (walkFiltered != null) {
				walkFiltered.close();
			}
			
			synchronized (FilesCompresor.this) {
				inProgress.remove(pathToCompress);
			}
			
		}

		final Instant endDate = Instant.now();
		final long between = ChronoUnit.SECONDS.between(startDate, endDate);

		LOGGER.info("Stop compress: " + pathToCompress + " took " + between + " secondes");
	}

	/**
	 * Gets the extensions to exclude.
	 *
	 * @return the extensions to exclude
	 */
	private Collection<String> getExcludeExtensions() {
		final Collection<String> excludeExtensions = new HashSet<>();
		final String excludeExtensionString = configuration.getProperty("fft.exclude.extension", ".html,.exe,.txt,.readme,.nfo,.link");
		final String[] split = excludeExtensionString.split(",");
		excludeExtensions.addAll(Arrays.asList(split));
		return excludeExtensions;
	}

	/**
	 * Compute tar bzip2 name.
	 *
	 * @param pathToCompress
	 *            the path to compress
	 * @return the path
	 */
	public Path computeTarBZip2Name(final Path pathToCompress) {
		final Path tarBZip2File = pathToCompress.getParent().resolve(pathToCompress.getFileName() + TBZIP2);
		return tarBZip2File;
	}

}
