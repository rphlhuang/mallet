ThisBuild / scalaVersion := "2.13.18"
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "com.github.rphlhuang"
Test / testOptions       += Tests.Argument(TestFrameworks.ScalaTest, "-oF")

val chiselVersion    = "7.13.0"
val scalatestVersion = "3.2.19"

lazy val root = (project in file("."))
  .settings(
    name := "mallet",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel"     % chiselVersion,
      "org.scalatest"     %% "scalatest"  % scalatestVersion % "test",
    ),
    Compile / unmanagedSourceDirectories ++= Seq(
      baseDirectory.value / "third_party" / "chisel-axi-utils" / "src" / "main" / "scala",
      baseDirectory.value / "third_party" / "berkeley-hardfloat" / "hardfloat" / "src" / "main" / "scala",
    ),
    Compile / unmanagedSources ++= Seq(
      baseDirectory.value / "third_party" / "rial" / "src" / "main" / "scala" / "ecc" / "package.scala",
      baseDirectory.value / "third_party" / "rial" / "src" / "main" / "scala" / "ecc" / "fletcher.scala",
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations",
      // next two lines allow Mallet role errors
      "-Wnonunit-statement",
      "-Wconf:msg=unused value of type:s,msg=unused value of type .*(ResultNeedsValidWhen|CommitNeedsRequiring|CommitNeedsAcceptedOn).*:e",
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
  )
