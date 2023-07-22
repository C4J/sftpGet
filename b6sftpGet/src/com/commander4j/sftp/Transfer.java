package com.commander4j.sftp;

import java.io.File;
import java.io.IOException;
import java.util.Hashtable;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.core.util.DefaultShutdownCallbackRegistry;
import org.apache.logging.log4j.spi.LoggerContextFactory;

import com.commander4j.email.EmailQueue;
import com.commander4j.email.EmailThread;
import com.commander4j.util.EncryptData;
import com.commander4j.util.JCipher;
import com.commander4j.util.JUtility;
import com.commander4j.xml.JXMLDocument;

import net.schmizz.keepalive.KeepAliveProvider;
import net.schmizz.sshj.Config;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

public class Transfer extends Thread
{
	public static Transfer sftpget;
	public static Logger logger = org.apache.logging.log4j.LogManager.getLogger((Transfer.class));
	public static LoggerContextFactory factory = LogManager.getFactory();
	public static Hashtable<String, String> general = new Hashtable<String, String>();
	public static Hashtable<String, String> security = new Hashtable<String, String>();
	public static Hashtable<String, String> source = new Hashtable<String, String>();
	public static Hashtable<String, String> destination = new Hashtable<String, String>();
	public static boolean run = true;;
	public static SSHClient sshClient;
	public static SFTPClient sftpClient;
	public static JUtility utils = new JUtility();
	public static EmailQueue emailqueue = new EmailQueue();
	public static EmailThread emailthread;
	public static String version = "4.00";
	public static Long pollFrequencySeconds = (long) 0;

	public static void main(String[] args)
	{

		Transfer.initLogging("");

		logger.info("sftpGet Starting");

		ShutdownHook shutdownHook = new ShutdownHook();
		Runtime.getRuntime().addShutdownHook(shutdownHook);

		sftpget = new Transfer();
		sftpget.loadConfigXML();

		emailqueue.setEnabled(Boolean.valueOf(utils.replaceNullStringwithDefault(general.get("emailEnabled"), "no")));

		emailthread = new EmailThread();

		emailthread.start();

		emailqueue.addToQueue("Monitor", utils.replaceNullStringwithDefault(general.get("title"), "SFTP Get"), "Program started on " + utils.getClientName(), "");

		try
		{
			pollFrequencySeconds = Long.valueOf(source.get("pollFrequencySeconds"));
		}
		catch (Exception ex)
		{
			pollFrequencySeconds = (long) 10000;
		}

		logger.debug("SFTP Get Version " + version);
		logger.debug("currentDirectory :" + source.get("localDir"));
		logger.debug("Source Folder Polling Frequency :" + pollFrequencySeconds);
		logger.debug("Scan for files...");

		while (run)
		{
			sftpget.transferFiles();

			try
			{
				Thread.sleep(pollFrequencySeconds);
			}
			catch (InterruptedException e)
			{
				run = false;
			}
		}

		emailqueue.addToQueue("Monitor", utils.replaceNullStringwithDefault(general.get("title"), "SFTP Get"), "Program stopped on " + utils.getClientName(), "");

		emailthread.shutdown();

		while (emailthread.isAlive())
		{
			try
			{
				Thread.sleep(100);
			}
			catch (InterruptedException e)
			{

			}
		}

		logger.info("sftpGet Shutdown");

		System.exit(0);
	}

	private boolean connect()
	{
		boolean result = false;

		try
		{

			Config config = new DefaultConfig();
			config.setKeepAliveProvider(KeepAliveProvider.KEEP_ALIVE);
			sshClient = new SSHClient(config);

			sshClient.setTimeout(2000);

			if (security.get("checkKnownHosts").equals("no"))
			{
				sshClient.addHostKeyVerifier(new PromiscuousVerifier());
			}

			logger.info("authType=" + security.get("authType"));

			// * Method 1* //
			if (security.get("authType").equals("user password"))
			{

				sshClient.connect(security.get("remoteHost"), Integer.valueOf(security.get("remotePort")));

				sshClient.authPassword(security.get("username"), security.get("password"));

			}

			// * Method 1* //
			if (security.get("authType").equals("user public key"))
			{
				String username = security.get("username");
				File privateKey = new File(security.get("privateKeyFile"));

				KeyProvider keys;
				keys = sshClient.loadKeys(privateKey.getPath());

				sshClient.connect(security.get("remoteHost"), Integer.valueOf(security.get("remotePort")));

				sshClient.authPublickey(username, keys);
			}

			sftpClient = sshClient.newSFTPClient();

			result = true;
		}
		catch (UserAuthException e)
		{
			logger.error(e.getMessage());
		}
		catch (TransportException e)
		{
			logger.error(e.getMessage());
		}

		catch (IOException e)
		{
			logger.error(e.getMessage());
		}

		return result;
	}

