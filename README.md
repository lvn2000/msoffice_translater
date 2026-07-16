# doc-translator

Translate Microsoft Office documents (`.pptx`, `.docx`, `.xls`, `.xlsx`) and PDF files (`.pdf`) using configurable LLM providers (DeepSeek, ChatGPT, Gemini, Claude). Only text is translated — images, charts, and other media are preserved as-is.

> **Note on PDF:** Since PDF is a rendering format without a structured text-run model, translated text is written into a **new** PDF. Original graphics, images, and precise layout are not preserved. For PPTX and DOCX all original formatting and media are retained.

The default language direction is **Ukrainian → Russian**, but any language pair is supported (configured in `application.conf`). Source language can be auto-detected by the model.

## Features

- **Configurable language direction** — set any source/target pair, or let the model auto-detect the source
- **Batch translation** — multiple text segments are sent in a single API call for efficiency (configurable batch size)
- **Formatting preservation** — font style, size, color, bold/italic, and other formatting are retained
- **PPTX support** — slides, text shapes, tables, grouped shapes
- **DOCX support** — body paragraphs and table cells (including nested per-cell paragraphs)
- **PDF support** — per-page paragraph extraction; translated output is a new PDF
- **XLS/XLSX support** — string cells in all sheets; numeric, boolean and formula cells are left untouched
- **Pluggable provider architecture** — switch between DeepSeek, ChatGPT, Gemini, or Claude with one config change
- **Auto-counter for output files** — if `_ru.pptx`/`_ru.docx` already exists, creates `_ru_1.pptx`, `_ru_2.pptx`, etc. (never overwrites)
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
│       │   ├── source/                  ← put .pptx / .docx / .pdf / .xls / .xlsx files here
│       │   │   └── .gitkeep
│       │   └── output/                  ← translated files appear here
│       │       └── .gitkeep
│       └── scala/
│           ├── Main.scala               ← entry point
│           ├── config/Config.scala      ← reads application.conf + env vars
│           ├── model/TextElement.scala  ← data model for text positions
│           ├── model/Batch.scala        ← batch data model
│           └── service/
│               ├── DocumentService.scala← shared batching utilities
│               ├── DocxService.scala    ← Apache POI docx read/write
│               ├── PdfService.scala     ← Apache PDFBox pdf read/write
│               ├── PptxService.scala    ← Apache POI pptx read/write
│               ├── XlsService.scala     ← Apache POI xls/xlsx read/write
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

The API key is read from the environment variable named in `llm.api-key-env` in `application.conf` (default: `API_KEY`):

```bash
export API_KEY="sk-your-key-here"
```

> **Tip:** add this line to your `~/.bashrc` or `~/.profile` to avoid typing it every time.
>
> For Ollama (local), any dummy value works: `export API_KEY="ollama"`

You can use a different env var name by changing `application.conf`:
```hocon
llm {
  api-key-env = "MY_CUSTOM_KEY"
}
```
then `export MY_CUSTOM_KEY="sk-..."`.

### 2. Place source files

```bash
cp /path/to/your/file.pptx src/main/resources/source/
cp /path/to/your/file.docx src/main/resources/source/
cp /path/to/your/file.pdf src/main/resources/source/
cp /path/to/your/file.xlsx src/main/resources/source/
```

### 3. Run

```bash
sbt run
```

### 4. Find results

Translated files appear in `src/main/resources/output/` with `_ru` suffix, e.g.:

```
для Кирг Актуальні зміни в МСА.pptx → для Кирг Актуальні зміни в МСА_ru.pptx
report.docx → report_ru.docx
presentation.pdf → presentation_ru.pdf
budget.xlsx → budget_ru.xlsx
```

## Configuration

All settings can be defined in `src/main/resources/application.conf` or overridden via environment variables:

| Setting | `application.conf` path | Env var | Default |
|---|---|---|---|
| Provider | `provider` | `PROVIDER` | `openai` |
| API key env var name | `llm.api-key-env` | — | `API_KEY` |
| API URL | `llm.api-url` | `API_URL` | `http://localhost:11434/v1/chat/completions` |
| Model | `llm.model` | `MODEL` | `llama3.2` |
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
| `openai` | OpenAI-compatible (Ollama, DeepSeek, ChatGPT, Groq…) | `llama3.2`, `gpt-4o`, `deepseek-chat` | `llm.api-url` + `llm.model` |
| `gemini` | Google Gemini | `gemini-2.0-flash`, `gemini-1.5-pro` | `llm.model` = model name; API key in URL query |
| `claude` | Anthropic Claude | `claude-sonnet-4-20250514`, `claude-haiku-3-5-sonnet` | `llm.model` = model ID; `x-api-key` header |

Examples for different providers:

```hocon
# Ollama / local model (default)
provider = "openai"
llm {
  api-key-env = "API_KEY"
  api-url     = "http://localhost:11434/v1/chat/completions"
  model       = "llama3.2"
}
```

```hocon
# DeepSeek
provider = "openai"
llm {
  api-key-env = "DEEPSEEK_API_KEY"
  api-url     = "https://api.deepseek.com/v1/chat/completions"
  model       = "deepseek-chat"
}
```

```hocon
# OpenAI / ChatGPT
provider = "openai"
llm {
  api-key-env = "OPENAI_API_KEY"
  api-url     = "https://api.openai.com/v1/chat/completions"
  model       = "gpt-4o"
}
```

```hocon
# Google Gemini
provider = "gemini"
llm {
  api-key-env = "GEMINI_API_KEY"
  model       = "gemini-2.0-flash"
}
# Note: api-url is ignored for Gemini — the adapter constructs the URL
```

```hocon
# Anthropic Claude
provider = "claude"
llm {
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
java -jar target/scala-3.6.4/doc-translator-assembly-1.0.0.jar
```

When running from a JAR, ensure the source/output directories exist on the filesystem and are configured via environment variables or `application.conf` next to the JAR.

## How It Works

1. **Extract** — the appropriate service (`PptxService` for `.pptx`, `DocxService` for `.docx`, `PdfService` for `.pdf`, `XlsService` for `.xls`/`.xlsx`) opens the file and walks every text run/paragraph/cell, recording each text's position
2. **Batch** — texts are grouped into batches (default 10)
3. **Translate** — each batch is sent via the configured `ProviderAdapter` (OpenAI-compatible, Gemini, or Claude) as a numbered list with a system prompt for the configured language direction. The response is parsed back into individual translations
4. **Write** — the service writes the translated text back. For PPTX/DOCX/XLS this preserves all original formatting and media. For PDF, a new document is created with the translated text

## Error Handling

- **Batch failures** — if a batch returns a mismatched count of translations, each text in that batch is retried individually
- **Unreachable API** — the app prints the error and continues with the next batch/file
- **Missing API key** — the app exits immediately with a clear message
- **Corrupt file** — the file is skipped and an error is printed

## Environment variables reference

```bash
export API_KEY="sk-..."              # required (default name; set via api-key-env)
export PROVIDER="openai"           # openai, gemini, claude
export MODEL="llama3.2"
export API_URL="http://localhost:11434/v1/chat/completions"
export SOURCE_DIR="/absolute/path"
export OUTPUT_DIR="/absolute/path"
export MAX_BATCH_SIZE="5"
export SOURCE_LANG="Ukrainian"       # "auto" or empty = auto-detect
export TARGET_LANG="Russian"
export LANG_CODE="ru"                # output filename suffix + doc language

## License

[MIT](LICENSE)
```
