package service.provider

import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import sttp.client4.quick.*
import sttp.client4.Response
import sttp.model.StatusCode

import scala.util.Try

/** Adapter for Google Gemini API.
  *
  * Request:  POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={apiKey}
  *           { contents: [{ parts: [{ text }] }] }
  * Response: { candidates: [{ content: { parts: [{ text }] } }] }
  *
  * Note: the `apiUrl` config is ignored — Gemini has a fixed endpoint structure.
  */
object GeminiAdapter extends ProviderAdapter:

  val name = "Gemini"

  /** Gemini API version header */
  private val ApiBase = "https://generativelanguage.googleapis.com/v1beta"

  def sendRequest(
      apiKey: String,
      apiUrl: String,
      model: String,
      systemPrompt: String,
      userMessage: String
  ): Either[String, String] =
    val fullUrl = s"$ApiBase/models/$model:generateContent?key=$apiKey"

    val body = Json.obj(
      "system_instruction" -> Json.obj(
        "parts" -> Json.arr(
          Json.obj("text" -> Json.fromString(systemPrompt))
        )
      ),
      "contents" -> Json.arr(
        Json.obj(
          "parts" -> Json.arr(
            Json.obj("text" -> Json.fromString(userMessage))
          )
        )
      )
    ).noSpaces

    val response: Either[Throwable, Response[String]] = Try {
      quickRequest
        .post(uri"$fullUrl")
        .header("Content-Type", "application/json")
        .body(body)
        .send()
    }.toEither

    for
      resp    <- response.left.map(e => s"HTTP error: ${e.getMessage}")
      _       <- Either.cond(resp.code == StatusCode.Ok, (),
                   s"API ${resp.code}: ${resp.body.take(500)}")
      json    <- parse(resp.body).left.map(e => s"Invalid JSON: $e")
      text    <- json.hcursor
                   .downField("candidates")
                   .downN(0)
                   .downField("content")
                   .downField("parts")
                   .downN(0)
                   .downField("text")
                   .as[String]
                   .left.map(_ => s"No content in response: ${resp.body.take(200)}")
    yield text.trim
