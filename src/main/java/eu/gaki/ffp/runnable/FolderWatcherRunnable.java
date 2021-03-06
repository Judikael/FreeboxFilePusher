/*
 *
 */
package eu.gaki.ffp.runnable;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.gaki.ffp.domain.FfpItem;
import eu.gaki.ffp.domain.StatusEnum;
import eu.gaki.ffp.service.ServiceProvider;

// TODO: Auto-generated Javadoc
/**
 * Job for scan a watched folder.
 */
public class FolderWatcherRunnable implements Runnable {

	/** The Constant LOGGER. */
	private static final Logger LOGGER = LoggerFactory.getLogger(FolderWatcherRunnable.class);

	/** The folders to watch. */
	private final Path watchedFolder;

	/** The service provider. */
	private final ServiceProvider serviceProvider;

	/** The file change cooldown. */
	private final Long fileChangeCooldown;

	/**
	 * Instantiates a new job for scan a watched folder.
	 *
	 * @param watchedFolder
	 *            the watched folder
	 * @param serviceProvider
	 *            the service provider
	 */
	public FolderWatcherRunnable(final Path watchedFolder, final ServiceProvider serviceProvider) {
		this.watchedFolder = watchedFolder;
		this.serviceProvider = serviceProvider;
		fileChangeCooldown = serviceProvider.getConfigService().getFileChangeCooldown();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void run() {
		try {

			// Watch new files and folder in watched folder
			if (Files.exists(watchedFolder)) {
				try (DirectoryStream<Path> stream = Files.newDirectoryStream(watchedFolder)) {

					final AtomicBoolean someItemChanged = new AtomicBoolean(false);
					for (final Path path : stream) {
						// Search for the path
						final List<FfpItem> contains = serviceProvider.getDaoService().contains(path.toUri());
						if (contains.isEmpty()) {
							// Create a new item and compute the checksum
							final FfpItem item = serviceProvider.getItemService().create(path);
							serviceProvider.getDaoService().get().addFfpItem(item);
							serviceProvider.getChecksumService().computeChecksum(item);
						} else {
							// Existing Item
							contains.forEach(item -> {
								if (StatusEnum.WATCH.equals(item.getStatus())) {
									// Update the item in case of files
									// added/removed
									final boolean filesChanged = serviceProvider.getItemService().update(item);
									// Update the Checksum
									final boolean checksumChanged = serviceProvider.getChecksumService()
											.computeChecksum(item);
									if (!filesChanged && !checksumChanged) {
										final LocalDateTime adler32Date = item.getAdler32Date();
										if (adler32Date != null) {
											final Duration between = Duration.between(adler32Date, LocalDateTime.now());
											if (between.getSeconds() >= fileChangeCooldown) {
												// No change during the cooldown
												// period:
												// we mark as to send
												item.setStatus(StatusEnum.TO_SEND);
												someItemChanged.set(true);
												LOGGER.info(
														"Change status of {} to TO_SEND. Checksum don't have change for {} sec.",
														item, between.getSeconds());
											}
										}
									} else {
										someItemChanged.set(true);
									}
								}
							});
						}
					}
					// Save the domain
					if (someItemChanged.get()) {
						serviceProvider.getDaoService().save();
					}
				} catch (final Exception e) {
					LOGGER.error(e.getMessage(), e);
				}
			} else {
				LOGGER.warn("The folder {} doesn't exist.", watchedFolder);
			}

		} catch (final Exception e) {
			LOGGER.error("Cannot watch folder:" + e.getMessage(), e);
		}
	}

}
