//lazy val scalatraVersion = "2.6.5"    // can see the latest version at https://github.com/scalatra/scalatra/releases
lazy val akkaHttpVersion = "10.1.10"
lazy val akkaVersion    = "2.5.26"

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization    := "com.horizon",
      scalaVersion    := "2.12.8"
    )),
    name := "Exchange API",
    version := "0.1.0",
    resolvers += Classpaths.typesafeReleases,
    // Sbt uses Ivy for dependency resolution, so it supports its version syntax: http://ant.apache.org/ivy/history/latest-milestone/ivyfile/dependency.html#revision
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http"            % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-xml"        % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-stream"          % akkaVersion,

      "com.typesafe.slick" %% "slick" % "latest.release",
      "com.typesafe.slick" %% "slick-hikaricp" % "latest.release",
      "com.github.tminglei" %% "slick-pg" % "latest.release",
      "com.github.tminglei" %% "slick-pg_json4s" % "latest.release",
      "org.postgresql" % "postgresql" % "latest.release",
      "com.zaxxer" % "HikariCP" % "latest.release",
      "org.slf4j" % "slf4j-api" % "1.7.26",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.mchange" % "c3p0" % "latest.release",
      //"javax.servlet" % "javax.servlet-api" % "latest.release" % "provided",
      "org.json4s" %% "json4s-native" % "latest.release",
      "org.json4s" %% "json4s-jackson" % "latest.release",
      "org.scalaj" %% "scalaj-http" % "latest.release",
      "com.typesafe" % "config" % "latest.release",
      "org.mindrot" % "jbcrypt" % "latest.release",
      "com.pauldijou" %% "jwt-core" % "2.1.0",
      "com.github.cb372" %% "scalacache-guava" % "latest.release",
      "com.osinka.i18n" %% "scala-i18n" % "latest.release",

      "com.typesafe.akka" %% "akka-http-testkit"    % akkaHttpVersion % Test,
      "com.typesafe.akka" %% "akka-testkit"         % akkaVersion     % Test,
      "com.typesafe.akka" %% "akka-stream-testkit"  % akkaVersion     % Test,

      "org.scalatest" %% "scalatest" % "latest.release" % "test",
      "org.scalacheck" %% "scalacheck" % "latest.release" % "test",
      "junit" % "junit" % "latest.release" % "test"
    ),
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
    javaOptions ++= Seq("-Djava.security.auth.login.config=src/main/resources/jaas.config", "-Djava.security.policy=src/main/resources/auth.policy")
  )
