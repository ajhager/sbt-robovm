package sbtrobovm

import java.io.{IOException, StringReader}
import java.util

import org.apache.commons.io.FileUtils
import org.jboss.shrinkwrap.resolver.api.SBTRoboVMResolver
import org.robovm.compiler.AppCompiler
import org.robovm.compiler.config.Config.TargetType
import org.robovm.compiler.config.{Arch, Config, OS}
import org.robovm.compiler.log.Logger
import org.robovm.compiler.target.ios._
import sbt.Defaults._
import sbt.Keys._
import sbt._
import sbtrobovm.RobovmPlugin._

object RobovmProjects {

  def configTask(arch: => Arch, os: => OS, targetType: => TargetType, skipInstall: Boolean) = Def.task[Config.Builder] {
    val st = streams.value

    val builder = new Config.Builder()

    builder.logger(new Logger() {
      def debug(s: String, o: java.lang.Object*) = {
        if (robovmVerbose.value) {
          st.log.info(s.format(o: _*))
        } else {
          st.log.debug(s.format(o: _*))
        }
      }

      def info(s: String, o: java.lang.Object*) = st.log.info(s.format(o: _*))

      def warn(s: String, o: java.lang.Object*) = st.log.warn(s.format(o: _*))

      def error(s: String, o: java.lang.Object*) = st.log.error(s.format(o: _*))
    })

    builder.home(robovmHome.value)

    robovmProperties.value match {
      case Left(propertyFile) =>
        st.log.debug("Including properties file: " + propertyFile.getAbsolutePath)
        builder.addProperties(propertyFile)
      case Right(propertyMap) =>
        st.log.debug("Including embedded properties: " + propertyMap)
        for ((key, value) <- propertyMap) {
          builder.addProperty(key, value)
        }
      case _ =>
    }

    robovmConfiguration.value match {
      case Left(file) =>
        st.log.debug("Loading config file: " + file.getAbsolutePath)
        builder.read(file)
      case Right(xml) =>
        st.log.debug("Loading embedded xml configuration: "+xml)
        builder.read(new StringReader(xml.toString()),baseDirectory.value)
    }

    builder.clearClasspathEntries()
    robovmInputJars.value foreach { jarFile =>
      st.log.debug("Adding input jar: " + jarFile)
      builder.addClasspathEntry(jarFile)
    }

    provisioningProfile.value.foreach(name => {
      val list = ProvisioningProfile.list()
      try{
        val profile = ProvisioningProfile.find(list,name)
        builder.iosProvisioningProfile(profile)
        st.log.debug("Using explicit provisioning profile: "+profile.toString)
      }catch {
        case _:IllegalArgumentException => // Not found
          st.log.error("No provisioning profile identifiable with \""+name+"\" found. "+{
            if(list.size() == 0){
              "No profiles installed."
            }else{
              "Those were found: ("+list.size()+")"
            }
          })
          for(i <- 0 until list.size()){
            val profile = list.get(i)
            st.log.error("\tName: "+profile.getName)
            st.log.error("\t\tUUID: "+profile.getUuid)
            st.log.error("\t\tAppID Prefix: "+profile.getAppIdPrefix)
            st.log.error("\t\tAppID Name: "+profile.getAppIdName)
            st.log.error("\t\tType: "+profile.getType)
          }
      }
    })

    val skipSign = skipSigning.value.exists(skip => skip) //Check if value is Some(true)

    if(skipSign){
      st.log.debug("Skipping signing.")
      builder.iosSkipSigning(true)
    }else{
      signingIdentity.value.foreach(name => {
        val list = SigningIdentity.list()
        try{
          val signIdentity = SigningIdentity.find(list,name)
          builder.iosSignIdentity(signIdentity)
          st.log.debug("Using explicit signing identity: "+signIdentity.toString)
        }catch {
          case _:IllegalArgumentException => // Not found
            st.log.error("No signing identity identifiable with \""+name+"\" found. "+{
              if(list.size() == 0){
                "No identities installed."
              }else{
                "Those were found: ("+list.size()+")"
              }
            })
            for(i <- 0 until list.size()){
              val identity = list.get(i)
              st.log.error("\tName: "+identity.getName)
              st.log.error("\t\tFingerprint: "+identity.getFingerprint)
            }
        }
      })
    }

    //To make sure that options were not overrided, that would not work.
    builder.skipInstall(skipInstall)
    builder.targetType(targetType)
    builder.os(os)
    builder.arch(arch)

    val t = target.value
    builder.installDir(t / "robovm")
    val tmpDir = t / "robovm.tmp"
    if(tmpDir.isDirectory){
      try {
        FileUtils.deleteDirectory(tmpDir)
      } catch {
        case io:IOException =>
          st.log.error("Failed to clean temporary output directory "+tmpDir)
          st.log.trace(io)
      }
    }
    tmpDir.mkdirs()
    builder.tmpDir(tmpDir)

    val enableRobovmDebug = robovmDebug.value
    if(enableRobovmDebug){
      st.log.info("RoboVM Debug is enabled")
      builder.debug(true)
      val debugPort = robovmDebugPort.value
      if(debugPort != -1){
        builder.addPluginArgument("debug:jdwpport=" + debugPort)
      }
    }

    builder
  }

