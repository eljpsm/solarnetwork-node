/* ==================================================================
 * SolarBackupResourceProvider.java - 25/06/2020 10:40:43 AM
 *
 * Copyright 2020 SolarNetwork.net Dev Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA
 * 02111-1307 USA
 * ==================================================================
 */

package net.solarnetwork.node.backup.ext;

import static net.solarnetwork.node.Constants.solarNodeHome;
import static net.solarnetwork.util.ObjectUtils.nonnull;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.util.FileCopyUtils;
import net.solarnetwork.node.backup.BackupResource;
import net.solarnetwork.node.backup.BackupResourceInfo;
import net.solarnetwork.node.backup.BackupResourceProvider;
import net.solarnetwork.node.backup.BackupResourceProviderInfo;
import net.solarnetwork.node.backup.SimpleBackupResourceInfo;
import net.solarnetwork.node.backup.SimpleBackupResourceProviderInfo;

/**
 * {@link BackupResourceProvider} that uses a "solarbackup" external command to
 * manage a single backup resource.
 *
 * <p>
 * The command must accept the following arguments:
 * </p>
 * <ol>
 * <li><b>action</b> - the action to perform; one of <code>create</code> or
 * <code>extract</code></li>
 * <li><b>name</b> - the provider name, representing the unique backup
 * configuration to perform the action on</li>
 * </ol>
 *
 * @author matt
 * @version 2.0
 */
public class SolarBackupResourceProvider implements BackupResourceProvider {

	/** The default value for the {@code command} property. */
	public static final String DEFAULT_COMMAND = solarNodeHome() + "/bin/solarbackup";

	/** The default value for the {@code resourceBundleDir} property. */
	public static final String DEFAULT_RESOURCE_BUNDLE_DIR = "/usr/share/solarnode/backup.d";

	/** The default value for the {@code backupResourceExtension} property. */
	public static final String DEFAULT_BACKUP_RESOURCE_EXTENSION = ".tgz";

	/** The default value for the {@code useSudo} property. */
	public static final boolean DEFAULT_USE_SUDO = true;

	private static final Logger log = LoggerFactory.getLogger(SolarBackupResourceProvider.class);

	private String command = DEFAULT_COMMAND;
	private String resourceBundleDir = DEFAULT_RESOURCE_BUNDLE_DIR;
	private boolean useSudo = DEFAULT_USE_SUDO;
	private @Nullable String name;
	private String backupResourceExtension = DEFAULT_BACKUP_RESOURCE_EXTENSION;

	private @Nullable MessageSource messageSource;

	/**
	 * Constructor.
	 */
	public SolarBackupResourceProvider() {
		super();
	}

	@Override
	public String getKey() {
		return "net.solarnetwork.node.backup.ext.SolarBackupResourceProvider-" + name;
	}

	private synchronized MessageSource messageSource() {
		if ( messageSource != null ) {
			return messageSource;
		}
		final var ms = new ResourceBundleMessageSource();
		File f = new File(resourceBundleDir);
		try {
			ms.setBundleClassLoader(new URLClassLoader(new URL[] { f.toURI().toURL() }));
			ms.setBasename(name);
		} catch ( MalformedURLException e ) {
			log.warn("Error getting message bundle resource [{}]: {}", f, e.toString());
			// ignore
		}
		messageSource = ms;
		return ms;
	}

	@Override
	public BackupResourceProviderInfo providerInfo(@Nullable Locale locale) {
		final MessageSource ms = messageSource();
		final Locale loc = (locale != null ? locale : Locale.getDefault());
		String name = "External Backup Provider";
		String desc = "Backs up device resources.";
		if ( ms != null ) {
			name = nonnull(ms.getMessage("title", null, name, loc), "Name");
			desc = nonnull(ms.getMessage("desc", null, desc, loc), "Description");
		}
		return new SimpleBackupResourceProviderInfo(getKey(), name, desc);
	}

	@Override
	public BackupResourceInfo resourceInfo(BackupResource resource, @Nullable Locale locale) {
		return new SimpleBackupResourceInfo(resource.getProviderKey(), resource.getBackupPath(), null);
	}

	@Override
	public Iterable<BackupResource> getBackupResources() {
		return List.of(new ExtBackupResource());
	}

