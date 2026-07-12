package service

import config.Config
import sttp.client4.quick.*
import sttp.client4.Response
import sttp.model.StatusCode
import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*

import scala.util.Try
import scala.util.matching.Regex

/** Translates text via the DeepSeek API with configurable language pair. */
class TranslationService(config: Config):

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

  /** Translate a single batch of text elements via the DeepSeek API. */
  def translateBatch(batch: service.Batch): Either[String, service.Batch] =
    val numberedText = batch.elements.zipWithIndex.map { (elem, i) =>
      s"${i + 1}. ${elem.originalText}"
    }.mkString("\n")

    val body = Json.obj(
      "model"       -> Json.fromString(config.modelName),
      "messages"    -> Json.arr(
        Json.obj("role" -> Json.fromString("system"),
                 "content" -> Json.fromString(systemPrompt)),
        Json.obj("role" -> Json.fromString("user"),
                 "content" -> Json.fromString(numberedText))
      ),
      "temperature" -> Json.fromDoubleOrNull(0.0)
    ).noSpaces

    val response: Either[Throwable, Response[String]] = Try {
      quickRequest
        .post(uri"${config.apiUrl}")
        .header("Authorization", s"Bearer ${config.apiKey}")
        .header("Content-Type", "application/json")
        .body(body)
        .send()
    }.toEither

    for
      resp       <- response.left.map(e => s"HTTP error: ${e.getMessage}")
      _          <- Either.cond(resp.code == StatusCode.Ok, (),
                      s"API ${resp.code}: ${resp.body.take(500)}")
      content    <- extractContent(resp.body)
      parsed     <- parseTranslation(content, batch.elements.size)
      updated     = batch.copy(elements = batch.elements.zip(parsed).map {
                      case (e, t) => e.copy(translatedText = t)
                    })
    yield updated

  /** Translate multiple batches sequentially.
    * Failed batches fall back to per-text translation.
    */
  def translateBatches(batches: Seq[service.Batch]): Seq[service.Batch] =
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

  private def translateIndividually(batch: service.Batch): service.Batch =
    val translated = batch.elements.map { elem =>
      translateSingle(elem.originalText) match
        case Right(t) => elem.copy(translatedText = t)
        case Left(e)  =>
          Console.err.println(s"  [WARN] Could not translate \"${truncate(elem.originalText, 60)}\": $e")
          elem.copy(translatedText = "")
    }
    batch.copy(elements = translated)

  private def translateSingle(text: String): Either[String, String] =
    val body = Json.obj(
      "model"       -> Json.fromString(config.modelName),
      "messages"    -> Json.arr(
        Json.obj("role"    -> Json.fromString("system"),
                 "content" -> Json.fromString(singlePrompt)),
        Json.obj("role"    -> Json.fromString("user"),
                 "content" -> Json.fromString(text))
      ),
      "temperature" -> Json.fromDoubleOrNull(0.0)
    ).noSpaces

    val response = Try {
      quickRequest
        .post(uri"${config.apiUrl}")
        .header("Authorization", s"Bearer ${config.apiKey}")
        .header("Content-Type", "application/json")
        .body(body)
        .send()
    }.toEither

    for
      resp    <- response.left.map(e => s"HTTP error: ${e.getMessage}")
      _       <- Either.cond(resp.code == StatusCode.Ok, (),
                   s"API ${resp.code}: ${resp.body.take(200)}")
      content <- extractContent(resp.body)
    yield content

  /** Extract message content from DeepSeek response JSON. */
  private def extractContent(body: String): Either[String, String] =
    for
      json    <- parse(body).left.map(e => s"Invalid JSON: $e")
      content <- json.hcursor
                   .downField("choices")
                   .downN(0)
                   .downField("message")
                   .downField("content")
                   .as[String]
                   .left.map(_ => s"No content in response: ${body.take(200)}")
    yield content.trim

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
      // Small batches — the model may have merged lines. Use raw lines.
      val raw = content.split("\n").toList.map(_.trim).filter(_.nonEmpty)
      Right(padOrTruncate(raw, expected))
    else
      Left(s"Expected $expected translations, got ${translations.size}")

  private def padOrTruncate(list: List[String], target: Int): List[String] =
    if list.size >= target then list.take(target)
    else list ++ List.fill(target - list.size)("")

  private def truncate(s: String, n: Int): String =
    if s.length <= n then s else s.take(n) + "..."

  /** Returns true if the source language value means "auto-detect". */
  private def isAutoDetect(lang: String): Boolean =
    val v = lang.trim.toLowerCase
    v.isEmpty || v == "auto" || v == "automatically" || v == "autodetect"