	private boolean disconnect()
	{
		boolean result = false;
		try
		{
			sftpClient.close();
			sshClient.disconnect();
			result = true;
		}
		catch (IOException e)
		{
			logger.error(e.getMessage());
		}
		return result;
	}

	public void requestStop()
	{
		run = false;
	}

	private void transferFiles()
	{

		int getCount = 0;
		if (connect())
		{

			String localDir = destination.get("localDir");
			String remoteDir = source.get("remoteDir");
			String localTempExtension = destination.get("tempFileExtension");
			String getList = utils.getClientName() + " transferred the following file(s) \n\n";

			logger.debug("localDir = [" + localDir + "]");
			logger.debug("remoteDir = [" + remoteDir + "]");
			logger.debug("localTempExtension = [" + localTempExtension + "]");

			List<RemoteResourceInfo> remoteDirectory;
			try
			{

				logger.debug("executing ls on remote directory");
				try
				{
					logger.debug("--------------------------------------");
					logger.debug("Looking into "+source.get("remoteDir"));
					logger.debug("--------------------------------------");
					
					remoteDirectory = sftpClient.ls(source.get("remoteDir"));
					
					logger.debug(remoteDirectory.toString());

					logger.debug(remoteDirectory.size() + " files found.");

					if (remoteDirectory.size() > 0)
					{
						for (int temp = 0; temp < remoteDirectory.size(); temp++)
						{
							RemoteResourceInfo resInfo = remoteDirectory.get(temp);
							String remoteFilename = resInfo.getName();
							logger.debug("******************************************************************************************************************");
							logger.debug("******** R E M O T E  F I L E N A M E ******* = [" + remoteFilename + "]");
							logger.debug("******************************************************************************************************************");
							boolean isFile = resInfo.isRegularFile();
							logger.debug("isFile = [" + isFile + "]");
							boolean isDirectory = resInfo.isDirectory();
							logger.debug("isDirectory = [" + isDirectory + "]");
							FileAttributes attrib = resInfo.getAttributes();
							// long modificationTime = attrib.getMtime();
							// Set<FilePermission> perms =
							// attrib.getPermissions();

							Long remoteSize = attrib.getSize();
							logger.debug("remoteSize = [" + remoteSize + "]");
							Long localSize = (long) 0;

							// Check that remote directory entry is a file
							if (isFile == true)
							{
								// Check remote entry is not a directory
								if (isDirectory == false)
								{
									// Check that remote file has the specified
									// file
									// extension
									if (remoteFilename.toUpperCase().endsWith(source.get("remoteFileMask").toUpperCase()))
									{

										// GET GOES HERE

										try
										{

											if (new File(localDir + remoteFilename).exists())
											{
												logger.debug("Local file [" + localDir + remoteFilename + "] already exists and will be deleted prior to GET");
												FileUtils.deleteQuietly(new File(localDir + remoteFilename));
											}

											if (new File(localDir + remoteFilename + localTempExtension).exists())
											{
												logger.debug("Local temp file [" + localDir + remoteFilename + localTempExtension + "] already exists and will be deleted prior to GET");
												FileUtils.deleteQuietly(new File(localDir + remoteFilename + localTempExtension));
											}

											// GET the remote file and download
											// with
											// a .tmp file extension.
											logger.debug("GET [" + remoteDir + remoteFilename + "] and save to [" + localDir + remoteFilename + localTempExtension + "]");

											try
											{

												sftpClient.get(remoteDir + remoteFilename, localDir + remoteFilename + localTempExtension);
												
												localSize = FileUtils.sizeOf(new File(localDir + remoteFilename + localTempExtension));

												// See if the downloaded file is the
												// same size as the remote file.
												if (localSize.compareTo(remoteSize) == 0)
												{
													logger.debug("Rename temp file  [" + localDir + remoteFilename + localTempExtension + "] to [" + localDir + remoteFilename + "]");
													FileUtils.moveFile(FileUtils.getFile(localDir + remoteFilename + localTempExtension), FileUtils.getFile(localDir + remoteFilename));

													logger.debug("rm [" + remoteDir + remoteFilename + "]");
													sftpClient.rm(remoteDir + remoteFilename);

													getList = getList + remoteFilename + "\n";
													getCount++;
												}
												else
												{
													logger.error("Filesize [" + remoteFilename + "] mismatch !\n" + " Remote File Size is :" + remoteSize + " bytes.\nLocal File Size is :" + localSize + " bytes.");

													FileUtils.deleteQuietly(new File(localDir + remoteFilename + localTempExtension));
												}
											}
											catch (IOException ex)
											{
												logger.error("Error when trying to perform sftpClient.get " + ex.getMessage());
											}



										}
										catch (Exception e1)
										{
											e1.printStackTrace();
										}

									}
								}
							}
						}

						getList = getList + "\n\nfrom " + security.get("remoteHost");

						if (getCount > 0)
						{
							emailqueue.addToQueue("Monitor", utils.replaceNullStringwithDefault(general.get("title"), "SFTP Get"), getList, "");
						}

					}
				}
				catch (IOException e)
				{
					logger.error("Error when trying to perform sftpClient.ls " + e.getMessage());
				}

			}
			catch (Exception e1)
			{
				e1.printStackTrace();
			}

			disconnect();
		}

	}