  def configAndCompileTask(arch: => Arch, os: => OS, targetType: => TargetType, skipInstall: Boolean) = Def.task[Config] {
    val st = streams.value
    val config = configTask(arch,os,targetType,skipInstall).value.build()

    st.log.info("Compiling RoboVM app, this could take a while")
    val compiler = new AppCompiler(config)
    compiler.compile()

    st.log.info("RoboVM app compiled")
    config
  }

  lazy val baseSettings = Seq(
    robovmProperties := Right(
      Map(
        "app.name" -> name.value,
        "app.executable" -> name.value.replace(" ",""),
        "app.mainclass" -> {
          val mainClassName = (mainClass in(Compile, run)).value.orElse((selectMainClass in Compile).value).getOrElse("")
          streams.value.log.debug("Selected main class \""+mainClassName+"\"")
          mainClassName
        })
    ),
    robovmConfiguration := Right(
      <config>
        <executableName>${{app.executable}}</executableName>
        <mainClass>${{app.mainclass}}</mainClass>
        <resources>
          <resource>
            <directory>resources</directory>
          </resource>
        </resources>
      </config>
    ),
    skipSigning := None,
    robovmDebug := false,
    robovmDebugPort := -1,
    robovmHome := new Config.Home(new SBTRoboVMResolver().resolveAndUnpackRoboVMDistArtifact(RoboVMVersion)),
    robovmInputJars := (fullClasspath in Compile).value map (_.data),
    robovmVerbose := false,
    robovmLicense := {
      com.robovm.lm.LicenseManager.forkUI()
    },
    provisioningProfile := None, //TODO Figure out how to move these to iOSProject, so they won't be set elsewhere
    signingIdentity := None,
    ivyConfigurations += ManagedNatives
  )

  trait RoboVMProject {

    val projectSettings:Seq[Def.Setting[_]]

    def apply(
               id: String,
               base: File,
               aggregate: => Seq[ProjectReference] = Nil,
               dependencies: => Seq[ClasspathDep[ProjectReference]] = Nil,
               delegates: => Seq[ProjectReference] = Nil,
               settings: => Seq[Def.Setting[_]] = Seq.empty,
               configurations: Seq[Configuration] = Configurations.default
               ) = Project(
      id,
      base,
      aggregate,
      dependencies,
      delegates,
      coreDefaultSettings ++ baseSettings ++ projectSettings ++ settings,
      configurations
    )
  }

  object iOSProject extends RoboVMProject {

    override lazy val projectSettings = Seq(
      robovmSimulatorDevice := None,
      device := {
        val config = configAndCompileTask(Arch.thumbv7, OS.ios, TargetType.ios, skipInstall = true).value

        val launchParameters = config.getTarget.createLaunchParameters()
        config.getTarget.launch(launchParameters).waitFor()
      },
      iphoneSim := {
        val config = configAndCompileTask(Arch.x86, OS.ios, TargetType.ios, skipInstall = true).value

        val launchParameters = config.getTarget.createLaunchParameters().asInstanceOf[IOSSimulatorLaunchParameters]
        launchParameters.setDeviceType(DeviceType.getBestDeviceType(config.getHome, DeviceType.DeviceFamily.iPhone))
        config.getTarget.launch(launchParameters).waitFor()
      },
      ipadSim := {
        val config = configAndCompileTask(Arch.x86, OS.ios, TargetType.ios, skipInstall = true).value

        val launchParameters = config.getTarget.createLaunchParameters().asInstanceOf[IOSSimulatorLaunchParameters]
        launchParameters.setDeviceType(DeviceType.getBestDeviceType(config.getHome, DeviceType.DeviceFamily.iPad))
        config.getTarget.launch(launchParameters).waitFor()
      },
      ipa := {
        val config = configTask(arch = null, OS.ios, TargetType.ios, skipInstall = false).value.build()
        val compiler = new AppCompiler(config)
        val architectures = new util.ArrayList[Arch]()
        architectures.add(Arch.thumbv7)
        architectures.add(Arch.arm64)
        compiler.createIpa(architectures)
      },
      simulator := {
        val simulatorDeviceName: String = robovmSimulatorDevice.value.getOrElse(sys.error("Define device kind name first. See robovmSimulatorDevice setting and simulatorDevices task."))
        val config = configAndCompileTask(Arch.x86, OS.ios, TargetType.ios, skipInstall = true).value

        val launchParameters = config.getTarget.createLaunchParameters().asInstanceOf[IOSSimulatorLaunchParameters]
        val simDevice = DeviceType.getDeviceType(config.getHome, simulatorDeviceName)
        if (simDevice == null) sys.error( s"""iOS simulator device "$simulatorDeviceName" not found.""")
        launchParameters.setDeviceType(simDevice)
        config.getTarget.launch(launchParameters).waitFor()
      },
      simulatorDevices := {
        val devices = DeviceType.getSimpleDeviceTypeIds(robovmHome.value)
        for (simpleDevice <- scala.collection.convert.wrapAsScala.iterableAsScalaIterable(devices)) {
          println(simpleDevice)
        }
        println(devices.size() + " devices found.")
      }
    )

  }

  object NativeProject extends RoboVMProject {

    override lazy val projectSettings = Seq(
      native := {
        val config = configAndCompileTask(Arch.getDefaultArch, OS.getDefaultOS, TargetType.console, skipInstall = true).value

        val launchParameters = config.getTarget.createLaunchParameters()
        config.getTarget.launch(launchParameters).waitFor()
      }
    )
    
  }
}
