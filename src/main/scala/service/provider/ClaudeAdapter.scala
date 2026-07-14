package service.provider

import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import sttp.client4.quick.*
import sttp.client4.Response
import sttp.model.StatusCode

import scala.util.Try

/** Adapter for Anthropic Claude API.
  *
  * Request:  POST https://api.anthropic.com/v1/messages
  *           x-api-key: <key>
  *           anthropic-version: 2023-06-01
  *           { model, max_tokens: 4096, system, messages: [{role, content}] }
  * Response: { content: [{ type: "text", text: "..." }] }
  */
object ClaudeAdapter extends ProviderAdapter:

  val name = "Claude"

  private val DefaultVersion = "2023-06-01"

  def sendRequest(
      apiKey: String,
      apiUrl: String,
      model: String,
      systemPrompt: String,
      userMessage: String
  ): Either[String, String] =
    val effectiveUrl = if apiUrl.nonEmpty && apiUrl != "https://api.deepseek.com/v1/chat/completions" then
      apiUrl
    else
      "https://api.anthropic.com/v1/messages"

    val body = Json.obj(
      "model"      -> Json.fromString(model),
      "max_tokens" -> Json.fromInt(4096),
      "system"     -> Json.fromString(systemPrompt),
      "messages"   -> Json.arr(
        Json.obj("role" -> Json.fromString("user"),
                 "content" -> Json.fromString(userMessage))
      )
    ).noSpaces

    val response: Either[Throwable, Response[String]] = Try {
      quickRequest
        .post(uri"$effectiveUrl")
        .header("x-api-key", apiKey)
        .header("anthropic-version", DefaultVersion)
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
                   .downField("content")
                   .downN(0)
                   .downField("text")
                   .as[String]
                   .left.map(_ => s"No text content in response: ${resp.body.take(200)}")
    yield text.trim