	private boolean loadConfigXML()
	{
		boolean result = false;

		logger.info("loadConfigXML");
		JCipher cipher = new JCipher(EncryptData.key);

		String xmlConfig = (System.getProperty("user.dir") + File.separator + "xml" + File.separator + "config" + File.separator + "sftpGet.xml");

		JXMLDocument xmlDoc;
		xmlDoc = new JXMLDocument(xmlConfig);

		// Config //

		int seq = 1;
		String id = "";
		String value = "";
		String encrypted = "";

		// General //

		seq = 1;

		while (xmlDoc.findXPath("/config/sftpGet2/general/value[" + String.valueOf(seq) + "]/@id").equals("") == false)
		{
			id = xmlDoc.findXPath("/config/sftpGet2/general/value[" + String.valueOf(seq) + "]/@id");
			value = xmlDoc.findXPath("/config/sftpGet2/general/value[" + String.valueOf(seq) + "]");
			encrypted = xmlDoc.findXPath("/config/sftpGet2/general/value[" + String.valueOf(seq) + "]/@encrypted");

			if (encrypted.equals("yes"))
			{
				value = cipher.decode(value);
			}

			if (id.equals("") == false)
			{
				general.put(id, value);
			}
			seq++;
		}

		// Security //

		seq = 1;

		while (xmlDoc.findXPath("/config/sftpGet2/security/value[" + String.valueOf(seq) + "]/@id").equals("") == false)
		{
			id = xmlDoc.findXPath("/config/sftpGet2/security/value[" + String.valueOf(seq) + "]/@id");
			value = xmlDoc.findXPath("/config/sftpGet2/security/value[" + String.valueOf(seq) + "]");
			encrypted = xmlDoc.findXPath("/config/sftpGet2/security/value[" + String.valueOf(seq) + "]/@encrypted");

			if (encrypted.equals("yes"))
			{
				value = cipher.decode(value);
			}

			if (id.equals("") == false)
			{
				security.put(id, value);
			}
			seq++;
		}

		// Source //

		seq = 1;

		while (xmlDoc.findXPath("/config/sftpGet2/source/value[" + String.valueOf(seq) + "]/@id").equals("") == false)
		{
			id = xmlDoc.findXPath("/config/sftpGet2/source/value[" + String.valueOf(seq) + "]/@id");
			value = xmlDoc.findXPath("/config/sftpGet2/source/value[" + String.valueOf(seq) + "]");
			encrypted = xmlDoc.findXPath("/config/sftpGet2/source/value[" + String.valueOf(seq) + "]/@encrypted");

			if (encrypted.equals("yes"))
			{
				value = cipher.decode(value);
			}

			if (id.equals("") == false)
			{
				source.put(id, value);
			}
			seq++;
		}

		// Destination //

		seq = 1;

		while (xmlDoc.findXPath("/config/sftpGet2/destination/value[" + String.valueOf(seq) + "]/@id").equals("") == false)
		{
			id = xmlDoc.findXPath("/config/sftpGet2/destination/value[" + String.valueOf(seq) + "]/@id");
			value = xmlDoc.findXPath("/config/sftpGet2/destination/value[" + String.valueOf(seq) + "]");
			encrypted = xmlDoc.findXPath("/config/sftpGet2/destination/value[" + String.valueOf(seq) + "]/@encrypted");

			if (encrypted.equals("yes"))
			{
				value = cipher.decode(value);
			}

			if (id.equals("") == false)
			{
				destination.put(id, value);
			}
			seq++;
		}

		//source.put("remoteDir", Transfer.utils.formatPathTerminator(source.get("remoteDir")));
		source.put("remoteDir", source.get("remoteDir"));
		destination.put("localDir", destination.get("localDir"));

		return result;
	}

	public static void initLogging(String filename)
	{
		if (filename.isEmpty())
		{
			filename = System.getProperty("user.dir") + File.separator + "xml" + File.separator + "config" + File.separator + "log4j2.xml";
		}

		LoggerContext context = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
		File file = new File(filename);

		context.setConfigLocation(file.toURI());

		if (factory instanceof Log4jContextFactory)
		{
			// LOG.info("register shutdown hook");
			Log4jContextFactory contextFactory = (Log4jContextFactory) factory;

			((DefaultShutdownCallbackRegistry) contextFactory.getShutdownCallbackRegistry()).stop();
		}

	}

}
