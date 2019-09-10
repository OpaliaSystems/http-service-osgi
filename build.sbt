
val mScalaVersion = "2.12.8"
val mInterfacesVersion = "1.0.0"
val mCommonsVersion = "1.0.0"
val mBootloaderVersion = "1.0.0"
val mCrossVersion = """^(\d+\.\d+)""".r.findFirstIn(mScalaVersion).get

val exclusionRules = Seq(
  ExclusionRule("org.scala-lang", "scala-library"),
  ExclusionRule("org.scala-lang", "scala-reflect"),
  ExclusionRule("org.scala-lang", "scala-compiler"),
  ExclusionRule("com.typesafe", "config"),
  ExclusionRule("systems.opalia", s"interfaces_$mCrossVersion"),
  ExclusionRule("org.osgi", "org.osgi.core"),
  ExclusionRule("org.osgi", "org.osgi.service.component"),
  ExclusionRule("org.osgi", "org.osgi.compendium")
)

def commonSettings: Seq[Setting[_]] = {

  Seq(
    organizationName := "Opalia Systems",
    organizationHomepage := Some(url("https://opalia.systems")),
    organization := "systems.opalia",
    homepage := Some(url("https://github.com/OpaliaSystems/opalia-service-http")),
    version := "1.0.0",
    scalaVersion := mScalaVersion
  )
}

lazy val `testing` =
  (project in file("testing"))
    .dependsOn(`http-api`)
    .settings(

      name := "testing",

      commonSettings,

      parallelExecution in ThisBuild := false,

      libraryDependencies ++= Seq(
        "systems.opalia" %% "interfaces" % mInterfacesVersion,
        "systems.opalia" %% "commons" % mCommonsVersion,
        "systems.opalia" %% "bootloader" % mBootloaderVersion,
        "org.scalatest" %% "scalatest" % "3.0.7" % "test"
      )
    )

lazy val `http-api` =
  (project in file("http-api"))
    .settings(

      name := "http-api",

      description := "The project provides an API for working with HTTP server/client.",

      commonSettings,

      bundleSettings,

      OsgiKeys.importPackage ++= Seq(
        "scala.*",
        "com.typesafe.config.*",
        "systems.opalia.interfaces.*"
      ),

      OsgiKeys.exportPackage ++= Seq(
        "systems.opalia.service.http.api.*"
      ),

      libraryDependencies ++= Seq(
        "systems.opalia" %% "interfaces" % mInterfacesVersion % "provided"
      )
    )

lazy val `http-impl-akka` =
  (project in file("http-impl-akka"))
    .dependsOn(`http-api`)
    .settings(

      name := "http-impl-akka",

      description := "The project provides a HTTP server/client implementation based on Akka HTTP.",

      commonSettings,

      bundleSettings,

      OsgiKeys.privatePackage ++= Seq(
        "systems.opalia.service.http.impl.*"
      ),

      OsgiKeys.importPackage ++= Seq(
        "scala.*",
        "com.typesafe.config.*",
        "systems.opalia.interfaces.*",
        "systems.opalia.service.http.api.*"
      ),

      libraryDependencies ++= Seq(
        "org.osgi" % "org.osgi.core" % "6.0.0" % "provided",
        "org.osgi" % "org.osgi.service.component.annotations" % "1.4.0",
        "org.osgi" % "org.osgi.service.log" % "1.4.0",
        "systems.opalia" %% "interfaces" % mInterfacesVersion % "provided",
        "systems.opalia" %% "commons" % mCommonsVersion excludeAll (exclusionRules: _*),
        "com.typesafe.akka" %% "akka-http" % "10.1.8" excludeAll (exclusionRules: _*),
        "com.typesafe.akka" %% "akka-stream" % "2.5.19" excludeAll (exclusionRules: _*),
        "com.typesafe.akka" %% "akka-osgi" % "2.5.19" excludeAll (exclusionRules: _*)
      )
    )
