name         := "doc-translator"
version      := "1.0.0"
scalaVersion := "3.6.4"

libraryDependencies ++= Seq(
  // Apache POI for processing pptx and docx files
  "org.apache.poi" % "poi-ooxml" % "5.3.0",

  // Apache PDFBox for processing pdf files
  "org.apache.pdfbox" % "pdfbox" % "3.0.2",

  // HTTP client for DeepSeek API calls
  "com.softwaremill.sttp.client4" %% "core" % "4.0.0-M19",

  // JSON parsing for API requests/responses
  "io.circe" %% "circe-core"    % "0.14.10",
  "io.circe" %% "circe-generic" % "0.14.10",
  "io.circe" %% "circe-parser"  % "0.14.10",

  // Logging
  "ch.qos.logback" % "logback-classic" % "1.5.13",
  "org.typelevel" %% "log4cats-slf4j"  % "2.7.0",

  // HOCON configuration (application.conf)
  "com.typesafe" % "config" % "1.4.3",

  // Testing
  "org.scalameta" %% "munit" % "1.1.0" % Test
)


