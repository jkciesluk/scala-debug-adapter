package ch.epfl.scala.debugadapter.sbtplugin.internal

import ch.epfl.scala.debugadapter._
import sbt._
import sbt.librarymanagement.DependencyResolution
import sbt.librarymanagement.ScalaModuleInfo
import sbt.librarymanagement.UnresolvedWarningConfiguration
import sbt.librarymanagement.UpdateConfiguration

import java.net.URLClassLoader
import scala.util.Properties

private[sbtplugin] object InternalTasks {
  lazy val classPathEntries: Def.Initialize[Task[Seq[ClassPathEntry]]] =
    Def.task {
      val fullClasspath =
        Keys.fullClasspath.value // compile to fill the class directories
      val managedEntries =
        externalClassPathEntries.value ++ internalClassPathEntries.value
      val managedClasspath = managedEntries.map(_.absolutePath).toSet
      val unmanagedEntries =
        fullClasspath
          .map(_.data.getAbsoluteFile.toPath)
          .filter(path => !managedClasspath.contains(path))
          .map(path => ClassPathEntry(path, Seq.empty))
      managedEntries ++ unmanagedEntries
    }

  lazy val javaRuntime: Def.Initialize[Task[Option[JavaRuntime]]] = Def.task {
    for {
      jdkHome <- Keys.javaHome.value
        .map(_.toString)
        .orElse(Option(Properties.jdkHome))
      javaRuntime <- JavaRuntime(jdkHome)
    } yield javaRuntime
  }

  lazy val tryResolveEvaluationClassLoader: Def.Initialize[Task[Option[URLClassLoader]]] = Def.taskIf {
    if (Keys.scalaVersion.value.startsWith("3"))
      resolveEvaluationClassLoader.result.map {
        case Inc(cause) => None
        case Value(value) => Some(value)
      }.value
    else None
  }

  lazy val tryResolveStepFilterClassLoader: Def.Initialize[Task[Option[URLClassLoader]]] = {
    resolveStepFilterClassLoader.result.map {
      case Inc(cause) => None
      case Value(value) => Some(value)
    }
  }

  private lazy val resolveEvaluationClassLoader = Def.task {
    val scalaInstance = Keys.scalaInstance.value
    val scalaVersion = Keys.scalaVersion.value

    val org = BuildInfo.organization
    val artifact = s"${BuildInfo.expressionCompilerName}_$scalaVersion"
    val version = BuildInfo.version

    val updateReport = fetchArtifactsOf(
      org % artifact % version,
      Seq.empty,
      Keys.dependencyResolution.value,
      Keys.scalaModuleInfo.value,
      Keys.updateConfiguration.value,
      (Keys.update / Keys.unresolvedWarningConfiguration).value,
      Keys.streams.value.log
    )
    val evaluatorJars = updateReport
      .select(
        configurationFilter(Runtime.name),
        moduleFilter(org, artifact, version),
        artifactFilter(extension = "jar", classifier = "")
      )
      .map(_.toURI.toURL)
      .toArray
    new URLClassLoader(evaluatorJars, scalaInstance.loader)
  }

  private lazy val resolveStepFilterClassLoader = Def.task {
    val scalaModule = Keys.scalaModuleInfo.value

    val org = BuildInfo.organization
    val artifact = s"${BuildInfo.scala3StepFilterName}_3"
    val version = BuildInfo.version
    val tastyDep = scalaModule.map { info =>
      info.scalaOrganization %% "tasty-core" % info.scalaFullVersion
    }

    val updateReport = fetchArtifactsOf(
      org % artifact % version,
      tastyDep.toSeq,
      Keys.dependencyResolution.value,
      Keys.scalaModuleInfo.value,
      Keys.updateConfiguration.value,
      (Keys.update / Keys.unresolvedWarningConfiguration).value,
      Keys.streams.value.log
    )
    val stepFilterJars = updateReport
      .select(
        configurationFilter(Runtime.name),
        moduleFilter(),
        artifactFilter(extension = "jar", classifier = "")
      )
      .map(_.toURI.toURL)
      .toArray
    new URLClassLoader(stepFilterJars, null)
  }

  private lazy val externalClassPathEntries: Def.Initialize[Task[Seq[ClassPathEntry]]] = Def.task {
    val classifierReport = Keys.updateClassifiers.value
    val report = Keys.update.value
    val configRef = Keys.configuration.value.toConfigRef
    val allSourceJars = classifierReport.configurations
      .filter(report => report.configuration == configRef)
      .flatMap(_.modules)
      .map { module =>
        val sourceJars = module.artifacts.collect {
          case (artifact, jar) if artifact.classifier.contains("sources") =>
            SourceJar(jar.toPath)
        }
        (module.module, sourceJars)
      }
      .toMap

    report.configurations
      .filter(report => report.configuration == configRef)
      .flatMap(_.modules)
      .flatMap { module =>
        val sourceEntries = allSourceJars.getOrElse(module.module, Seq.empty)
        module.artifacts.collectFirst {
          case (artifact, jar)
              if !artifact.classifier
                .exists(cls => cls == "sources" || cls == "javadoc") =>
            ClassPathEntry(jar.toPath, sourceEntries)
        }
      }
  }

  private lazy val internalClassPathEntries: Def.Initialize[Task[Seq[ClassPathEntry]]] = Def.taskDyn {
    val internalDependencies = Keys.bspInternalDependencyConfigurations
    val classPathEntries = for {
      (proj, configs) <- Keys.bspInternalDependencyConfigurations.value
      config <- configs
    } yield internalClassPathEntry(proj, config)
    classPathEntries.join(_.join)
  }

  private def internalClassPathEntry(
      proj: ProjectRef,
      config: ConfigKey
  ): Def.Initialize[Task[ClassPathEntry]] = Def.task {
    val classDirectory = (proj / config / Keys.classDirectory).value.toPath
    val sourceDirectories =
      (proj / config / Keys.sourceDirectories).value.map(_.toPath)
    val sourceFiles = (proj / config / Keys.sources).value.map(_.toPath)
    val standaloneSourceFiles = sourceFiles.filter { file =>
      sourceDirectories.forall(dir => !file.startsWith(dir))
    }
    val sourceEntries =
      sourceDirectories.map(SourceDirectory.apply) ++
        standaloneSourceFiles.map(f => StandaloneSourceFile(f, f.getFileName.toString))
    ClassPathEntry(classDirectory, sourceEntries)
  }

  private def fetchArtifactsOf(
      moduleID: ModuleID,
      dependencies: Seq[ModuleID],
      dependencyRes: DependencyResolution,
      scalaInfo: Option[ScalaModuleInfo],
      updateConfig: UpdateConfiguration,
      warningConfig: UnresolvedWarningConfiguration,
      log: sbt.Logger
  ): UpdateReport = {
    val sha1 = Hash.toHex(Hash(moduleID.name))
    val dummyID =
      ModuleID("ch.epfl.scala.temp", "temp-module" + sha1, moduleID.revision)
        .withConfigurations(moduleID.configurations)
    val descriptor =
      dependencyRes.moduleDescriptor(
        dummyID,
        moduleID +: dependencies.toVector,
        scalaInfo
      )

    dependencyRes.update(descriptor, updateConfig, warningConfig, log) match {
      case Right(report) =>
        report
      case Left(warning) =>
        throw new MessageOnlyException(
          s"Couldn't retrieve `$moduleID` : ${warning.resolveException.getMessage}."
        )
    }
  }
}