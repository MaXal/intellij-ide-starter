package com.intellij.ide.starter.sdk

import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.ide.starter.path.GlobalPaths
import com.intellij.ide.starter.runner.SetupException
import com.intellij.openapi.projectRoots.impl.jdkDownloader.*
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.tools.ide.util.common.logOutput
import com.intellij.tools.ide.util.common.withRetryBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

class DownloadJDKException() : SetupException("JDK list is empty")

object JdkDownloaderFacade {

  val jdk8: JdkDownloadItem get() = jdkDownloader(JdkVersion.JDK_8.toString())
  val jdk11: JdkDownloadItem get() = jdkDownloader(JdkVersion.JDK_11.toString())
  val jdk17: JdkDownloadItem get() = jdkDownloader(JdkVersion.JDK_17.toString())
  val jbrJcef17: JdkDownloadItem get() = jdkDownloader(JdkVersion.JDK_17.toString(), jbr = true)
  val jdk20: JdkDownloadItem get() = jdkDownloader(JdkVersion.JDK_20.toString())
  val jdk21: JdkDownloadItem get() = jdkDownloader(JdkVersion.JDK_21.toString())
  val jbr21: JdkDownloadItem get() = jdkDownloader(JdkVersion.JDK_21.toString(), jbr = true)

  const val MINIMUM_JDK_FILES_COUNT: Int = 42

  fun jdkDownloader(version: String, jdks: Iterable<JdkDownloadItem> = allJdks, jbr: Boolean = false): JdkDownloadItem {
    val jdkName =
      when (jbr) {
        true -> "jbr"
        else -> "corretto"
      }

    return jdks.singleOrNull {
      it.jdk.sharedIndexAliases.contains("$jdkName-$version")
    } ?: throw DownloadJDKException()
  }

  val allJdks: List<JdkDownloadItem> by lazy {
    listJDKs(JdkPredicate.forCurrentProcess())
  }

  val allJdksForWSL: List<JdkDownloadItem> by lazy {
    listJDKs(JdkPredicate.forWSL(null))
  }

  private fun listJDKs(predicate: JdkPredicate): List<JdkDownloadItem> {
    val allJDKs = JdkListDownloader().downloadModelForJdkInstaller(null, predicate)
    logOutput("Total JDKs: ${allJDKs.map { it.fullPresentationText }}")

    val allVersions = allJDKs.map { it.jdkVersion }.toSortedSet()
    logOutput("JDK versions: $allVersions")

    return allJDKs.map { jdk ->
      JdkDownloadItem(jdk) {
        downloadJdkItem(jdk, predicate)
      }
    }
  }

  private fun downloadJdkItem(jdk: JdkItem, predicate: JdkPredicate): JdkItemPaths {
    val targetJdkHome = determineTargetJdkHome(predicate, jdk)
    val targetHomeMarker = targetJdkHome.resolve("home.link")
    logOutput("Checking JDK at $targetJdkHome")

    if (shouldDownloadJdk(targetJdkHome, targetHomeMarker)) {
      downloadAndInstallJdk(jdk, targetJdkHome, targetHomeMarker)
    }

    val javaHome = Path.of(Files.readString(targetHomeMarker))
    require(javaHome.resolve(getJavaBin(predicate)).isRegularFile()) {
      @OptIn(ExperimentalPathApi::class)
      targetJdkHome.deleteRecursively()
      "corrupted JDK home: $targetJdkHome (now deleted)"
    }

    return JdkItemPaths(homePath = javaHome, installPath = targetJdkHome)
  }

  @Suppress("SSBasedInspection")
  private fun determineTargetJdkHome(predicate: JdkPredicate, jdk: JdkItem): Path =
    if (isWSL(predicate)) {
      runBlocking(Dispatchers.IO) {
        val wslDistribution = WslDistributionManager.getInstance().installedDistributions.find { it.version == 2 }
                              ?: throw SetupException("WSL 2 distribution is not found")
        Path.of(wslDistribution.getWindowsPath("${wslDistribution.userHome}/.jdks/${jdk.installFolderName}"))
      }
    }
    else {
      GlobalPaths.instance.getCacheDirectoryFor("jdks").resolve(jdk.installFolderName)
    }

  private fun isWSL(predicate: JdkPredicate): Boolean {
    return (predicate == JdkPredicate.forWSL(null) &&
            SystemInfoRt.isWindows &&
            WslDistributionManager.getInstance().installedDistributions.isNotEmpty())
  }

  private fun shouldDownloadJdk(targetJdkHome: Path, targetHomeMarker: Path): Boolean =
    !Files.isRegularFile(targetHomeMarker) || @OptIn(ExperimentalPathApi::class) targetJdkHome.walk().count() < MINIMUM_JDK_FILES_COUNT

  private fun downloadAndInstallJdk(jdk: JdkItem, targetJdkHome: Path, targetHomeMarker: Path) {
    withRetryBlocking(messageOnFailure = "Failure on downloading/installing JDK", retries = 5) {
      logOutput("Downloading JDK at $targetJdkHome")
      @OptIn(ExperimentalPathApi::class)
      targetJdkHome.deleteRecursively()

      val jdkInstaller = JdkInstaller()
      val request = jdkInstaller.prepareJdkInstallationDirect(jdk, targetPath = targetJdkHome)
      jdkInstaller.installJdk(request, targetHomeMarker)
    }
  }

  private fun downloadFileFromUrl(urlString: String, destinationPath: Path) {
    destinationPath.createParentDirectories()
    URL(urlString).openStream().use { inputStream ->
      Files.copy(inputStream, destinationPath, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private fun JdkInstaller.installJdk(request: JdkInstallRequest, markerFile: Path) {
    val item = request.item
    val targetDir = request.installDir
    // TODO Integrate EelApi here.
    val wslDistribution = wslDistributionFromPath(targetDir)
    if (wslDistribution != null && item.os != "linux") {
      error("Cannot install non-linux JDK into WSL environment to $targetDir from $item")
    }
    val temp = GlobalPaths.instance.testHomePath.resolve("tmp/jdk").toAbsolutePath().toString()
    val downloadFile = Path.of(temp, "jdk-${System.nanoTime()}-${item.archiveFileName}")
    try {
      try {
        downloadFileFromUrl(item.url, downloadFile)
        if (!downloadFile.isRegularFile()) {
          throw RuntimeException("Downloaded file does not exist: $downloadFile")
        }
      }
      catch (t: Throwable) {
        throw RuntimeException("Failed to download ${item.fullPresentationText} from ${item.url}: ${t.message}", t)
      }

      try {
        if (wslDistribution != null) {
          JdkInstallerWSL.unpackJdkOnWsl(wslDistribution, item.packageType, downloadFile, targetDir, item.packageRootPrefix)
        }
        else {
          item.packageType.openDecompressor(downloadFile).let {
            val fullMatchPath = item.packageRootPrefix.trim('/')
            if (fullMatchPath.isBlank()) it else it.removePrefixPath(fullMatchPath)
          }.extract(targetDir)
        }
      }
      catch (t: Throwable) {
        throw RuntimeException("Failed to extract ${item.fullPresentationText}. ${t.message}", t)
      }
      Files.writeString(markerFile, request.javaHome.toRealPath().toString(), Charsets.UTF_8)
    }
    finally {
      downloadFile.deleteIfExists()
    }
  }

  private fun getJavaBin(predicate: JdkPredicate): String = "bin/java" + when {
    (SystemInfo.isWindows && predicate != JdkPredicate.forWSL(null)) -> ".exe"
    else -> ""
  }
}
