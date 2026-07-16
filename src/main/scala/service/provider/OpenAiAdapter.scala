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


  // Ollama's HTTP server sometimes closes the TCP connection between requests.
  // Without the retry, every other batch would fail and fall back to slow individual translation.
  // The retry waits 1 second and tries again — which usually works because Ollama
  // accepts the new connection on the second attempt.
  // If you're using cloud APIs (DeepSeek, OpenAI, etc.) this never triggers
  // because their servers handle keep-alive properly.
  // It's specifically for local models where the server is less robust.
  private val MaxRetries   = 2
  private val RetryDelayMs = 1000L

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

    def attempt(remaining: Int): Either[String, String] =
      val response: Either[Throwable, Response[String]] = Try {
        quickRequest
          .post(uri"$apiUrl")
          .header("Authorization", s"Bearer $apiKey")
          .header("Content-Type", "application/json")
          .body(body)
          .send()
      }.toEither

      val result = for
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

      result match
        case Left(err) if remaining > 0 && err.startsWith("HTTP error:") =>
          Thread.sleep(RetryDelayMs)
          attempt(remaining - 1)
        case _ => result

    attempt(MaxRetries)
