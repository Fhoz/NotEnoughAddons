package me.fhoz.notenoughaddons.services;


import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.github.bakedlibs.dough.common.CommonPatterns;
import kong.unirest.GetRequest;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import kong.unirest.UnirestException;
import me.fhoz.notenoughaddons.NotEnoughAddons;

// Edit from the MetricService.java file on the Slimefun4 repo, original @author WalshyDev

public class UpdateService {
    
    private static final String API_URL = "https://api.github.com/";
    private static final String REPO_NAME = "NotEnoughAddons";
    private static final String JAR_NAME = "NotEnoughAddons";
    private static final String RELEASES_URL = API_URL + "repos/Fhoz/" + REPO_NAME + "/releases/latest";
    private static final String DOWNLOAD_URL = "https://github.com/Fhoz/" + REPO_NAME + "/releases/download";
    private static NotEnoughAddons plugin;
    private static File notEnoughAddonsFile;
    private static String pathString;

    private static String neaVersion = null;
    private static boolean hasDownloadedUpdate = false;

    static {
        // @formatter:off (We want this to stay this nicely aligned :D )
        Unirest.config()
            .concurrency(2, 1)
            .setDefaultHeader("User-Agent", "NotEnoughAddons Auto-Updater")
            .setDefaultHeader("Accept", "application/vnd.github.v3+json")
            .enableCookieManagement(false)
            .cookieSpec("ignoreCookies");
        // @formatter:on
    }

    public UpdateService(@Nonnull NotEnoughAddons plugin) {
        UpdateService.plugin = plugin;

        
        UpdateService.notEnoughAddonsFile = new File(JAR_NAME + ".jar");
        Path path = Paths.get(notEnoughAddonsFile.toURI());
        UpdateService.pathString = path.getParent().toString() + "\\plugins";
        UpdateService.notEnoughAddonsFile = new File(pathString, JAR_NAME + ".jar");
    }

     /**
     * This method loads the metric module and starts the metrics collection.
     */
    public void start() {
        if (!notEnoughAddonsFile.exists()) {
            plugin.getLogger().info(JAR_NAME + " does not exist, downloading...");

            if (!download(getLatestVersion())) {
                plugin.getLogger().warning("Failed to download NotEnoughAddons as the file could not be downloaded.");
                return;
            }
        }

        try {
            // Get the version of the current NotEnoughAddons.jar
            neaVersion = plugin.getDescription().getVersion();

            /*
             * If it has not been newly downloaded, auto-updates are enabled
             * AND there's a new version then cleanup, download and start
             */
            if (!hasDownloadedUpdate && hasAutoUpdates() && checkForUpdate(neaVersion)) {
                plugin.getLogger().info("Cleaned up, now re-loading NotEnoughAddons!");
                start();
                return;
            }
        } catch (Exception | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load NotEnoughAddons. Maybe the jar is corrupt?", e);
        }
    }

    /**
     * Checks for a new update and compares it against the current version.
     * If there is a new version available then this returns true.
     *
     * @param currentVersion
     *            The current version which is being used.
     * 
     * @return if there is an update available.
     */
    public static boolean checkForUpdate(@Nullable String currentVersion) {
        if (currentVersion == null || !CommonPatterns.NUMERIC.matcher(currentVersion).matches()) {
            return false;
        }

        int latest = getLatestVersion();

        if (latest > Integer.parseInt(currentVersion)) {
            return download(latest);
        }

        return false;
    }


     /**
     * Gets the latest version available as an int.
     * This is an internal method used by {@link #checkForUpdate(String)}.
     * If it cannot get the version for whatever reason this will return 0, effectively always
     * being behind.
     *
     * @return The latest version as an integer or -1 if it failed to fetch.
     */
    public static int getLatestVersion() {
        try {
            HttpResponse<JsonNode> response = Unirest.get(RELEASES_URL).asJson();

            if (!response.isSuccess()) {
                return -1;
            }

            JsonNode node = response.getBody();

            if (node == null) {
                return -1;
            }

            return node.getObject().getInt("tag_name");
        } catch (UnirestException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to fetch latest builds for NotEnoughAddons: {0}", e.getMessage());
            return -1;
        }
    }

    /**
     * Downloads the version specified to Bukkits's update folder.
     *
     * @param version
     *            The version to download.
     */
    private static boolean download(int version) {
        File file;
        if (!notEnoughAddonsFile.exists()) {
            file = new File(pathString, "NotEnoughAddons.jar");
        }
        else {
            file = new File(pathString + "\\update", "NotEnoughAddons.jar");
        }

        try {
            plugin.getLogger().log(Level.INFO, "# Starting download of NotEnoughAddons build: #{0}", version);

            if (file.exists()) {
                // Delete the file in case we accidentally downloaded it before
                Files.delete(file.toPath());
            }

            AtomicInteger lastPercentPosted = new AtomicInteger();
            GetRequest request = Unirest.get(DOWNLOAD_URL + "/" + version + "/" + JAR_NAME + ".jar");

            HttpResponse<File> response = request.downloadMonitor((b, fileName, bytesWritten, totalBytes) -> {
                int percent = (int) (20 * (Math.round((((double) bytesWritten / totalBytes) * 100) / 20)));

                if (percent != 0 && percent != lastPercentPosted.get()) {
                    plugin.getLogger().info("# Downloading... " + percent + "% " + "(" + bytesWritten + "/" + totalBytes + " bytes)");
                    lastPercentPosted.set(percent);
                }
            }).asFile(file.getPath());

            if (response.isSuccess()) {
                plugin.getLogger().log(Level.INFO, "Successfully downloaded {0} build: #{1}", new Object[] { JAR_NAME, version });
                plugin.getLogger().log(Level.WARNING, "The addon will be updated when the server is restarted!");

                neaVersion = String.valueOf(version);
                hasDownloadedUpdate = true;
                return true;
            }
        } catch (UnirestException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to fetch the latest jar file from the builds page. Perhaps GitHub is down? Response: {0}", e.getMessage());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to replace the old NotEnoughAddons file with the new one. Please do this manually! Error: {0}", e.getMessage());
        }

        return false;
    }

     /**
     * Returns the currently downloaded NotEnoughAddons version.
     * This <strong>can change</strong>! It may be null or an
     * older version before it has downloaded a newer one.
     *
     * @return The current version or null if not loaded.
     */
    @Nullable
    public String getVersion() {
        return neaVersion;
    }

     /**
     * Returns if the current server has NotEnoughAddons auto-updates enabled.
     *
     * @return True if the current server has metrics auto-updates enabled.
     */
    public boolean hasAutoUpdates() {
        return NotEnoughAddons.getInstance().getConfig().getBoolean("options.auto-update");
    }
}
