package de.pixart.messenger.services;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.NoSuchPaddingException;

import de.pixart.messenger.Config;
import de.pixart.messenger.R;
import de.pixart.messenger.entities.Account;
import de.pixart.messenger.entities.Conversation;
import de.pixart.messenger.entities.Message;
import de.pixart.messenger.persistance.DatabaseBackend;
import de.pixart.messenger.persistance.FileBackend;
import de.pixart.messenger.utils.EncryptDecryptFile;
import de.pixart.messenger.xmpp.jid.Jid;

public class ExportLogsService extends Service {

	private static final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
	private static final String DIRECTORY_STRING_FORMAT = FileBackend.getConversationsDirectory() + "/chats/%s";
	private static final String MESSAGE_STRING_FORMAT = "(%s) %s: %s\n";
	private static final int NOTIFICATION_ID = 1;
	private static AtomicBoolean running = new AtomicBoolean(false);
	private DatabaseBackend mDatabaseBackend;
	private List<Account> mAccounts;

	@Override
	public void onCreate() {
		mDatabaseBackend = DatabaseBackend.getInstance(getBaseContext());
		mAccounts = mDatabaseBackend.getAccounts();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (running.compareAndSet(false, true)) {
			new Thread(new Runnable() {
				@Override
				public void run() {
					export();
					stopForeground(true);
					running.set(false);
					stopSelf();
				}
			}).start();
		}
		return START_NOT_STICKY;
	}

	private void export() {
		List<Conversation> conversations = mDatabaseBackend.getConversations(Conversation.STATUS_AVAILABLE);
		conversations.addAll(mDatabaseBackend.getConversations(Conversation.STATUS_ARCHIVED));
		NotificationManager mNotifyManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(getBaseContext());
		mBuilder.setContentTitle(getString(R.string.app_name))
				.setContentText(getString(R.string.notification_export_logs_title))
				.setSmallIcon(R.drawable.ic_import_export_white_24dp)
				.setProgress(0, 0, true);
		startForeground(NOTIFICATION_ID, mBuilder.build());
        mNotifyManager.notify(NOTIFICATION_ID, mBuilder.build());

		for (Conversation conversation : conversations) {
			writeToFile(conversation);
		}
		if (mAccounts.size() == 1) {
			try {
				ExportDatabase();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void writeToFile(Conversation conversation) {
		Jid accountJid = resolveAccountUuid(conversation.getAccountUuid());
		Jid contactJid = conversation.getJid();

		File dir = new File(String.format(DIRECTORY_STRING_FORMAT,accountJid.toBareJid().toString()));
		dir.mkdirs();

		BufferedWriter bw = null;
		try {
			for (Message message : mDatabaseBackend.getMessagesIterable(conversation)) {
				if (message.getType() == Message.TYPE_TEXT || message.hasFileOnRemoteHost()) {
					String date = simpleDateFormat.format(new Date(message.getTimeSent()));
					if (bw == null) {
						bw = new BufferedWriter(new FileWriter(
								new File(dir, contactJid.toBareJid().toString() + ".txt")));
					}
					String jid = null;
					switch (message.getStatus()) {
						case Message.STATUS_RECEIVED:
							jid = getMessageCounterpart(message);
							break;
						case Message.STATUS_SEND:
						case Message.STATUS_SEND_RECEIVED:
						case Message.STATUS_SEND_DISPLAYED:
							jid = accountJid.toBareJid().toString();
							break;
					}
					if (jid != null) {
						String body = message.hasFileOnRemoteHost() ? message.getFileParams().url.toString() : message.getBody();
						bw.write(String.format(MESSAGE_STRING_FORMAT, date, jid,
								body.replace("\\\n", "\\ \n").replace("\n", "\\ \n")));
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				if (bw != null) {
					bw.close();
				}
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}
	}

	private Jid resolveAccountUuid(String accountUuid) {
		for (Account account : mAccounts) {
			if (account.getUuid().equals(accountUuid)) {
				return account.getJid();
			}
		}
		return null;
	}

	private String getMessageCounterpart(Message message) {
		String trueCounterpart = (String) message.getContentValues().get(Message.TRUE_COUNTERPART);
		if (trueCounterpart != null) {
			return trueCounterpart;
		} else {
			return message.getCounterpart().toString();
		}
	}

	public void ExportDatabase() throws IOException {
		Account mAccount = mAccounts.get(0);
		// Get hold of the db:
		FileInputStream InputFile = new FileInputStream(this.getDatabasePath(DatabaseBackend.DATABASE_NAME));
		// Set the output folder on the SDcard
		File directory = new File(FileBackend.getConversationsDirectory() + "/database/");
		// Create the folder if it doesn't exist:
		if (!directory.exists()) {
			directory.mkdirs();
		}
		//Delete old database export file
		File temp_db_file = new File(directory + "/database.bak");
		if (temp_db_file.exists()) {
            Log.d(Config.LOGTAG, "Delete temp database backup file from " + temp_db_file.toString());
			temp_db_file.delete();
		}
		// Set the output file stream up:
		FileOutputStream OutputFile = new FileOutputStream(directory.getPath() + "/database.db.crypt");

		String EncryptionKey = mAccount.getPassword(); //get account password

		Log.d(Config.LOGTAG,"Password for " + mAccount.getJid().toString() + " is " + EncryptionKey);
		// encrypt database from the input file to the output file
		try {
			EncryptDecryptFile.encrypt(InputFile, OutputFile, EncryptionKey);
		} catch (NoSuchAlgorithmException e) {
			Log.d(Config.LOGTAG,"Database exporter: encryption failed with " + e);
			e.printStackTrace();
		} catch (NoSuchPaddingException e) {
			Log.d(Config.LOGTAG,"Database exporter: encryption failed with " + e);
			e.printStackTrace();
		} catch (InvalidKeyException e) {
			Log.d(Config.LOGTAG,"Database exporter: encryption failed (invalid key) with " + e);
			e.printStackTrace();
		} catch (IOException e) {
			Log.d(Config.LOGTAG,"Database exporter: encryption failed (IO) with " + e);
			e.printStackTrace();
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
