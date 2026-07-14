import config.Config
import service.{PptxService, TranslationService}
import service.provider.{ProviderAdapter, OpenAiAdapter, GeminiAdapter, ClaudeAdapter}

import java.io.File
import scala.util.{Try, Using}

/** Application entry point.
  *
  * Reads all `.pptx` files from the source directory, translates their text
  * content using the configured LLM provider, and writes translated copies
  * to the output directory with a `_ru` suffix.
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

    val pptxFiles = sourceDir.listFiles().filter { f =>
      f.isFile && f.getName.toLowerCase.endsWith(".pptx")
    }.toSeq.sortBy(_.getName)

    if pptxFiles.isEmpty then
      println("No .pptx files found in source directory.")
      System.exit(0)

    val translator = TranslationService(cfg, adapter)

    pptxFiles.foreach { file =>
      processFile(file, outputDir, translator, cfg.maxBatchSize)
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

  /** Find a non-existing output file by incrementing a numeric suffix.
    * Tries `name.pptx`, then `name_1.pptx`, `name_2.pptx`, etc.
    */
  private def resolveOutputFile(dir: File, nameBase: String): File =
    var counter = 0
    var file    = File(dir, s"$nameBase.pptx")
    while file.exists() do
      counter += 1
      file = File(dir, s"${nameBase}_$counter.pptx")
    file

  private def processFile(
      source: File,
      outputDir: File,
      translator: TranslationService,
      maxBatchSize: Int
  ): Unit =
    val baseName = source.getName.replaceAll("""\.pptx$""", "_ru")
    val output   = resolveOutputFile(outputDir, baseName)

    println(s"[PROCESS] ${source.getName} → ${output.getName}")
    println(s"  Extracting text...")

    val elements = Try(PptxService.extractTexts(source)).toEither match
      case Right(elems) =>
        println(s"  Found ${elems.size} text segments.")
        if elems.isEmpty then
          println(s"  No text to translate — copying as-is.")
          java.nio.file.Files.copy(source.toPath, output.toPath)
          return
        elems
      case Left(e) =>
        System.err.println(s"  [ERROR] Failed to read pptx: ${e.getMessage}")
        return

    val batches = PptxService.prepareBatches(elements, maxBatchSize)

    println(s"  Translating (${batches.size} batches)...")
    val translatedBatches = translator.translateBatches(batches)

    val allTranslated = translatedBatches.flatMap(_.elements)

    val failedCount = allTranslated.count(_.translatedText.isEmpty)
    if failedCount > 0 then
      println(s"  Warning: $failedCount text segments could not be translated.")

    println(s"  Writing translated file...")
    PptxService.writeTranslated(source, allTranslated, output)

    println(s"  ✓ Saved: ${output.getName}")
