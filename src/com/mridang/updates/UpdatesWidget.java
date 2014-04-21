package com.mridang.updates;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.Header;
import org.apache.http.entity.StringEntity;
import org.json.JSONArray;
import org.json.JSONObject;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.bugsense.trace.BugSenseHandler;
import com.google.android.apps.dashclock.api.DashClockExtension;
import com.google.android.apps.dashclock.api.ExtensionData;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;

/*
 * This class is the main class that provides the widget
 */
public class UpdatesWidget extends DashClockExtension {

	/* This is the launch intent using for starting the application */
	Intent ittApplication;
	/* The JSON payload of the post request */
	JSONObject jsoRequest = new JSONObject();
	/* The of applications and the versions */
	Map<String, Integer> mapVersions = new HashMap<String, Integer>();
	/* The of applications and the packages */
	Map<String, String> mapPackages = new HashMap<String, String>();
	/* The cached status of the extensions */
	String strStatus = "";
	/* The cached title of the extensions */
	String strTitle = "";
	/* The cached message of the extensions */
	String strMessage = "";
	/* The instance of the network client */
	AsyncHttpClient ascClient = new AsyncHttpClient();

	/*
	 * @see com.google.android.apps.dashclock.api.DashClockExtension#onInitialize(boolean)
	 */
	@Override
	protected void onInitialize(boolean isReconnect) {

		Log.d("UpdatesWidget", "Initializing");

		try {

			Log.d("UpdatesWidget", "Getting the launch intent for the application");
			try {

				PackageManager pkgManager = getPackageManager();
				ittApplication = pkgManager.getLaunchIntentForPackage("com.android.vending");
				ittApplication.addCategory(Intent.CATEGORY_LAUNCHER);

			} catch (Exception e) {
				Log.e("UpdatesWidget", "Error getting the launch intent for application", e);
				return;
			}

			TelephonyManager mgrTelephony = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
			String strAndrid = "" + Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
			String strDevice = "" + mgrTelephony.getDeviceId();
			String strSerial = "" + mgrTelephony.getSimSerialNumber();
			UUID uidUniqid = new UUID(strAndrid.hashCode(), ((long) strDevice.hashCode() << 32) | strSerial.hashCode());

			Log.d("UpdatesWidget", "Building request payload with installed applications");
			jsoRequest.put("android_version", Build.VERSION.SDK_INT);
			jsoRequest.put("model", Build.DEVICE);
			jsoRequest.put("apps", new JSONArray());
			JSONArray jsoUpdates = new JSONArray();

			SharedPreferences speSettings = PreferenceManager.getDefaultSharedPreferences(this);
			final Editor ediSettings = speSettings.edit();
			Integer intFlags = PackageManager.GET_META_DATA | PackageManager.GET_SHARED_LIBRARY_FILES | PackageManager.GET_SIGNATURES;
			for (PackageInfo pkgPackage : getApplicationContext().getPackageManager().getInstalledPackages(intFlags)) {

				String strPackage = pkgPackage.applicationInfo.loadLabel(getPackageManager()).toString();
				if ((pkgPackage.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 1) {
					Log.d("UpdatesWidget", "Skipping system package " + strPackage);
					continue;
				}

				if (speSettings.getBoolean(pkgPackage.packageName, false)) {
					Log.d("UpdatesWidget", "Skipping excluded package " + strPackage);
					continue;
				}

				if ((pkgPackage.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
					Log.d("UpdatesWidget", "Skipping debuggable package " + strPackage);
					continue;
				}

				FileInputStream fisPackage = null;
				try {

					fisPackage = new FileInputStream(new File(pkgPackage.applicationInfo.sourceDir));
					String strSha1 = new String(Hex.encodeHex(DigestUtils.sha1(fisPackage)));
					String strCert = new String(Hex.encodeHex(DigestUtils.sha1(pkgPackage.signatures[0].toByteArray())));

					JSONObject jsoPackage = new JSONObject();
					jsoPackage.put("pname", pkgPackage.packageName);
					jsoPackage.put("vcode", pkgPackage.versionCode);
					jsoPackage.put("vname", pkgPackage.versionName);
					jsoPackage.put("cert_sig", strCert);
					jsoPackage.put("apk_sha1", strSha1);
					jsoRequest.getJSONArray("apps").put(jsoPackage);
					
					mapVersions.put(pkgPackage.packageName, pkgPackage.versionCode);
					mapPackages.put(pkgPackage.packageName, strPackage);
					
					if (speSettings.getBoolean(strSha1, false)) {
						Log.d("UpdatesWidget", "Skipping reported package " + strPackage);
						continue;
					}

					Log.d("UpdatesWidget", "Adding " + strPackage);
					JSONObject jsoUpdate = new JSONObject();
					jsoUpdate.put("pname", pkgPackage.packageName);
					jsoUpdate.put("vcode", pkgPackage.versionCode);
					jsoUpdate.put("vname", pkgPackage.versionName);
					jsoUpdate.put("cert_sig", strCert);
					jsoUpdate.put("apk_sha1", strSha1);
					jsoUpdates.put(jsoUpdate);
					ediSettings.putBoolean(strSha1, true);

				} catch (Exception e) {
					Log.w("UpdatesWidget", "Unable to calculate checksum for the package");
					continue;
				} finally {
					if (fisPackage != null) {
						fisPackage.close();
					}
				}

			}

			if (jsoUpdates.length() > 0) {

				Log.d("UpdatesWidget", "Sending the details of the installed packages");
				String strServer = "http://dashclock-updates.appspot.com";
				String strUrl = String.format("%s/%s/%s/%s/", strServer, uidUniqid.toString().replace("-", ""), Build.VERSION.SDK_INT, Build.DEVICE);
				Log.v("UpdatesWidget", strUrl);
				Log.v("UpdatesWidget", jsoUpdates.toString(2));
				ascClient.post(getApplicationContext(), 
						strUrl, 
						new StringEntity(jsoUpdates.toString(), "UTF-8"), "application/json",
						new AsyncHttpResponseHandler() {
	
					@Override
					public void onSuccess(String strResponse) {
						ediSettings.commit();
						Log.d("UpdatesWidget", "Successfully sent the details");
					}
	
					@Override
					public void onFailure(int intCode, Header[] arrHeaders, byte[] arrBytes, Throwable errError) {
						Log.w("UpdatesWidget", "Error posting the details due to code " + intCode);
					}
	
				});
				
			}

		} catch (Exception e) {
			Log.e("UpdatesWidget", "Unable to fetch the list of packages", e);
			BugSenseHandler.sendException(e);
		}

		super.onInitialize(isReconnect);

	}

	/*
	 * @see com.google.android.apps.dashclock.api.DashClockExtension#onCreate()
	 */
	public void onCreate() {

		super.onCreate();
		Log.d("UpdatesWidget", "Created");
		BugSenseHandler.initAndStartSession(this, getString(R.string.bugsense));

	}

	/*
	 * @see
	 * com.google.android.apps.dashclock.api.DashClockExtension#onUpdateData
	 * (int)
	 */
	@Override
	protected void onUpdateData(int intReason) {

		final ExtensionData edtInformation = new ExtensionData();
		setUpdateWhenScreenOn(true);

		try {

			if (strStatus.isEmpty() || (intReason == DashClockExtension.UPDATE_REASON_PERIODIC || intReason == DashClockExtension.UPDATE_REASON_SETTINGS_CHANGED)) {

				Log.d("UpdatesWidget", "Getting the updates of the installed packages");
				ascClient.setTimeout(60000);
				ascClient.setMaxRetriesAndTimeout(3, 60000);
				ascClient.post(getApplicationContext(), 
						"http://goddchen.de/android/appupdate_crowd/siteground/versions.php", 
						new StringEntity(jsoRequest.toString(), "UTF-8"), "application/json",
						new AsyncHttpResponseHandler() {

					@Override
					public void onSuccess(String strResponse) {

						try {

							BugSenseHandler.addCrashExtraData("Response", strResponse);

							Log.v("UpdatesWidget", "Server reponded with: " + strResponse);
							if (!strResponse.trim().isEmpty()) {

								JSONArray jsoResponse = new JSONArray(strResponse);
								Integer intUpdates = 0;
								String strPackages = "";
								BugSenseHandler.sendEvent("Successful Request");

								Log.d("UpdatesWidget", "Checking which packages have newer versions");
								for (Integer intI = 0; intI < jsoResponse.length(); intI++) {

									JSONObject jsoPackage = jsoResponse.getJSONObject(intI);
									JSONArray jsoVersions = jsoPackage.getJSONArray("by_android_version");
									for (Integer intJ = 0; intJ < jsoVersions.length(); intJ++) {

										Integer intVersion = jsoVersions.getJSONObject(intJ).getInt("vcode");
										if (intVersion > mapVersions.get(jsoPackage.get("pname"))) {

											String strPackage = mapPackages.get(jsoPackage.get("pname"));
											Log.d("UpdatesWidget", strPackage != null ? strPackage : jsoPackage.getString("pname"));
											intUpdates = intUpdates + 1;
											strPackages = strPackages + (strPackages.length() > 0 ?", " : "") + strPackage;
											break;

										}									

									}

								}

								if (intUpdates > 0) {
									strStatus = intUpdates.toString();
									strTitle = getResources().getQuantityString(R.plurals.updates, intUpdates, intUpdates);
									strMessage = strPackages;
								}

							}

						} catch (Exception e) {
							edtInformation.visible(false);
							Log.e("UpdatesWidget", "Encountered an error", e);
							BugSenseHandler.sendException(e);
						} finally {
							BugSenseHandler.clearCrashExtraData();
						}

					}

				});

			}

			if (!strStatus.isEmpty()) {

				edtInformation.visible(true);
				edtInformation.clickIntent(ittApplication);
				edtInformation.status(strStatus);
				edtInformation.expandedBody(strMessage);
				edtInformation.expandedTitle(strTitle);
				Log.d("UpdatesWidget", strTitle);

			}

			if (new Random().nextInt(5) == 0) {

				PackageManager mgrPackages = getApplicationContext().getPackageManager();

				try {

					mgrPackages.getPackageInfo("com.mridang.donate", PackageManager.GET_META_DATA);

				} catch (NameNotFoundException e) {

					Integer intExtensions = 0;
					Intent ittFilter = new Intent("com.google.android.apps.dashclock.Extension");
					String strPackage;

					for (ResolveInfo info : mgrPackages.queryIntentServices(ittFilter, 0)) {

						strPackage = info.serviceInfo.applicationInfo.packageName;
						intExtensions = intExtensions + (strPackage.startsWith("com.mridang.") ? 1 : 0); 

					}

					if (intExtensions > 1) {

						edtInformation.visible(true);
						edtInformation.clickIntent(new Intent(Intent.ACTION_VIEW).setData(Uri.parse("market://details?id=com.mridang.donate")));
						edtInformation.expandedTitle("Please consider a one time purchase to unlock.");
						edtInformation.expandedBody("Thank you for using " + intExtensions + " extensions of mine. Click this to make a one-time purchase or use just one extension to make this disappear.");
						setUpdateWhenScreenOn(true);

					}

				}

			} else {
				setUpdateWhenScreenOn(true);
			}

		} catch (Exception e) {
			edtInformation.visible(false);
			Log.e("UpdatesWidget", "Encountered an error", e);
			BugSenseHandler.sendException(e);
		}

		edtInformation.icon(R.drawable.ic_dashclock);
		publishUpdate(edtInformation);
		Log.d("UpdatesWidget", "Done");

	}

	/*
	 * @see com.google.android.apps.dashclock.api.DashClockExtension#onDestroy()
	 */
	public void onDestroy() {

		super.onDestroy();
		Log.d("UpdatesWidget", "Destroyed");
		BugSenseHandler.closeSession(this);

	}

}