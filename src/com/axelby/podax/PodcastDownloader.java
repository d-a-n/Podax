package com.axelby.podax;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Vector;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.axelby.podax.R.drawable;
import com.axelby.podax.ui.MainActivity;

public class PodcastDownloader extends Service {
	private DownloadThread _downloadThread;
	private boolean _started = false;
	Handler _handler = new Handler();
	Cursor _queueWatcher;

	String[] projection = {
			PodcastProvider.COLUMN_ID,
			PodcastProvider.COLUMN_TITLE,
			PodcastProvider.COLUMN_MEDIA_URL,
			PodcastProvider.COLUMN_FILE_SIZE,
	};

	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}

	@Override
	public void onCreate() {
		_downloadThread = new DownloadThread();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (!_started) {
			_queueWatcher = PodcastDownloader.this.getContentResolver().query(
					PodcastProvider.QUEUE_URI, projection, null, null,
					PodcastProvider.COLUMN_QUEUE_POSITION);
			_queueWatcher.registerContentObserver(new ContentObserver(new Handler()) {
				@Override
				public void onChange(boolean selfChange) {
					Log.d("Podax", "downloader - queue contentobserver noticed a change");
					super.onChange(selfChange);

					if (_downloadThread == null) {
						_downloadThread = new DownloadThread();
						_downloadThread.start();
					}
					else
						_downloadThread.refreshCursor();
				}
			});
			_downloadThread = new DownloadThread();
			_downloadThread.start();
		}
		_started = true;

	    return START_STICKY;
	}

	@Override
	public void onDestroy() {
		_downloadThread.interrupt();
	}

	class DownloadThread extends Thread {
		// the cursor to go through
		Cursor _queue = null;
		// if the queue changed, hold the cursor in nextqueue
		Cursor _nextQueue = null;

		public void refreshCursor() {
			Log.d("Podax", "downloader - download thread noticed a change");
			if (!Helper.ensureWifi(PodcastDownloader.this))
				return;

			// load the next cursor
			Log.d("Podax", "downloader - download thread loading the queue");
			if (_nextQueue != null && !_nextQueue.isClosed())
				_nextQueue.close();
			_nextQueue = PodcastDownloader.this.getContentResolver().query(
					PodcastProvider.QUEUE_URI, projection, null, null,
					PodcastProvider.COLUMN_QUEUE_POSITION);
		}

		@Override
		public void run() {
			Thread.currentThread().setName("PodcastDownloader_Download");
			try {
				if (!Helper.ensureWifi(PodcastDownloader.this))
					return;

				verifyDownloadedFiles();

				if (_queue == null) {
					_queue = PodcastDownloader.this.getContentResolver().query(
							PodcastProvider.QUEUE_URI, projection, null, null,
							PodcastProvider.COLUMN_QUEUE_POSITION);
				}
				downloadPodcastsInQueue();

				// if there's another queue cursor waiting, go through it
				while (_nextQueue != null) {
					Log.d("Podax", "downloader - download thread noticed the queue changed");
					_queue.close();
					_queue = _nextQueue;
					_nextQueue = null;
					downloadPodcastsInQueue();
				}

				Log.d("Podax", "downloader - download thread finished downloading podcasts");
			} finally {
				if (_queue != null && !_queue.isClosed())
					_queue.close();
				_queue = null;
				if (_nextQueue != null && !_nextQueue.isClosed())
					_nextQueue.close();
				_nextQueue = null;

				removeDownloadNotification();

				_downloadThread = null;
			}
		}

		private void downloadPodcastsInQueue() {
			_queue.moveToFirst();
			while (_queue.moveToNext()) {
				PodcastCursor podcast = new PodcastCursor(PodcastDownloader.this, _queue);

				if (podcast.isDownloaded())
					continue;

				try {
					if (hasQueueChanged())
						return;

					File mediaFile = new File(podcast.getFilename());
					Log.d("Podax", "Downloading " + podcast.getTitle());
					updateDownloadNotification(podcast.getTitle(), 0);

					HttpURLConnection c = createConnection(podcast, mediaFile);

					if (hasQueueChanged())
						return;

					// only valid response codes are 200 and 206
					if (c.getResponseCode() != 200 && c.getResponseCode() != 206)
						continue;

					if (hasQueueChanged())
						return;

					// response code 206 means partial content and range header worked
					if (c.getResponseCode() == 206) {
						// make sure there's more data to download
						if (c.getContentLength() <= 0) {
							podcast.setFileSize(mediaFile.length());
							continue;
						}
					} else {
						// if we did a range, the server doesn't support it
						if (mediaFile.exists())
							mediaFile.delete();
						podcast.setFileSize(c.getContentLength());
					}

					if (hasQueueChanged())
						break;

					if (!downloadFile(podcast.getId(), c, mediaFile)) {
						Log.d("Podax", "downloader - did not finish downloading file");
						continue;
					}
					Log.d("Podax", "downloader - finished downloading the file");

					if (mediaFile.length() == c.getContentLength()) {
						MediaPlayer mp = new MediaPlayer();
						mp.setDataSource(podcast.getFilename());
						mp.prepare();
						podcast.setDuration(mp.getDuration());
						mp.release();
					}

					Log.d("Podax", "Done downloading " + podcast.getTitle());
				} catch (Exception e) {
					Log.e("Podax", "Exception while downloading " + podcast.getTitle(), e);
					removeDownloadNotification();
					break;
				}
			}
		}

		private boolean hasQueueChanged() {
			return _nextQueue != null;
		}

		private boolean shouldStopDownloading(long podcastId) {
			return _nextQueue != null && !isPodcastInQueue(_nextQueue, podcastId);
		}


		private boolean isPodcastInQueue(Cursor queue, long podcastId) {
			queue.moveToFirst();
			while (queue.moveToNext())
				if (queue.getLong(0) == podcastId)
					return true;
				else
					Log.d("Podax", String.valueOf(podcastId) + " != " + queue.getLong(0));
			Log.d("Podax", "podcast not in queue");
			return false;
		}

		private HttpURLConnection createConnection(
				PodcastCursor podcast, File mediaFile)
				throws MalformedURLException, IOException {
			URL u = new URL(podcast.getMediaUrl());
			HttpURLConnection c = (HttpURLConnection)u.openConnection();
			if (mediaFile.exists() && mediaFile.length() > 0)
				c.setRequestProperty("Range", "bytes=" + mediaFile.length() + "-");
			return c;
		}

		private boolean downloadFile(long podcastId, HttpURLConnection conn, File file) {
			FileOutputStream outstream = null;
			InputStream instream = null;
			try {
				outstream = new FileOutputStream(file, true);
				instream = conn.getInputStream();
				int read;
				byte[] b = new byte[1024*64];
				while (!shouldStopDownloading(podcastId)
						&& (read = instream.read(b, 0, b.length)) != -1)
					outstream.write(b, 0, read);
			} catch (Exception e) {
				return false;
			} finally {
				close(outstream);
				close(instream);
			}
			return file.length() == conn.getContentLength();
		}
	}

	private void verifyDownloadedFiles() {
		Vector<String> validMediaFilenames = new Vector<String>();
		String[] projection = new String[] {
				PodcastProvider.COLUMN_ID,
				PodcastProvider.COLUMN_MEDIA_URL,
		};
		Uri queueUri = Uri.withAppendedPath(PodcastProvider.URI, "queue");
		Cursor c = getContentResolver().query(queueUri, projection, null, null, null);
		while (c.moveToNext())
			validMediaFilenames.add(new PodcastCursor(this, c).getFilename());
		c.close();

		File dir = new File(PodcastCursor.getStoragePath());
		for (File f : dir.listFiles()) {
			// make sure the file is a media file
			String extension = PodcastCursor.getExtension(f.getName());
			String[] mediaExtensions = new String[] { "mp3", "ogg", "wma", };
			if (Arrays.binarySearch(mediaExtensions, extension) < 0)
				continue;
			if (!validMediaFilenames.contains(f.getAbsolutePath())) {
				Log.w("Podax", "deleting file " + f.getName());
				f.delete();
			}
		}
	}

	public static void close(Closeable c) {
		if (c == null)
			return;
		try {
			c.close();
		} catch (IOException e) {
		}
	}

	void updateDownloadNotification(String title, long downloaded) {
		Intent notificationIntent = MainActivity.getSubscriptionIntent(this);
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

		Notification notification = new NotificationCompat.Builder(this)
				.setSmallIcon(drawable.icon)
				.setTicker("Downloading podcast: " + title)
				.setWhen(System.currentTimeMillis())
				.setContentTitle("Downloading Podcast")
				.setContentText(title)
				.setContentIntent(contentIntent)
				.setOngoing(true)
				.getNotification();
		
		NotificationManager notificationManager = (NotificationManager) 
				getSystemService(Context.NOTIFICATION_SERVICE);
		notificationManager.notify(Constants.PODCAST_DOWNLOAD_ONGOING, notification);
	}

	void removeDownloadNotification() {
		String ns = Context.NOTIFICATION_SERVICE;
		NotificationManager notificationManager = (NotificationManager) getSystemService(ns);
		notificationManager.cancel(Constants.PODCAST_DOWNLOAD_ONGOING);
	}
}