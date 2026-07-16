import config.Config
import service.{DocumentFormat, DocumentService, TranslationService}
import service.provider.{ProviderAdapter, OpenAiAdapter, GeminiAdapter, ClaudeAdapter}

import java.io.File
import scala.util.Try

/** Application entry point.
  *
  * Reads all supported files from the source directory, translates their
  * text content using the configured LLM provider, and writes translated
  * copies to the output directory with a `_ru` suffix.
  *
  * Supported formats are defined by `DocumentFormat` type class instances
  * registered in `DocumentService.formatRegistry`.
  */
object Main:

  def main(args: Array[String]): Unit =
    val cfg = Config.load()
    println(s"Source directory: ${cfg.sourceDir}")
    println(s"Output directory: ${cfg.outputDir}")

    val direction = if isAutoDetect(cfg.sourceLang) then
      s"auto → ${cfg.targetLang}"
    else
      s"${cfg.sourceLang} → ${cfg.targetLang}"
    println(s"Direction:   $direction")

    val adapter = selectAdapter(cfg.provider)
    println(s"Provider:    ${adapter.name}")
    println(s"Model:       ${cfg.modelName}")
    println()

    val sourceDir = new File(cfg.sourceDir)
    val outputDir = new File(cfg.outputDir)

    if !sourceDir.isDirectory then
      System.err.println(s"Source directory '${cfg.sourceDir}' does not exist.")
      System.exit(1)

    outputDir.mkdirs()

    val files = discoverFiles(sourceDir)

    if files.isEmpty then
      val known = DocumentService.formatRegistry.keys.toSeq.sorted.mkString(", ")
      println(s"No supported files found in source directory. Known extensions: $known")
      System.exit(0)

    val translator = TranslationService(cfg, adapter)

    files.foreach { (file, format) =>
      processFile(file, format, outputDir, translator, cfg.maxBatchSize)
      println()
    }

    println("All files processed.")

  // --------------------------------------------------------------------------
  // Private helpers
  // --------------------------------------------------------------------------

  /** Returns true if the source language value means "auto-detect". */
  private def isAutoDetect(lang: String): Boolean =
    val v = lang.trim.toLowerCase
    v.isEmpty || v == "auto" || v == "automatically" || v == "autodetect"

  /** Select the provider adapter by name. */
  private def selectAdapter(name: String): ProviderAdapter =
    name.trim.toLowerCase match
      case "openai" => OpenAiAdapter
      case "gemini" => GeminiAdapter
      case "claude" => ClaudeAdapter
      case other =>
        System.err.println(s"Unknown provider '$other'. Valid: openai, gemini, claude")
        System.exit(1)
        OpenAiAdapter // unreachable

  /** Discover supported files and their DocumentFormat in the source directory. */
  private def discoverFiles(dir: File): Seq[(File, DocumentFormat)] =
    val allExts = DocumentService.formatRegistry.keySet
    dir.listFiles()
      .filter(_.isFile)
      .flatMap { f =>
        val ext = extensionOf(f)
        DocumentService.formatForExtension(ext).map(fmt => (f, fmt))
      }
      .toSeq
      .sortBy(_._1.getName)

  /** Find a non-existing output file by incrementing a numeric suffix.
    * Tries `name.ext`, then `name_1.ext`, `name_2.ext`, etc.
    */
  private def resolveOutputFile(dir: File, nameBase: String, extension: String): File =
    var counter = 0
    var file    = File(dir, s"$nameBase.$extension")
    while file.exists() do
      counter += 1
      file = File(dir, s"${nameBase}_$counter.$extension")
    file

  private def processFile(
      source: File,
      format: DocumentFormat,
      outputDir: File,
      translator: TranslationService,
      maxBatchSize: Int
  ): Unit =
    val ext      = extensionOf(source)
    val baseName = source.getName.replaceAll(raw"\\." + ext + "$", "_ru")
    val output   = resolveOutputFile(outputDir, baseName, ext)

    println(s"[PROCESS] ${source.getName} → ${output.getName}")
    println(s"  Extracting text...")

    format.extractTexts(source) match
      case Left(err) =>
        System.err.println(s"  [ERROR] $err")
        return
      case Right(elems) =>
        println(s"  Found ${elems.size} text segments.")
        if elems.isEmpty then
          println(s"  No text to translate — copying as-is.")
          java.nio.file.Files.copy(source.toPath, output.toPath)
          return

        val batches = DocumentService.prepareBatches(elems, maxBatchSize)

        println(s"  Translating (${batches.size} batches)...")
        val translatedBatches = translator.translateBatches(batches)

        val allTranslated = translatedBatches.flatMap(_.elements)

        val failedCount = allTranslated.count(_.translatedText.isEmpty)
        if failedCount > 0 then
          println(s"  Warning: $failedCount text segments could not be translated.")

        println(s"  Writing translated file...")
        format.writeTranslated(source, allTranslated, output) match
          case Left(err) =>
            System.err.println(s"  [ERROR] $err")
          case Right(()) =>
            println(s"  ✓ Saved: ${output.getName}")

  /** Return the file extension without the leading dot. */
  private def extensionOf(file: File): String =
    val name = file.getName
    val dot  = name.lastIndexOf('.')
    if dot >= 0 then name.substring(dot + 1).toLowerCase else ""
