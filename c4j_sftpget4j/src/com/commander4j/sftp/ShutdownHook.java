package com.commander4j.sftp;

import org.apache.logging.log4j.LogManager;

import com.commander4j.util.JWait;


public class ShutdownHook extends Thread
{

	@Override
	public void run()
	{
		
		JWait wait = new JWait();
		Transfer.sftpget.requestStop();
		Transfer.sftpget.interrupt();

		while (Transfer.sftpget.isAlive())
		{

			wait.oneSec();
		}
	
		LogManager.shutdown();
	}

}
