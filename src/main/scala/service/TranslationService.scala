package service

import config.Config
import model.Batch
import service.provider.ProviderAdapter

import scala.util.matching.Regex

/** Translates text via a pluggable LLM provider adapter. */
class TranslationService(config: Config, adapter: ProviderAdapter):

  private val NumberedLine: Regex = """^(\d+)[.)]\s*(.*)""".r

  /** Build the system prompt from configured language direction. */
  private def systemPrompt: String =
    val direction = if isAutoDetect(config.sourceLang) then
      s"to ${config.targetLang.trim}. Detect the source language automatically"
    else
      s"from ${config.sourceLang.trim} to ${config.targetLang.trim}"

    s"""You are a translator. Translate the following text $direction.

      |You will receive a numbered list of texts, one per line:
      |  N. text
      |
      |Return ONLY the translations in the same numbered format:
      |  N. translated text
      |
      |Rules:
      |- Keep the line numbers unchanged.
      |- Preserve all special characters, variables (e.g. {0}, %1), numbers, punctuation, and whitespace.
      |- The number of lines in the response must exactly match the input.
      |- Do not add any explanations, introductions, or conclusions.""".stripMargin

  private def singlePrompt: String =
    val direction = if isAutoDetect(config.sourceLang) then
      s"to ${config.targetLang.trim}. Detect the source language automatically"
    else
      s"from ${config.sourceLang.trim} to ${config.targetLang.trim}"
    s"Translate the following text $direction. Return only the translation, no explanations."

  /** Translate a single batch of text elements. */
  def translateBatch(batch: Batch): Either[String, Batch] =
    val numberedText = batch.elements.zipWithIndex.map { (elem, i) =>
      s"${i + 1}. ${elem.originalText}"
    }.mkString("\n")

    for
      content <- adapter.sendRequest(
                   config.apiKey, config.apiUrl, config.modelName,
                   systemPrompt, numberedText
                 )
      parsed  <- parseTranslation(content, batch.elements.size)
      updated  = batch.copy(elements = batch.elements.zip(parsed).map {
                   case (e, t) => e.copy(translatedText = t)
                 })
    yield updated

  /** Translate multiple batches sequentially.
    * Failed batches fall back to per-text translation.
    */
  def translateBatches(batches: Seq[Batch]): Seq[Batch] =
    batches.zipWithIndex.map { case (batch, idx) =>
      print(s"  Batch ${idx + 1}/${batches.size} (${batch.elements.size} texts)... ")
      translateBatch(batch) match
        case Right(updated) =>
          println("ok")
          updated
        case Left(err) =>
          println(s"FAILED ($err)")
          Console.err.println(s"  [ERROR] Batch ${idx + 1}: $err — retrying individually")
          translateIndividually(batch)
    }

  // --------------------------------------------------------------------------
  // Private helpers
  // --------------------------------------------------------------------------

  private def translateIndividually(batch: Batch): Batch =
    val translated = batch.elements.map { elem =>
      translateSingle(elem.originalText) match
        case Right(t) => elem.copy(translatedText = t)
        case Left(e)  =>
          Console.err.println(s"  [WARN] Could not translate \"${truncate(elem.originalText, 60)}\": $e")
          elem.copy(translatedText = "")
    }
    batch.copy(elements = translated)

  private def translateSingle(text: String): Either[String, String] =
    adapter.sendRequest(
      config.apiKey, config.apiUrl, config.modelName,
      singlePrompt, text
    )

  /** Parse numbered output ("1. text\\n2. text…") back into a list. */
  private def parseTranslation(
      content: String,
      expected: Int
  ): Either[String, List[String]] =
    val translations = content
      .split("\n")
      .to(LazyList)
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap {
        case NumberedLine(_, text) => Some(text.trim)
        case _                     => None
      }
      .toList

    if translations.size == expected then
      Right(translations)
    else if translations.size < expected && expected <= 3 then
      val raw = content.split("\n").toList.map(_.trim).filter(_.nonEmpty)
      Right(padOrTruncate(raw, expected))
    else
      Left(s"Expected $expected translations, got ${translations.size}")

  private def padOrTruncate(list: List[String], target: Int): List[String] =
    if list.size >= target then list.take(target)
    else list ++ List.fill(target - list.size)("")

  private def truncate(s: String, n: Int): String =
    if s.length <= n then s else s.take(n) + "..."

  private def isAutoDetect(lang: String): Boolean =
    val v = lang.trim.toLowerCase
    v.isEmpty || v == "auto" || v == "automatically" || v == "autodetect"
