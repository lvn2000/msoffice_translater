# pptx-translator

Translate Microsoft PowerPoint (`.pptx`) files using configurable LLM providers (DeepSeek, ChatGPT, Gemini, Claude). Only text is translated — images, charts, and other media are preserved as-is.

The default language direction is **Ukrainian → Russian**, but any language pair is supported (configured in `application.conf`). Source language can be auto-detected by the model.

## Features

- **Configurable language direction** — set any source/target pair, or let the model auto-detect the source
- **Batch translation** — multiple text segments are sent in a single API call for efficiency (configurable batch size)
- **Formatting preservation** — font style, size, color, bold/italic, and other formatting are retained
- **Table support** — text inside table cells is translated
- **Group shape support** — text inside nested grouped shapes is handled
- **Pluggable provider architecture** — switch between DeepSeek, ChatGPT, Gemini, or Claude with one config change
- **Auto-counter for output files** — if `_ru.pptx` already exists, creates `_ru_1.pptx`, `_ru_2.pptx`, etc. (never overwrites)
- **Idempotent output** — translated files get `_ru` suffix, originals are untouched

## Requirements

- **Java 11+**
- **sbt 1.10+**
- **API key** for your chosen provider:
  - DeepSeek: [platform.deepseek.com](https://platform.deepseek.com)
  - OpenAI: [platform.openai.com](https://platform.openai.com)
  - Google Gemini: [aistudio.google.com](https://aistudio.google.com)
  - Anthropic Claude: [console.anthropic.com](https://console.anthropic.com)

## Project Structure

```
├── build.sbt
├── README.md
├── .gitignore
├── src/
│   └── main/
│       ├── resources/
│       │   ├── application.conf        ← configuration (key, paths, language, model)
│       │   ├── logback.xml
│       │   ├── source/                  ← put .pptx files here
│       │   │   └── .gitkeep
│       │   └── output/                  ← translated files appear here
│       │       └── .gitkeep
│       └── scala/
│           ├── Main.scala               ← entry point
│           ├── config/Config.scala      ← reads application.conf + env vars
│           ├── model/TextElement.scala  ← data model for text positions
│           └── service/
│               ├── PptxService.scala    ← Apache POI read/write
│               ├── TranslationService.scala ← batch & parse logic
│               └── provider/
│                   ├── ProviderAdapter.scala  ← trait
│                   ├── OpenAiAdapter.scala    ← OpenAI-compatible
│                   ├── GeminiAdapter.scala    ← Google Gemini
│                   └── ClaudeAdapter.scala    ← Anthropic Claude
└── project/
    ├── build.properties
    └── plugins.sbt
```

## Quick Start

### 1. Set the API key

The API key is read from the environment variable named in `deepseek.api-key-env` in `application.conf` (default: `DEEPSEEK_API_KEY`):

```bash
export DEEPSEEK_API_KEY="sk-deepseek-key-here"
```

> **Tip:** add this line to your `~/.bashrc` or `~/.profile` to avoid typing it every time.

You can use a different env var name by changing `application.conf`:
```hocon
deepseek {
  api-key-env = "MY_CUSTOM_KEY"
}
```
then `export MY_CUSTOM_KEY="sk-..."`.

### 2. Place source files

```bash
cp /path/to/your/file.pptx src/main/resources/source/
```

### 3. Run

```bash
sbt run
```

### 4. Find results

Translated files appear in `src/main/resources/output/` with `_ru` suffix, e.g.:

```
для Кирг Актуальні зміни в МСА.pptx → для Кирг Актуальні зміни в МСА_ru.pptx
```

## Configuration

All settings can be defined in `src/main/resources/application.conf` or overridden via environment variables:

| Setting | `application.conf` path | Env var | Default |
|---|---|---|---|
| Provider | `provider` | `PROVIDER` | `openai` |
| API key env var name | `deepseek.api-key-env` | — | `DEEPSEEK_API_KEY` |
| API URL | `deepseek.api-url` | `DEEPSEEK_API_URL` | `https://api.deepseek.com/v1/chat/completions` |
| Model | `deepseek.model` | `DEEPSEEK_MODEL` | `deepseek-chat` |
| Source directory | `app.source-dir` | `SOURCE_DIR` | `src/main/resources/source` |
| Output directory | `app.output-dir` | `OUTPUT_DIR` | `src/main/resources/output` |
| Batch size | `app.max-batch-size` | `MAX_BATCH_SIZE` | `10` |
| Source language | `app.source-lang` | `SOURCE_LANG` | `auto` |
| Target language | `app.target-lang` | `TARGET_LANG` | `Russian` |

Resolution order: **`application.conf` → environment variable → hardcoded default**.

### Language direction

The `source-lang` and `target-lang` control translation direction:

| `source-lang` | `target-lang` | Behaviour |
|---|---|---|
| `auto` (or empty) | `Russian` | Auto-detect source → Russian |
| `Ukrainian` | `Russian` | Ukrainian → Russian |
| `English` | `Ukrainian` | English → Ukrainian |
| `German` | `English` | German → English |

Language names are passed to the model in the system prompt. Use names the model understands (e.g. `Ukrainian`, `Russian`, `English`, `German`, `French`, `Chinese`).

### Provider configuration

Set `provider` in `application.conf` to switch between backends:

| `provider` | Adapter | Supported models | Example config |
|---|---|---|---|
| `openai` | OpenAI-compatible (DeepSeek, ChatGPT, Groq, Together, etc.) | `deepseek-chat`, `gpt-4o`, `llama-3.3-70b-versatile` | `deepseek.api-url` + `deepseek.model` |
| `gemini` | Google Gemini | `gemini-2.0-flash`, `gemini-1.5-pro` | `deepseek.model` = model name; API key in URL query |
| `claude` | Anthropic Claude | `claude-sonnet-4-20250514`, `claude-haiku-3-5-sonnet` | `deepseek.model` = model ID; `x-api-key` header |

Examples for different providers:

```hocon
# DeepSeek (default)
provider = "openai"
deepseek {
  api-key-env = "DEEPSEEK_API_KEY"
  api-url     = "https://api.deepseek.com/v1/chat/completions"
  model       = "deepseek-chat"
}
```

```hocon
# OpenAI / ChatGPT
provider = "openai"
deepseek {
  api-key-env = "OPENAI_API_KEY"
  api-url     = "https://api.openai.com/v1/chat/completions"
  model       = "gpt-4o"
}
```

```hocon
# Google Gemini
provider = "gemini"
deepseek {
  api-key-env = "GEMINI_API_KEY"
  model       = "gemini-2.0-flash"
}
# Note: api-url is ignored for Gemini — the adapter constructs the URL
```

```hocon
# Anthropic Claude
provider = "claude"
deepseek {
  api-key-env = "ANTHROPIC_API_KEY"
  model       = "claude-sonnet-4-20250514"
}
# Note: api-url defaults to https://api.anthropic.com/v1/messages
```

### Batch size

The translation API processes multiple text segments in one call. Default is 10 texts per batch. If you encounter token limit errors, reduce this value. For longer texts, consider reducing to 3–5.

## Building a fat JAR

```bash
sbt assembly
java -jar target/scala-3.6.4/pptx-translator-assembly-1.0.0.jar
```

When running from a JAR, ensure the source/output directories exist on the filesystem and are configured via environment variables or `application.conf` next to the JAR.

## How It Works

1. **Extract** — `PptxService` opens the `.pptx` with Apache POI and walks every slide → shape → paragraph → text run, recording each text's position
2. **Batch** — texts are grouped into batches (default 10)
3. **Translate** — each batch is sent via the configured `ProviderAdapter` (OpenAI-compatible, Gemini, or Claude) as a numbered list with a system prompt for the configured language direction. The response is parsed back into individual translations
4. **Write** — `PptxService` opens the original file again and replaces each text run using its position as a lookup key, then saves to the output folder with `_ru` suffix

## Error Handling

- **Batch failures** — if a batch returns a mismatched count of translations, each text in that batch is retried individually
- **Unreachable API** — the app prints the error and continues with the next batch/file
- **Missing API key** — the app exits immediately with a clear message
- **Corrupt pptx** — the file is skipped and an error is printed

## Environment variables reference

```bash
export DEEPSEEK_API_KEY="sk-..."     # required (default name; set via api-key-env)
export PROVIDER="openai"           # openai, gemini, claude
export DEEPSEEK_MODEL="deepseek-chat"
export DEEPSEEK_API_URL="https://..."
export SOURCE_DIR="/absolute/path"
export OUTPUT_DIR="/absolute/path"
export MAX_BATCH_SIZE="5"
export SOURCE_LANG="Ukrainian"       # "auto" or empty = auto-detect
export TARGET_LANG="Russian"

## License

[MIT](LICENSE)
```
