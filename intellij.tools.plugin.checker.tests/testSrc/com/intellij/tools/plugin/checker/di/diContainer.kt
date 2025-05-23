package com.intellij.tools.plugin.checker.di

import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.teamcity.TeamCityCIServer
import com.intellij.ide.starter.community.IdeByLinkDownloader
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.ide.IdeDownloader
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.IdeProduct
import com.intellij.ide.starter.models.IdeProductImp
import com.intellij.ide.starter.report.ErrorReporter
import com.intellij.ide.starter.report.FailureDetailsForTeamcity
import com.intellij.ide.starter.report.FailureDetailsOnCI
import com.intellij.ide.starter.runner.IDERunContext
import com.intellij.tools.ide.util.common.logOutput
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.Path

internal val serverUri = URI("https://intellij-plugins-performance.teamcity.com").normalize()
internal val teamCityIntelliJPerformanceServer = TeamCityCIServer()
private val isDiInitialized: AtomicBoolean = AtomicBoolean(false)

fun initPluginCheckerDI(systemPropertiesFilePath: Path = Path(System.getenv("TEAMCITY_BUILD_PROPERTIES_FILE"))) {
  synchronized(isDiInitialized) {
    if (!isDiInitialized.get()) {
      isDiInitialized.set(true)

      di = DI {
        extend(di)

        bindSingleton<CIServer>(overrides = true) {
          TeamCityCIServer(systemPropertiesFilePath)
        }
        bindSingleton<ErrorReporter>(overrides = true) {
          object: ErrorReporter { override fun reportErrorsAsFailedTests(runContext: IDERunContext) {} }
        }
        bindSingleton<IdeProduct>(overrides = true) { IdeProductImp }
        bindSingleton<IdeDownloader>(overrides = true) { IdeByLinkDownloader }
        bindSingleton<FailureDetailsOnCI>(overrides = true) { FailureDetailsForTeamcity }
        bindSingleton<IdeProductProvider> { IdeProductProvider }
        bindSingleton<URI>(tag = "teamcity.uri", overrides = true) { serverUri }
      }
    }

    logOutput("Plugin checker DI was initialized")
  }
}