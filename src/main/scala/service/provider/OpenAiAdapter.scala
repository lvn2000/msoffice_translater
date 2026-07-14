package service.provider

import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import sttp.client4.quick.*
import sttp.client4.Response
import sttp.model.StatusCode

import scala.util.Try

/** Adapter for OpenAI-compatible APIs: DeepSeek, OpenAI, Groq, Together, etc.
  *
  * Request:  POST /v1/chat/completions
  *           Authorization: Bearer <key>
  *           { model, messages: [{role, content}], temperature }
  * Response: { choices: [{ message: { content } }] }
  */
object OpenAiAdapter extends ProviderAdapter:

  val name = "OpenAI-compatible"

  def sendRequest(
      apiKey: String,
      apiUrl: String,
      model: String,
      systemPrompt: String,
      userMessage: String
  ): Either[String, String] =
    val body = Json.obj(
      "model"       -> Json.fromString(model),
      "messages"    -> Json.arr(
        Json.obj("role" -> Json.fromString("system"),
                 "content" -> Json.fromString(systemPrompt)),
        Json.obj("role" -> Json.fromString("user"),
                 "content" -> Json.fromString(userMessage))
      ),
      "temperature" -> Json.fromDoubleOrNull(0.0)
    ).noSpaces

    val response: Either[Throwable, Response[String]] = Try {
      quickRequest
        .post(uri"$apiUrl")
        .header("Authorization", s"Bearer $apiKey")
        .header("Content-Type", "application/json")
        .body(body)
        .send()
    }.toEither

    for
      resp    <- response.left.map(e => s"HTTP error: ${e.getMessage}")
      _       <- Either.cond(resp.code == StatusCode.Ok, (),
                   s"API ${resp.code}: ${resp.body.take(500)}")
      json    <- parse(resp.body).left.map(e => s"Invalid JSON: $e")
      content <- json.hcursor
                   .downField("choices")
                   .downN(0)
                   .downField("message")
                   .downField("content")
                   .as[String]
                   .left.map(_ => s"No content in response: ${resp.body.take(200)}")
    yield content.trim
