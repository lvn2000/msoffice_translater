package service.provider

import io.circe.Json
import io.circe.parser.parse

/** Pluggable adapter for different LLM providers.
  *
  * Each implementation handles the provider-specific HTTP request/response
  * format, while `TranslationService` handles batching and response parsing
  * on top.
  */
trait ProviderAdapter:

  /** Human-readable provider name (e.g. "OpenAI", "Gemini", "Claude"). */
  def name: String

  /** Send a chat completion request and return the response text.
    *
    * @param apiKey   the secret API key
    * @param apiUrl   the API endpoint URL (provider may override if unused)
    * @param model    the model identifier
    * @param systemPrompt  system-level instruction
    * @param userMessage   the user message text
    * @param extraBodyJson  extra JSON merged into the request body; used for
    *                   provider-specific fields (e.g. DeepSeek's `thinking`)
    * @return the raw response text, or an error message
    */
  def sendRequest(
      apiKey: String,
      apiUrl: String,
      model: String,
      systemPrompt: String,
      userMessage: String,
      extraBodyJson: String
  ): Either[String, String]

  /** Merge provider-specific extra JSON (from config) into a base request body.
    *
    * Invalid or empty JSON is ignored, so a malformed setting never breaks a
    * provider that does not use it.
    */
  protected def mergeExtraBody(base: Json, extraJson: String): Json =
    parse(extraJson).toOption match
      case Some(extra) => base.deepMerge(extra)
      case None        => base