	private String[] cmd(String action, String name) {
		if ( isUseSudo() ) {
			return new String[] { "sudo", getCommand(), action, name };
		}
		return new String[] { getCommand(), action, name };
	}

	@Override
	public boolean restoreBackupResource(BackupResource resource) {
		final String name = nonnull(getName(), "Name");
		final String path = name + getBackupResourceExtension();
		if ( !path.equals(resource.getBackupPath()) ) {
			return false;
		}
		ProcessBuilder pb = new ProcessBuilder(cmd("extract", name));
		try {
			Process pr = pb.start();
			FileCopyUtils.copy(resource.getInputStream(), pr.getOutputStream());
			return true;
		} catch ( IOException e ) {
			log.error("Error restoring backup resource {}: {}", name, e.toString());
		}
		return false;
	}

	private class ExtBackupResource implements BackupResource {

		private final String name;
		private final String backupPath;
		private final long modificationDate;

		private ExtBackupResource() {
			super();
			this.name = nonnull(getName(), "Name");
			this.backupPath = this.name + getBackupResourceExtension();
			this.modificationDate = System.currentTimeMillis();
		}

		@Override
		public String getBackupPath() {
			return backupPath;
		}

		@Override
		public InputStream getInputStream() throws IOException {
			ProcessBuilder pb = new ProcessBuilder(cmd("create", name));
			Process pr = pb.start();
			return pr.getInputStream();
		}

		@Override
		public long getModificationDate() {
			return modificationDate;
		}

		@Override
		public String getProviderKey() {
			return getKey();
		}

		@Override
		public @Nullable String getSha256Digest() {
			return null;
		}

	}

	/**
	 * Get the helper program command to use.
	 *
	 * @return the command; defaults to {@link #DEFAULT_COMMAND}
	 */
	public final String getCommand() {
		return command;
	}

	/**
	 * Set the helper program command to use.
	 *
	 * @param command
	 *        the command to set; if {@code null} then {@link #DEFAULT_COMMAND}
	 *        will be used
	 */
	public final void setCommand(String command) {
		this.command = (command != null ? command : DEFAULT_COMMAND);
	}

	/**
	 * Get the backup name being managed.
	 *
	 * @return the name
	 */
	public final @Nullable String getName() {
		return name;
	}

	/**
	 * Set the backup name to manage.
	 *
	 * @param name
	 *        the name to set
	 */
	public final void setName(@Nullable String name) {
		this.name = name;
	}

	/**
	 * Set the resource bundle directory path.
	 *
	 * @param resourceBundleDir
	 *        the resourceBundleDir to set; if {@code null} then
	 *        {@link #DEFAULT_RESOURCE_BUNDLE_DIR} will be used
	 */
	public final void setResourceBundleDir(String resourceBundleDir) {
		this.resourceBundleDir = (resourceBundleDir != null ? resourceBundleDir
				: DEFAULT_RESOURCE_BUNDLE_DIR);
	}

	/**
	 * Get the backup resource path extension to use.
	 *
	 * @return the extension; defaults to
	 *         {@link #DEFAULT_BACKUP_RESOURCE_EXTENSION}
	 */
	public final String getBackupResourceExtension() {
		return backupResourceExtension;
	}

	/**
	 * Set the backup resource path extension to use.
	 *
	 * @param backupResourceExtension
	 *        the backupResourceExtension to set; if {@code null} then
	 *        {@link #DEFAULT_BACKUP_RESOURCE_EXTENSION} will be used
	 */
	public final void setBackupResourceExtension(String backupResourceExtension) {
		this.backupResourceExtension = (backupResourceExtension != null ? backupResourceExtension
				: DEFAULT_BACKUP_RESOURCE_EXTENSION);
	}

	/**
	 * Get the "use sudo" flag.
	 *
	 * @return {@literal true} to use the {@literal sudo} command; defaults to
	 *         {@link #DEFAULT_USE_SUDO}
	 */
	public final boolean isUseSudo() {
		return useSudo;
	}

	/**
	 * Set the "use sudo" flag.
	 *
	 * @param useSudo
	 *        {@literal true} to use the {@literal sudo} command
	 */
	public final void setUseSudo(boolean useSudo) {
		this.useSudo = useSudo;
	}

}
